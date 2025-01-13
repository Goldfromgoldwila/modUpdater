package core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.Comparator;

@Component
@RestController
@RequestMapping("/api")
public class ExtractJson {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtractJson.class);
    private static final String DECOMPILED_DIR = "decompiled_mods";
    private static final String MOD_JSON_FILE = "fabric.mod.json";

    public void processMod(String targetVersion) {
        try {
            LOGGER.info("Processing mod for version: {}", targetVersion);
            File modDir = findLatestModDirectory();
            if (modDir == null) {
                throw new IllegalStateException("No decompiled directory found");
            }

            File modJsonFile = new File(modDir, MOD_JSON_FILE);
            if (!modJsonFile.exists()) {
                throw new IllegalStateException("Mod JSON file not found");
            }

            // Read and log original content
            JsonObject modJson = readJsonFile(modJsonFile);
            LOGGER.info("=== Original JSON Content ===\n{}", 
                new GsonBuilder().setPrettyPrinting().create().toJson(modJson));

            JsonObject depends = getOrCreateDependsObject(modJson);
            
            // Get and clean current version
            String currentVersion = depends.has("minecraft") ? 
                depends.get("minecraft").getAsString().replaceAll("[>=<]", "") : "";
            LOGGER.info("Current version (cleaned): {}", currentVersion);
            
            // Update version (without operators)
            depends.addProperty("minecraft", targetVersion);
            LOGGER.info("Updated to version: {}", targetVersion);
            
            // Log updated content
            LOGGER.info("=== Updated JSON Content ===\n{}", 
                new GsonBuilder().setPrettyPrinting().create().toJson(modJson));
            
            saveJsonFile(modJsonFile, modJson);
            LOGGER.info("Successfully saved changes to {}", modJsonFile.getName());

            // Call version handler with clean versions
            MinecraftVersionHandler versionHandler = new MinecraftVersionHandler();
            versionHandler.setCleanVersion(currentVersion);
            versionHandler.setMcVersion(targetVersion);
            versionHandler.compareVersions(currentVersion, targetVersion);

        } catch (Exception e) {
            LOGGER.error("Mod processing failed: {}", e.getMessage());
            throw new RuntimeException("Mod processing failed", e);
        }
    }

    public void compareVersions(String cleanVersion, String targetVersion) {
        LOGGER.info("\n=== Starting Version Comparison ===");
        // Clean versions (remove any >= or <= operators)
        String sourceVersion = cleanVersion.replaceAll("[>=<]", "").trim();
        String newVersion = targetVersion.replaceAll("[>=<]", "").trim();
        
        LOGGER.info("Comparing versions {} and {}", sourceVersion, newVersion);
        
        try {
            Path oldVersionPath = Paths.get(DECOMPILED_DIR, sourceVersion);
            Path newVersionPath = Paths.get(DECOMPILED_DIR, newVersion);
            
            if (!Files.exists(oldVersionPath) || !Files.exists(newVersionPath)) {
                LOGGER.error("One or both version directories not found");
                return;
            }

        } catch (Exception e) {
            LOGGER.error("Error comparing versions: {}", e.getMessage());
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
            LOGGER.error("Error finding mod directory: {}", e.getMessage());
            return null;
        }
    }

    private JsonObject readJsonFile(File file) throws IOException {
        String content = Files.readString(file.toPath());
        LOGGER.debug("Reading file content:\n{}", content);
        return JsonParser.parseString(content).getAsJsonObject();
    }

    private void saveJsonFile(File file, JsonObject jsonObject) throws IOException {
        String content = new GsonBuilder().setPrettyPrinting().create().toJson(jsonObject);
        LOGGER.debug("Writing file content:\n{}", content);
        Files.writeString(file.toPath(), content);
    }

    private JsonObject getOrCreateDependsObject(JsonObject jsonObject) {
        if (!jsonObject.has("depends")) {
            jsonObject.add("depends", new JsonObject());
        }
        return jsonObject.getAsJsonObject("depends");
    }

    @PostMapping("/convert-version")
    public ResponseEntity<?> convertVersion(@RequestParam String targetVersion) {
        try {
            LOGGER.info("Received version conversion request: {}", targetVersion);
            processMod(targetVersion);
            return ResponseEntity.ok().body(Map.of(
                "success", true,
                "message", "Version updated to " + targetVersion
            ));
        } catch (Exception e) {
            LOGGER.error("Version conversion failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
}
