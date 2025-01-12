FROM openjdk:17-slim

WORKDIR /app

COPY target/minecraft-mod-updater-1.0-SNAPSHOT.jar app.jar

EXPOSE 8080

COPY 1.21 versions/1.21
COPY 1.21.1 versions/1.21.1
COPY 1.21.2 versions/1.21.2
COPY 1.21.3 versions/1.21.3
COPY 1.21.4 versions/1.21.4 

# Copy WinMerge from local project
COPY winmerge/ /opt/winmerge/

# Set WinMerge path environment variable
ENV WINMERGE_PATH=/opt/winmerge/WinMergeU.exe

ENTRYPOINT ["java", "-jar", "app.jar"]