FROM openjdk:17-slim

WORKDIR /app

COPY target/minecraft-mod-updater-1.0-SNAPSHOT.jar app.jar

EXPOSE 8080

COPY 1.21 versions/1.21
COPY 1.21.1 versions/1.21.1
COPY 1.21.2 versions/1.21.2
COPY 1.21.3 versions/1.21.3
COPY 1.21.4 versions/1.21.4 

# Install only the essential Meld dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    meld \
    python3-minimal \
    python3-gi \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p diff_results

# Set environment variable for display
ENV DISPLAY=:0

# Set non-interactive mode for apt
ENV DEBIAN_FRONTEND=noninteractive

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]