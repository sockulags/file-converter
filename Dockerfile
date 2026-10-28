FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
# Testerna körs i CI, där LibreOffice finns installerat.
RUN mvn -q -B -DskipTests package

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
