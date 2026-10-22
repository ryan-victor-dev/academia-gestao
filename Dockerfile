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

# Usuário sem privilégios: o sistema não roda como root.
# O pebble (gerenciador de serviços da Canonical, que vem na imagem base) nunca é usado
# aqui e é compilado com um Go com vulnerabilidades conhecidas: menos superfície de ataque.
RUN groupadd --system app && useradd --system --gid app app \
    && rm -f /usr/bin/pebble

COPY --from=build /app/target/academia-gestao-*.jar app.jar
USER app
EXPOSE 8090
# A JVM usa até 75% da memória do container (o padrão, 25%, desperdiça o teto do Compose).
# Se a memória acabar (OutOfMemoryError), a JVM sai em vez de seguir viva e travada: sem
# isso, o processo não morre, e o restart do Compose nunca traz a aplicação de volta.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]