package core;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Map<String, String> FABRIC_LOADER_VERSIONS = Map.of(
        "1.20", ">=0.14.21",
        "1.20.1", ">=0.14.21",
        "1.20.2", ">=0.14.21",
        "1.20.3", ">=0.15.0",
        "1.20.4", ">=0.15.0",
        "1.21", ">=0.15.0",
        "1.21.1", ">=0.15.0",
        "1.21.2", ">=0.15.0",
        "1.21.3", ">=0.15.0",
        "1.21.4", ">=0.15.0"
    );

    private static final Map<String, String> FABRIC_API_VERSIONS = Map.of(
        "1.20", ">=0.83.0",
        "1.20.1", ">=0.83.0",
        "1.20.2", ">=0.89.0",
        "1.20.3", ">=0.91.0",
        "1.20.4", ">=0.91.0",
        "1.21", ">=0.92.0",
        "1.21.1", ">=0.92.0",
        "1.21.2", ">=0.92.0",
        "1.21.3", ">=0.92.0",
        "1.21.4", ">=0.92.0"
    );

    public void processMod(String mcVersion) {
        try {
            LOGGER.info("Processing mod for Minecraft version: {}", mcVersion);
            File decompDir = findLatestModDirectory();

            if (decompDir != null) {
                File modJsonFile = new File(decompDir, MOD_JSON_FILE);
                int retryCount = 0;
                int maxRetries = 5; // Maximum number of retries

                    // Retry mechanism for finding the mod JSON file
                while (!modJsonFile.exists() && retryCount < maxRetries) {
                    LOGGER.info("Mod JSON file not found: {}. Retrying in 4 seconds...", modJsonFile.getPath());
                    Thread.sleep(4000); // 4 seconds delay
                    retryCount++;
                }

            
                if (modJsonFile.exists()) {
                    LOGGER.info("Mod JSON file found: {}", modJsonFile.getPath());
                    modifyJsonFile(modJsonFile, mcVersion);
                } else {
                    LOGGER.error("Mod JSON file not found: {}", modJsonFile.getPath());
                }
            } else {
                LOGGER.error("No decompiled directory found.");
                // Auto-retry for Missing Directory
                LOGGER.info("Retrying in 5 seconds...");
                Thread.sleep(5000); // 5 seconds delay
                processMod(mcVersion); // Retry
            }
        } catch (Exception e) {
            LOGGER.error("Error processing mod: {}", e.getMessage(), e);
        }
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
            LOGGER.error("Error finding latest mod directory: {}", e.getMessage());
            return null;
        }
    }

    private void modifyJsonFile(File modJsonFile, String mcVersion) throws IOException {
        logFileContent(modJsonFile); // Log old file content
        JsonObject jsonObject = readJsonFile(modJsonFile);
        JsonObject depends = getOrCreateDependsObject(jsonObject);
    
        // Log all changes made to the JSON file
        LOGGER.info("Old JSON data: {}", jsonObject);
        LOGGER.info("Old dependencies: {}", depends);
    
        // Replace any existing version or range under "minecraft" with the new version
        if (depends.has("minecraft")) {
            String currentMinecraftVersion = depends.get("minecraft").getAsString();
            LOGGER.info("Current Minecraft version: {}", currentMinecraftVersion);
    
            // Update the Minecraft version to the new version from the frontend
            depends.addProperty("minecraft", mcVersion);
            LOGGER.info("Updated Minecraft version from {} to {}", currentMinecraftVersion, mcVersion);
        } else {
            depends.addProperty("minecraft", mcVersion);
            LOGGER.info("Added Minecraft version: {}", mcVersion);
        }
    
        // Log the updated depends object
        LOGGER.info("Updated depends object: {}", depends);
    
        // Update Fabric dependencies
        Optional.ofNullable(FABRIC_LOADER_VERSIONS.get(mcVersion))
                .ifPresent(loaderVersion -> {
                    String oldLoaderVersion = depends.get("fabricloader") != null ? depends.get("fabricloader").getAsString() : "not set";
                    depends.addProperty("fabricloader", loaderVersion);
                    LOGGER.info("Updated Fabric Loader version from {} to {}", oldLoaderVersion, loaderVersion);
                });
        Optional.ofNullable(FABRIC_API_VERSIONS.get(mcVersion))
                .ifPresent(apiVersion -> {
                    String oldApiVersion = depends.get("fabric-api") != null ? depends.get("fabric-api").getAsString() : "not set";
                    depends.addProperty("fabric-api", apiVersion);
                    LOGGER.info("Updated Fabric API version from {} to {}", oldApiVersion, apiVersion);
                });
    
        // Log the new dependencies
        LOGGER.info("New dependencies: {}", depends);
    
        // Save changes
        saveJsonFile(modJsonFile, jsonObject);
    
        logFileContent(modJsonFile); // Log new file content
    }
    

    private String processMinecraftVersion(String versionString, String newMcVersion) {
    // Remove any range indicators (e.g., >=, <=) and extract the exact version
    String exactVersion = versionString.replaceAll("[><=]", "").trim();
    String[] parts = exactVersion.split("\\s+");
    
    // If there's only one version, use it; otherwise, use the new version
    if (parts.length == 1) {
        exactVersion = parts[0];
    } else {
        exactVersion = newMcVersion;
    }
    
    LOGGER.info("Extracted exact Minecraft version: {}", exactVersion);
    return exactVersion;
    }
    
    private JsonObject readJsonFile(File file) throws IOException {
        try (FileReader reader = new FileReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private void logFileContent(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOGGER.info(line);
            }
        } catch (IOException e) {
            LOGGER.error("Error reading file: {}", e.getMessage());
        }
    }

    private JsonObject getOrCreateDependsObject(JsonObject jsonObject) {
        if (!jsonObject.has("depends")) {
            jsonObject.add("depends", new JsonObject());
            LOGGER.info("Created 'depends' section.");
        }
        return jsonObject.getAsJsonObject("depends");
    }

    private void saveJsonFile(File file, JsonObject jsonObject) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(jsonObject, writer);
            LOGGER.info("Successfully saved updated JSON to file: {}", file.getPath());
        } catch (IOException e) {
            LOGGER.error("Error saving JSON file: {}", e.getMessage());
            throw e;
        }
    }
    
}