FROM openjdk:17-slim

WORKDIR /app

COPY target/minecraft-mod-updater-1.0-SNAPSHOT.jar app.jar

EXPOSE 8080

COPY 1.21 versions/1.21
COPY 1.21.1 versions/1.21.1
COPY 1.21.2 versions/1.21.2
COPY 1.21.3 versions/1.21.3
COPY 1.21.4 versions/1.21.4 

# Install meld and its dependencies
RUN apt-get update && apt-get install -y \
    meld \
    python3 \
    python3-gi \
    python3-gi-cairo \
    gir1.2-gtk-3.0 \
    && rm -rf /var/lib/apt/lists/*

# Set environment variable for display (needed for GUI apps in container)
ENV DISPLAY=:0

ENTRYPOINT ["java", "-jar", "app.jar"]