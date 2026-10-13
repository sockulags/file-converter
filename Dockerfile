FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
# Testerna körs i CI, där LibreOffice finns installerat.
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre-noble

# --no-install-recommends håller borta extra fontpaket (bl.a. OpenType/CFF) som LibreOffice
# annars kan välja som ersättning. Fontlistan nedan är den enda som finns i imagen.
RUN apt-get update \
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
 && rm -rf /var/lib/apt/lists/*

COPY docker/fonts.conf /etc/fonts/conf.d/99-file-converter.conf
COPY fonts/ /opt/fonts/
RUN fc-cache -f

RUN useradd --system --uid 1001 --create-home converter
USER 1001
WORKDIR /app
COPY --from=build /src/target/file-converter-*.jar app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=40"
EXPOSE 3000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
