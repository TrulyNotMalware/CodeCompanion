FROM eclipse-temurin:21.0.4_7-jdk-alpine
ARG JAR_FILE_NAME=application-alpha
ARG SERVER_PORT=80

ARG PROFILES="prod"
ENV PROFILE=${PROFILES}
COPY ./build/libs/$JAR_FILE_NAME.jar /app.jar

EXPOSE $SERVER_PORT
ENTRYPOINT exec java -jar -Dspring.profiles.active=${PROFILE} -Duser.timezone=Asia/Seoul /app.jar