FROM eclipse-temurin:25.0.4.1_1-jre-alpine
ARG JAR_FILE_NAME=application-alpha
ARG SERVER_PORT=80
# from CI/CD pipeline
ARG BUILD_DATE
ARG BUILD_NUMBER
ARG VERSION
ARG GIT_REF
ARG PROFILES="prod"

ENV PROFILE=${PROFILES}
ENV BUILD_DATE=${BUILD_DATE}
ENV BUILD_NUMBER=${BUILD_NUMBER}
ENV VERSION=${VERSION}
ENV GIT_REF=${GIT_REF}
COPY ./build/libs/$JAR_FILE_NAME.jar /app.jar

EXPOSE $SERVER_PORT
# Heap = 50% of the 2Gi limit in k8s/deployment.yaml (1Gi); the 1536Mi request there covers heap plus non-heap memory.
# ExitOnOutOfMemoryError: Boot's liveness state does not reflect an OOM, so a Pod with a damaged heap would stay Ready.
ENTRYPOINT exec java -XX:MaxRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError -Dspring.profiles.active=${PROFILE} -Duser.timezone=Asia/Seoul -jar /app.jar
