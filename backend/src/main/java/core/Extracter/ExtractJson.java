package core.Extracter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import core.Comparer.VersionHandlerService;
import java.util.zip.*;

@Component
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"https://goldfromgoldwila.github.io", "https://modupdater.onrender.com"})
public class ExtractJson {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtractJson.class);
    private static final String DECOMPILED_DIR = "decompiled_mods";
    private static final String MOD_JSON_FILE = "fabric.mod.json";

    @Autowired
    private VersionHandlerService versionHandler;
    
    @Autowired
    private VersionParser versionParser;

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
                depends.get("minecraft").getAsString() : "";
            
            // Clean the version - will take the more detailed version if multiple exist
            String cleanVersion = versionParser.cleanVersion(currentVersion);
            LOGGER.info("Original version: '{}' -> Clean version: '{}'", 
                currentVersion, cleanVersion);
            
            // Update version (without operators)
            depends.addProperty("minecraft", targetVersion);
            LOGGER.info("Updated to version: {}", targetVersion);
            
            // Log updated content
            LOGGER.info("=== Updated JSON Content ===\n{}", 
                new GsonBuilder().setPrettyPrinting().create().toJson(modJson));
            
            saveJsonFile(modJsonFile, modJson);
            LOGGER.info("Successfully saved changes to {}", modJsonFile.getName());

            // Call version handler with clean versions
            versionHandler.setCleanVersion(cleanVersion);
            versionHandler.setMcVersion(targetVersion);
            versionHandler.compareVersions(cleanVersion, targetVersion);
            LOGGER.info("Calling version handler with clean version: {} and mc version: {}", cleanVersion, targetVersion);


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

    public void processModJson(Path modPath) {
        LOGGER.info("Processing mod.json from {}", modPath);
        try (ZipFile zipFile = new ZipFile(modPath.toFile())) {
            ZipEntry jsonEntry = zipFile.getEntry(MOD_JSON_FILE);
            if (jsonEntry == null) {
                LOGGER.error("No fabric.mod.json found in mod file");
                return;
            }

            try (InputStream inputStream = zipFile.getInputStream(jsonEntry);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                
                // Read and parse JSON content
                JsonObject modJson = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject depends = modJson.has("depends") ? 
                    modJson.getAsJsonObject("depends") : 
                    new JsonObject();
                
                // Get minecraft version from depends
                String currentVersion;
                if (depends.has("minecraft")) {
                    currentVersion = depends.get("minecraft").getAsString();
                    LOGGER.info("Found version in depends: {}", currentVersion);
                } else if (modJson.has("minecraft")) {
                    currentVersion = modJson.get("minecraft").getAsString();
                    LOGGER.info("Found version in root: {}", currentVersion);
                } else {
                    LOGGER.error("No Minecraft version found in mod.json");
                    return;
                }
                
                String cleanVersion = currentVersion.replaceAll("[>=<]", "").trim();
                LOGGER.info("Original version: {} -> Clean version: {}", currentVersion, cleanVersion);
                
                // Set versions in version handler
                versionHandler.setCleanVersion(cleanVersion);
                // Don't set mcVersion here - it will be set by MinecraftVersionHandler
                
                LOGGER.info("Successfully processed mod.json");
            }
        } catch (IOException e) {
            LOGGER.error("Error processing mod.json: {}", e.getMessage());
            throw new RuntimeException("Failed to process mod.json", e);
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

 
}
