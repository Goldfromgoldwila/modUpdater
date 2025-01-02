package core;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

@Component
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
    
            if (decompDir == null) {
                LOGGER.error("Decompiled directory not found for Minecraft version: {}", mcVersion);
                throw new IllegalStateException("Decompiled directory is missing.");
            }
    
            File modJsonFile = new File(decompDir, MOD_JSON_FILE);
    
            if (!modJsonFile.exists()) {
                LOGGER.error("Mod JSON file not found: {}", modJsonFile.getPath());
                throw new FileNotFoundException("Mod JSON file is missing in the directory: " + decompDir.getPath());
            }
    
            LOGGER.info("Mod JSON file found: {}", modJsonFile.getPath());
            modifyJsonFile(modJsonFile, mcVersion);
    
        } catch (Exception e) {
            LOGGER.error("Error processing mod for version {}: {}", mcVersion, e.getMessage(), e);
            throw new RuntimeException("Failed to process mod for version " + mcVersion, e);
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
        JsonObject jsonObject = readJsonFile(modJsonFile);
        JsonObject depends = getOrCreateDependsObject(jsonObject);

        // Process Minecraft version
        if (depends.has("minecraft")) {
            String minecraftVersion = depends.get("minecraft").getAsString();
            String updatedVersion = processMinecraftVersion(minecraftVersion, mcVersion);
            depends.addProperty("minecraft", updatedVersion);
        } else {
            depends.addProperty("minecraft", mcVersion);
        }

        // Update Fabric dependencies
        Optional.ofNullable(FABRIC_LOADER_VERSIONS.get(mcVersion))
                .ifPresent(loaderVersion -> depends.addProperty("fabricloader", loaderVersion));
        Optional.ofNullable(FABRIC_API_VERSIONS.get(mcVersion))
                .ifPresent(apiVersion -> depends.addProperty("fabric-api", apiVersion));

        LOGGER.info("Updated dependencies for Minecraft version: {}", mcVersion);

        saveJsonFile(modJsonFile, jsonObject);
    }

    private String processMinecraftVersion(String versionString, String newMcVersion) {
        String[] parts = versionString.split(" ");
        String upperVersion = null;

        for (String part : parts) {
            if (part.contains("<=")) {
                upperVersion = part.replace("<=", "").trim();
            }
        }

        LOGGER.info("Extracted upper Minecraft version: {}", upperVersion);
        return newMcVersion;
    }

    private JsonObject readJsonFile(File file) throws IOException {
        try (FileReader reader = new FileReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
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
        }
    }
}
