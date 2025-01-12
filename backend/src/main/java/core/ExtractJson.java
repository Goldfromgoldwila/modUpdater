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
import core.MinecraftVersionHandler.ComparisonResult;  // Add this import

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

            // Log original file content
            LOGGER.info("Original mod JSON content:");
            logFileContent(modJsonFile);

            // First read the JSON to get current version
            JsonObject modJson = readJsonFile(modJsonFile);
            JsonObject depends = getOrCreateDependsObject(modJson);
            
            // Get current version and clean it
            String currentVersion = "";
            if (depends.has("minecraft")) {
                currentVersion = depends.get("minecraft").getAsString();
                this.cleanVersion = processMinecraftVersion(currentVersion);
                logCurrentVersion(currentVersion, cleanVersion);
            }

            // Call MinecraftVersionHandler first for validation
            if (versionHandler != null) {
                try {
                    ComparisonResult result = versionHandler.compareMinecraftVersions(cleanVersion, targetVersion);
                    LOGGER.info("Version comparison result: {}", result);
                    
                    if (result == null) {
                        throw new IllegalStateException("Version comparison failed");
                    }
                } catch (Exception e) {
                    LOGGER.error("Version validation failed: {}", e.getMessage());
                    throw new IllegalStateException("Version validation failed", e);
                }
            }

            // Only proceed with updates if version check passes
            updateModJson(modJson, modJsonFile, targetVersion);

            // Log modified file content
            LOGGER.info("Modified mod JSON content:");
            logFileContent(modJsonFile);

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
        // Log before changes
        LOGGER.info("Current JSON state before updates: {}", modJson.toString());
        
        JsonObject depends = modJson.getAsJsonObject("depends");
        
        // Update Minecraft version while preserving the format
        if (depends.has("minecraft")) {
            String currentVersion = depends.get("minecraft").getAsString();
            LOGGER.info("Current minecraft version format: {}", currentVersion);
            
            // Handle version range format (e.g., ">=1.20 <=1.20.1")
            if (currentVersion.contains(" ")) {
                // Take the format from the second part (after space)
                String[] parts = currentVersion.split("\\s+");
                String versionPrefix = ">="; // Default prefix
                
                // Find the part with version comparison
                for (String part : parts) {
                    if (part.contains("<=")) {
                        versionPrefix = "<=";
                        break;
                    } else if (part.contains(">=")) {
                        versionPrefix = ">=";
                        break;
                    }
                }
                
                // Update with preserved format
                depends.addProperty("minecraft", versionPrefix + targetVersion);
            } else {
                // Preserve existing format (e.g., ">=1.21.3")
                String versionPrefix = "";
                if (currentVersion.startsWith(">=")) {
                    versionPrefix = ">=";
                } else if (currentVersion.startsWith("<=")) {
                    versionPrefix = "<=";
                }
                depends.addProperty("minecraft", versionPrefix + targetVersion);
            }
        } else {
            // If no existing version, use ">=" format
            depends.addProperty("minecraft", ">=" + targetVersion);
        }
        
        // Update Fabric dependencies
        String loaderVersion = FABRIC_LOADER_VERSIONS.get(targetVersion);
        String apiVersion = FABRIC_API_VERSIONS.get(targetVersion);
        
        if (loaderVersion != null) {
            depends.addProperty("fabricloader", loaderVersion);
        }
        if (apiVersion != null && !depends.has("fabric-api")) {
            depends.addProperty("fabric-api", apiVersion);
        }

        LOGGER.info("Updated dependencies: {}", depends);
        
        // Log final state before saving
        LOGGER.info("Final JSON state: {}", modJson.toString());
        
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
        if (versionString == null || versionString.trim().isEmpty()) {
            return "";
        }

        versionString = versionString.trim();
        
        // Handle version range format (e.g., ">=1.20 <=1.20.1")
        if (versionString.contains(" ")) {
            return Arrays.stream(versionString.split("\\s+"))
                    .map(this::cleanVersionString)
                    .filter(part -> !part.isEmpty())
                    .findFirst()
                    .orElse("");
        }
        
        return cleanVersionString(versionString);
    }

    private String cleanVersionString(String version) {
        // Remove any prefix/comparators (>=, <=, >, <, =)
        return version.replaceAll("[^0-9.]", "");
    }

    private void logCurrentVersion(String currentVersion, String cleanVersion) {
        LOGGER.info("Found minecraft version in depends: {}", currentVersion);
        LOGGER.info("Cleaned version for comparison: {}", cleanVersion);
    }

    private void logFileContent(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            reader.lines().forEach(LOGGER::info);
        } catch (IOException e) {
            LOGGER.error("Error reading file: {}", e.getMessage());
        }
    }

    private void handleVersionChanges(String targetVersion) {
        try {
            if (versionHandler == null) {
                LOGGER.error("MinecraftVersionHandler is not initialized");
                return;
            }

            LOGGER.info("Processing version change from {} to {}", cleanVersion, targetVersion);
            
            // Extract clean version without prefixes
            String sourceVersion = cleanVersion.replaceAll("[^0-9.]", "");
            
            // Call version handler methods
            versionHandler.setCleanVersion(sourceVersion);
            versionHandler.setMcVersion(targetVersion);
            
            try {
                ComparisonResult result = versionHandler.compareMinecraftVersions(sourceVersion, targetVersion);
                LOGGER.info("Version comparison result: {}", result);
            } catch (Exception e) {
                LOGGER.error("Error comparing versions: {}", e.getMessage(), e);
            }
            
        } catch (Exception e) {
            LOGGER.error("Error in version change processing: {}", e.getMessage(), e);
            throw new IllegalStateException("Version change processing failed", e);
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
