FROM eclipse-temurin:25.0.4_7-jre-alpine
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
# 50% of the 2Gi limit in k8s/deployment.yaml keeps the heap inside the 1Gi request, so the Pod is not the first eviction candidate.
ENTRYPOINT exec java -XX:MaxRAMPercentage=50.0 -jar -Dspring.profiles.active=${PROFILE} -Duser.timezone=Asia/Seoul /app.jar
