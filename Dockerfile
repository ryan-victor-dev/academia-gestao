# ===== Etapa 1: compilar =====
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Primeiro só o que define as dependências (aproveita o cache do Docker)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

# Depois o código, e gera o .jar
COPY src ./src
RUN ./mvnw -B -q package -DskipTests

# ===== Etapa 2: executar =====
FROM eclipse-temurin:25-jre
WORKDIR /app

# Usuário sem privilégios: o sistema não roda como root
RUN groupadd --system app && useradd --system --gid app app

COPY --from=build /app/target/academia-gestao-*.jar app.jar
USER app
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]