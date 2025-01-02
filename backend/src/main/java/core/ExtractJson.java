package core;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ExtractJson {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtractJson.class);
    private static final String DECOMPILED_DIR = "decompiled_mods";
    private static final String MOD_JSON_FILE = "fabric.mod.json";
    private static String lastProcessedMcVersion;
    private static String lastProcessedOriginalVersion;

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
            LOGGER.info("Processing mods for Minecraft version: {}", mcVersion);
            File decompDir = findLatestModDirectory();

            if (decompDir != null) {
                File modJsonFile = new File(decompDir, MOD_JSON_FILE);
                if (modJsonFile.exists()) {
                    LOGGER.info("Validated existence of {}", modJsonFile.getPath());
                } else {
                    LOGGER.error("Mod JSON file does not exist: {}", modJsonFile.getPath());
                }
            } else {
                LOGGER.error("Decompiled directory not found for Minecraft version: {}", mcVersion);
            }
        } catch (Exception e) {
            LOGGER.error("Error processing mod: {}", e.getMessage(), e);
        }
    }

    private File findLatestModDirectory() {
        Path decompiledDirPath = Paths.get(DECOMPILED_DIR);
        try {
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

        // Save the original Minecraft version
        String originalVersion = depends.has("minecraft") ? depends.get("minecraft").getAsString() : "not set";
        lastProcessedOriginalVersion = originalVersion;
        LOGGER.info("Original Minecraft version: {}", originalVersion);

        // Extract only the upper version
        String upperVersion = extractUpperVersion(originalVersion);
        LOGGER.info("Extracted upper version: {}", upperVersion);

        // Update the Minecraft version with the upper version
        depends.addProperty("minecraft", upperVersion);
        lastProcessedMcVersion = upperVersion;

        // Update Fabric dependencies
        Optional.ofNullable(FABRIC_LOADER_VERSIONS.get(upperVersion))
                .ifPresent(loaderVersion -> depends.addProperty("fabricloader", loaderVersion));
        Optional.ofNullable(FABRIC_API_VERSIONS.get(upperVersion))
                .ifPresent(apiVersion -> depends.addProperty("fabric-api", apiVersion));

        // Save the updated JSON back to the file
        saveJsonFile(modJsonFile, jsonObject);
    }

    private String extractUpperVersion(String versionString) {
        // Use regex to find the upper version
        Pattern pattern = Pattern.compile("(?:>=|<=|>|<|=)?(\\d+\\.\\d+(?:\\.\\d+)?)");
        Matcher matcher = pattern.matcher(versionString);
        String upperVersion = "not set";

        while (matcher.find()) {
            upperVersion = matcher.group(1); // Get the first match
        }

        return upperVersion;
    }

    private JsonObject readJsonFile(File file) throws IOException {
        try (FileReader reader = new FileReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private JsonObject getOrCreateDependsObject(JsonObject jsonObject) {
        if (!jsonObject.has("depends")) {
            jsonObject.add("depends", new JsonObject());
            LOGGER.info("Created new 'depends' section");
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
