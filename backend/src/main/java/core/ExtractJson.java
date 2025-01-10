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

    private String mcVersion; // Add this line to declare mcVersion

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

    private String cleanVersion;

    @Autowired
    private MinecraftVersionHandler.FileCache fileCache;

    @Autowired
    private MinecraftVersionHandler.ValidationService validationService;

    @Autowired
    private MinecraftVersionHandler.DiffGenerator diffGenerator;

    public void processMod(String mcVersion) {
        try {
            LOGGER.info("Processing mod for Minecraft version: {}", mcVersion);
            File decompDir = findLatestModDirectory();

            if (decompDir != null) {
                File modJsonFile = new File(decompDir, MOD_JSON_FILE);
                int retryCount = 0;
                int maxRetries = 5;

                while (!modJsonFile.exists() && retryCount < maxRetries) {
                    LOGGER.info("Mod JSON file not found: {}. Retrying in 4 seconds...", modJsonFile.getPath());
                    Thread.sleep(4000);
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
                LOGGER.info("Retrying in 5 seconds...");
                Thread.sleep(5000);
                processMod(mcVersion);
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
        logFileContent(modJsonFile);
        JsonObject jsonObject = readJsonFile(modJsonFile);
        JsonObject depends = getOrCreateDependsObject(jsonObject);

        LOGGER.info("Old JSON data: {}", jsonObject);
        LOGGER.info("Old dependencies: {}", depends);

        if (depends.has("minecraft")) {
            String currentMinecraftVersion = depends.get("minecraft").getAsString();
            LOGGER.info("Current Minecraft version: {}", currentMinecraftVersion);

            this.cleanVersion = processMinecraftVersion(currentMinecraftVersion);
            this.mcVersion = mcVersion;

            depends.addProperty("minecraft", mcVersion);
            LOGGER.info("Updated Minecraft version from {} to {}", currentMinecraftVersion, mcVersion);
            LOGGER.info("Clean version: {}", this.cleanVersion);
        } else {
            depends.addProperty("minecraft", mcVersion);
            LOGGER.info("Added Minecraft version: {}", mcVersion);
        }

        LOGGER.info("Updated depends object: {}", depends);

       // Update Fabric dependencies
        String loaderVersion = FABRIC_LOADER_VERSIONS.get(mcVersion);
        if (loaderVersion != null) {
            String oldLoaderVersion = depends.has("fabricloader") ? depends.get("fabricloader").getAsString() : "not set";
            depends.addProperty("fabricloader", loaderVersion);
            LOGGER.info("Updated Fabric Loader version from {} to {}", oldLoaderVersion, loaderVersion);
        }

        String apiVersion = FABRIC_API_VERSIONS.get(mcVersion);
        if (apiVersion != null) {
            String oldApiVersion = depends.has("fabric-api") ? depends.get("fabric-api").getAsString() : "not set";
            depends.addProperty("fabric-api", apiVersion);
            LOGGER.info("Updated Fabric API version from {} to {}", oldApiVersion, apiVersion);
        }

        LOGGER.info("New dependencies: {}", depends);

        saveJsonFile(modJsonFile, jsonObject);
        logFileContent(modJsonFile);

        triggerVersionComparison();
        LOGGER.info("Triggering version comparison between cleanVersion: {} and mcVersion: {}", cleanVersion, mcVersion);
    }

    private String processMinecraftVersion(String versionString) {
        versionString = versionString.trim();

        if (versionString.contains(" ")) {
            String[] parts = versionString.split("\\s+");
            for (int i = parts.length - 1; i >= 0; i--) {
                String part = parts[i].replaceAll("[^0-9.]", "");
                if (!part.isEmpty()) {
                    return part;
                }
            }
        }
        return versionString.replaceAll("[^0-9.]", "");
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

    // Getter for cleanVersion
    public String getCleanVersion() {
        return cleanVersion;
    }

    // Getter for mcVersion
    public String getMcVersion() {
        return mcVersion;
    }

    private void triggerVersionComparison() {
        LOGGER.info("Triggering comparison between cleanVersion: {} and mcVersion: {}", cleanVersion, mcVersion);
        MinecraftVersionHandler versionHandler = new MinecraftVersionHandler(fileCache, validationService, diffGenerator);
        versionHandler.compareMinecraftVersions(cleanVersion, mcVersion);
    }


}
