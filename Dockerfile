# Usage: docker build --build-arg MODULE=assistant -t helpdesk-assistant .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY ticket-mcp-server/pom.xml ticket-mcp-server/
COPY assistant/pom.xml assistant/
RUN ./mvnw -q -B dependency:go-offline
COPY . .
ARG MODULE
RUN ./mvnw -q -B -DskipTests -pl ${MODULE} -am package \
 && java -Djarmode=tools -jar ${MODULE}/target/${MODULE}-*-SNAPSHOT.jar extract --layers --destination /out

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app && mkdir /models && chown 10001 /models
WORKDIR /app
COPY --from=build /out/dependencies/ ./
COPY --from=build /out/spring-boot-loader/ ./
COPY --from=build /out/snapshot-dependencies/ ./
COPY --from=build /out/application/ ./
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" EMBEDDING_CACHE_DIR=/models
ENTRYPOINT ["sh", "-c", "exec java -jar *.jar"]
