package core;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

@Component
@RestController
@RequestMapping("/api")
public class ExtractJson {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtractJson.class);
    private static final String DECOMPILED_DIR = "decompiled_mods";
    private static final String MOD_JSON_FILE = "fabric.mod.json";

    private String mcVersion;
    private String cleanVersion;
    private final MinecraftVersionHandler versionHandler;

    // Define ComparisonResult enum here to avoid dependency issues
    public enum VersionCompatibility {
        COMPATIBLE,
        INCOMPATIBLE,
        UNKNOWN
    }

    private static final Map<String, String> FABRIC_LOADER_VERSIONS = Map.of(
        "1.21", ">=0.14.21",
        "1.21.1", ">=0.14.21",
        "1.21.2", ">=0.14.21",
        "1.21.3", ">=0.15.0",
        "1.21.4", ">=0.15.0"
    );

    private static final Map<String, String> FABRIC_API_VERSIONS = Map.of(
        "1.21", ">=0.83.0",
        "1.21.1", ">=0.83.0",
        "1.21.2", ">=0.89.0",
        "1.21.3", ">=0.91.0",
        "1.21.4", ">=0.91.0"
    );

    @Autowired
    public ExtractJson(MinecraftVersionHandler versionHandler) {
        this.versionHandler = versionHandler;
    }

    public void processMod(String targetVersion) {
        try {
            LOGGER.info("Starting mod processing for target version: {}", targetVersion);
            File modDir = findLatestModDirectory();
            if (modDir == null) {
                throw new IllegalStateException("No decompiled directory found");
            }

            File modJsonFile = new File(modDir, MOD_JSON_FILE);
            if (!waitForModJson(modJsonFile)) {
                throw new IllegalStateException("Mod JSON file not found after waiting");
            }

            JsonObject modJson = readAndValidateModJson(modJsonFile, targetVersion);
            updateModJson(modJson, modJsonFile, targetVersion);

        } catch (Exception e) {
            LOGGER.error("Error processing mod: {}", e.getMessage(), e);
            throw new RuntimeException("Mod processing failed", e);
        }
    }

    private boolean waitForModJson(File modJsonFile) {
        int retryCount = 0;
        int maxRetries = 5;
        while (!modJsonFile.exists() && retryCount < maxRetries) {
            LOGGER.info("Waiting for mod JSON file (attempt {}/{})", retryCount + 1, maxRetries);
            try {
                Thread.sleep(2000); // Reduced wait time to 2 seconds
                retryCount++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return modJsonFile.exists();
    }

    private JsonObject readAndValidateModJson(File modJsonFile, String targetVersion) throws IOException {
        JsonObject modJson = readJsonFile(modJsonFile);
        JsonObject depends = getOrCreateDependsObject(modJson);
        
        if (depends.has("minecraft")) {
            String currentVersion = depends.get("minecraft").getAsString();
            this.cleanVersion = processMinecraftVersion(currentVersion);
            this.mcVersion = targetVersion;
            
            LOGGER.info("Current mod version: {}, Target version: {}", cleanVersion, targetVersion);
            
            // Validate version compatibility
            try {
                if (!isVersionCompatible(cleanVersion, targetVersion)) {
                    throw new IllegalArgumentException(
                        String.format("Incompatible version change from %s to %s", cleanVersion, targetVersion)
                    );
                }
            } catch (Exception e) {
                LOGGER.error("Version comparison failed", e);
                throw new IllegalStateException("Version validation failed", e);
            }
        } else {
            LOGGER.warn("No Minecraft version found in mod dependencies");
            this.mcVersion = targetVersion;
        }
        
        return modJson;
    }

    private boolean isVersionCompatible(String currentVersion, String targetVersion) {
        // Simple version compatibility check
        String[] current = currentVersion.split("\\.");
        String[] target = targetVersion.split("\\.");
        
        // Compare major versions
        int currentMajor = Integer.parseInt(current[0]);
        int targetMajor = Integer.parseInt(target[0]);
        
        if (currentMajor != targetMajor) {
            return false;
        }
        
        // Compare minor versions
        int currentMinor = current.length > 1 ? Integer.parseInt(current[1]) : 0;
        int targetMinor = target.length > 1 ? Integer.parseInt(target[1]) : 0;
        
        // Allow updates within the same major version
        return currentMinor <= targetMinor;
    }

    private void updateModJson(JsonObject modJson, File modJsonFile, String targetVersion) throws IOException {
        JsonObject depends = modJson.getAsJsonObject("depends");
        
        // Update Minecraft version
        depends.addProperty("minecraft", targetVersion);
        
        // Update Fabric dependencies
        String loaderVersion = FABRIC_LOADER_VERSIONS.get(targetVersion);
        String apiVersion = FABRIC_API_VERSIONS.get(targetVersion);
        
        if (loaderVersion != null) {
            depends.addProperty("fabricloader", loaderVersion);
        }
        if (apiVersion != null) {
            depends.addProperty("fabric-api", apiVersion);
        }

        LOGGER.info("Updated dependencies: {}", depends);
        saveJsonFile(modJsonFile, modJson);
        logFileContent(modJsonFile);
    }

    private File findLatestModDirectory() {
        try {
            Path decompiledDirPath = Paths.get(DECOMPILED_DIR);
            return Files.list(decompiledDirPath)
                    .filter(Files::isDirectory)
                    .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .map(Path::toFile)
                    .orElse(null);
        } catch (IOException e) {
            LOGGER.error("Error finding latest mod directory", e);
            return null;
        }
    }

    private JsonObject readJsonFile(File file) throws IOException {
        try (FileReader reader = new FileReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private void saveJsonFile(File file, JsonObject jsonObject) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(jsonObject, writer);
        }
    }

    private JsonObject getOrCreateDependsObject(JsonObject jsonObject) {
        if (!jsonObject.has("depends")) {
            jsonObject.add("depends", new JsonObject());
        }
        return jsonObject.getAsJsonObject("depends");
    }

    private String processMinecraftVersion(String versionString) {
        versionString = versionString.trim();
        if (versionString.contains(" ")) {
            return Arrays.stream(versionString.split("\\s+"))
                    .map(part -> part.replaceAll("[^0-9.]", ""))
                    .filter(part -> !part.isEmpty())
                    .findFirst()
                    .orElse(versionString);
        }
        return versionString.replaceAll("[^0-9.]", "");
    }

    private void logFileContent(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            reader.lines().forEach(LOGGER::info);
        } catch (IOException e) {
            LOGGER.error("Error reading file: {}", e.getMessage());
        }
    }

    // Getters
    public String getCleanVersion() {
        return cleanVersion;
    }

    public String getMcVersion() {
        return mcVersion;
    }
}
