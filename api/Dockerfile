# Etapa de build: compila y empaqueta el jar. Las pruebas corren en CI (./mvnw verify),
# no aquí, porque necesitan Docker para Testcontainers.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Primero el pom y el wrapper: la capa de dependencias se reutiliza mientras no cambie el pom
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sh ./mvnw --batch-mode dependency:go-offline

COPY src src
RUN sh ./mvnw --batch-mode -DskipTests package \
    && mv target/api-*.jar app.jar

# Etapa final: solo el JRE y el jar, sin usuario root
FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER app

EXPOSE 8080
# La conexión a la base y las credenciales llegan por variables de entorno
# (POSTGRES_HOST, POSTGRES_USER, POSTGRES_PASSWORD...). Nada se hornea en la imagen.
ENTRYPOINT ["java", "-jar", "app.jar"]
