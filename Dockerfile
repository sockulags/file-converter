FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
# Testerna körs i CI, där LibreOffice finns installerat.
RUN mvn -q -B -DskipTests package

# Chrome for Testing, eftersom Ubuntus chromium-paket är en snap som inte går att köra i en container.
# Versionen uppdateras för hand: https://googlechromelabs.github.io/chrome-for-testing/
FROM eclipse-temurin:25-jre-resolute@sha256:acd83cdf6c9b5bfcd7c4e9d3e1969bacaacdcfa034c074500ab28610fb840f81 AS chrome
ARG CHROME_VERSION=153.0.8010.12
ADD https://storage.googleapis.com/chrome-for-testing-public/${CHROME_VERSION}/linux64/chrome-headless-shell-linux64.zip /tmp/chrome.zip
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip \
 && unzip -q /tmp/chrome.zip -d /opt \
 && mv /opt/chrome-headless-shell-linux64 /opt/chrome-headless-shell

# Låst på digest så att bygget är reproducerbart. Dependabot (docker) föreslår nya digester.
FROM eclipse-temurin:25-jre-resolute@sha256:acd83cdf6c9b5bfcd7c4e9d3e1969bacaacdcfa034c074500ab28610fb840f81

# --no-install-recommends håller borta extra fontpaket (bl.a. OpenType/CFF) som LibreOffice
# annars kan välja som ersättning. Fontlistan nedan är den enda som finns i imagen.
# upgrade tar in säkerhetsrättningar som kommit efter att basimagen byggdes.
# tini blir PID 1 och tar hand om föräldralösa soffice-processer, så att de inte blir zombier.
# procps ger ps, som JODConverter använder för att hitta och döda soffice-processer.
RUN apt-get update \
 && apt-get upgrade -y --no-install-recommends \
 && apt-get install -y --no-install-recommends \
      libreoffice-writer-nogui \
      libreoffice-calc-nogui \
      libreoffice-impress-nogui \
      fontconfig \
      fonts-dejavu-core \
      fonts-liberation \
      fonts-liberation2 \
      fonts-crosextra-carlito \
      fonts-crosextra-caladea \
      tini \
      procps \
 && rm -rf /var/lib/apt/lists/*

# Chromium för mallar som behöver flexbox, grid eller JavaScript (se EngineSelector). Bara den
# uppackade katalogen kopieras in, inte zip-filen.
COPY --from=chrome /opt/chrome-headless-shell /opt/chrome-headless-shell
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libnss3 \
      libatk1.0-0t64 \
      libatk-bridge2.0-0t64 \
      libatspi2.0-0t64 \
      libxcomposite1 \
      libxdamage1 \
      libxfixes3 \
      libxrandr2 \
      libgbm1 \
      libxkbcommon0 \
      libasound2t64 \
 && rm -rf /var/lib/apt/lists/* \
 && /opt/chrome-headless-shell/chrome-headless-shell --version

COPY docker/fonts.conf /etc/fonts/conf.d/99-file-converter.conf
COPY fonts/ /opt/fonts/
RUN fc-cache -f

# Grupp 1001 matchar fsGroup i Kubernetes, så att den delade volymen blir skrivbar.
RUN groupadd --system --gid 1001 converter \
 && useradd --system --uid 1001 --gid 1001 --create-home converter \
 && mkdir /data && chown 1001:1001 /data
USER 1001:1001
# Uppladdade fonter och alias. Montera en delad volym här i Kubernetes.
ENV CONVERTER_ASSETS_DIR=/data
WORKDIR /app
COPY --from=build /src/target/file-converter-*.jar app.jar

# ExitOnOutOfMemoryError: efter ett OutOfMemoryError är JVM:en i ett osäkert läge men svarar ändå på
# /health. Bättre att processen avslutas och Kubernetes startar om podden.
# Kubernetes-manifestet sätter hela JAVA_TOOL_OPTIONS (med ActiveProcessorCount) och ersätter detta.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=40 -XX:+ExitOnOutOfMemoryError"
EXPOSE 3000 9090
ENTRYPOINT ["tini", "--", "java", "-jar", "/app/app.jar"]
