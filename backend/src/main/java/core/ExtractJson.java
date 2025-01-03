package core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public class ExtractJson {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtractJson.class);
    
    private static final String DECOMPILED_DIR = "decompiled_mods";
    private static final String MOD_JSON_FILE = "fabric.mod.json";
    private static final Pattern VERSION_PATTERN = Pattern.compile("_(\\d+)\\.");

    // Version compatibility mappings with explicit types
    private static final Map<String, String> FABRIC_LOADER_VERSIONS = new HashMap<String, String>() {
        private static final long serialVersionUID = 1L;
        {
            put("1.20", ">=0.14.21");
            put("1.20.1", ">=0.14.21");
            put("1.20.2", ">=0.14.21");
            put("1.20.3", ">=0.15.0");
            put("1.20.4", ">=0.15.0");
            put("1.21", ">=0.15.0");
            put("1.21.1", ">=0.15.0");
            put("1.21.2", ">=0.15.0");
            put("1.21.3", ">=0.15.0");
            put("1.21.4", ">=0.15.0");
        }
    };

    private static final Map<String, String> FABRIC_API_VERSIONS = new HashMap<String, String>() {
        private static final long serialVersionUID = 1L;
        {
            put("1.20", ">=0.83.0");
            put("1.20.1", ">=0.83.0");
            put("1.20.2", ">=0.89.0");
            put("1.20.3", ">=0.91.0");
            put("1.20.4", ">=0.91.0");
            put("1.21", ">=0.92.0");
            put("1.21.1", ">=0.92.0");
            put("1.21.2", ">=0.92.0");
            put("1.21.3", ">=0.92.0");
            put("1.21.4", ">=0.92.0");
        }
    };

    public static void processMod(String mcVersion) throws IOException {
        LOGGER.info("Processing mods for Minecraft version: {}", mcVersion);

        Optional<File> latestModDir = getLatestModDir();
        if (latestModDir.isEmpty()) {
            LOGGER.error("No mod directories found in {}", DECOMPILED_DIR);
            throw new FileNotFoundException("No mod directories found in " + DECOMPILED_DIR);
        }

        File modDir = latestModDir.get();
        LOGGER.info("Found latest mod directory: {} (Last modified: {})",
                    modDir.getName(),
                    new java.util.Date(modDir.lastModified()));

        File modJsonFile = new File(modDir, MOD_JSON_FILE);
        validateModJsonFile(modJsonFile);
        
        // Modify the JSON contents
        modifyJsonFile(modJsonFile, mcVersion);
        
        LOGGER.info("[{}] Successfully completed mod processing for version {}",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    mcVersion);
    }

    private static Optional<File> getLatestModDir() throws IOException {
        File decompiledDir = new File(DECOMPILED_DIR);
        
        if (!decompiledDir.exists()) {
            LOGGER.error("Directory {} does not exist", DECOMPILED_DIR);
            throw new IOException("Decompiled directory not found");
        }
        
        if (!decompiledDir.isDirectory()) {
            LOGGER.error("{} is not a directory", DECOMPILED_DIR);
            throw new IOException("Invalid decompiled directory path");
        }

        return Files.list(decompiledDir.toPath())
                .filter(Files::isDirectory)
                .map(Path::toFile)
                .max(Comparator.comparingLong(File::lastModified));
    }

    private static void validateModJsonFile(File modJsonFile) throws FileNotFoundException {
        if (!modJsonFile.exists()) {
            LOGGER.error("File {} not found in {}", MOD_JSON_FILE, modJsonFile.getParent());
            throw new FileNotFoundException("File " + MOD_JSON_FILE + " not found in " + modJsonFile.getParent());
        }
        LOGGER.info("Found {} in {}", MOD_JSON_FILE, modJsonFile.getParent());
    }

    private static void modifyJsonFile(File modJsonFile, String mcVersion) throws IOException {
        JsonObject jsonObject;
        try (FileReader reader = new FileReader(modJsonFile)) {
            jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
        }

        // Update the Minecraft version
        jsonObject.addProperty("minecraft", mcVersion);
        jsonObject.addProperty("minecraft_version", mcVersion);

        // Update the depends object
        if (jsonObject.has("depends")) {
            JsonObject depends = jsonObject.getAsJsonObject("depends");
            
            // Update Minecraft version requirement
            depends.addProperty("minecraft", ">=" + mcVersion);
            
            // Update Fabric Loader version if available
            String requiredLoaderVersion = FABRIC_LOADER_VERSIONS.get(mcVersion);
            if (requiredLoaderVersion != null) {
                depends.addProperty("fabricloader", requiredLoaderVersion);
            }

            // Update Fabric API version if it exists in depends
            if (depends.has("fabric-api") || depends.has("fabric")) {
                String requiredApiVersion = FABRIC_API_VERSIONS.get(mcVersion);
                if (requiredApiVersion != null) {
                    // Check both possible property names
                    if (depends.has("fabric-api")) {
                        depends.addProperty("fabric-api", requiredApiVersion);
                    }
                    if (depends.has("fabric")) {
                        depends.addProperty("fabric", requiredApiVersion);
                    }
                }
            }
        }

        // Write the updated JSON back to the file
        try (FileWriter writer = new FileWriter(modJsonFile)) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(jsonObject, writer);
        }

        LOGGER.info("Updated {} with Minecraft version {} and compatible dependencies", MOD_JSON_FILE, mcVersion);
    }
}