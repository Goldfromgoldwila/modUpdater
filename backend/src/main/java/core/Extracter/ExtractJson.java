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
import core.Config.DirectoryConfig;

@Component
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"https://goldfromgoldwila.github.io", "https://modupdater.onrender.com"})
public class ExtractJson {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtractJson.class);
    private static final String MOD_JSON_FILE = "fabric.mod.json";

    @Autowired
    private VersionHandlerService versionHandler;
    
    @Autowired
    private VersionParser versionParser;

    private String targetVersion;
    private String originalVersion;

    public void processMod(String targetVersion) {
        this.targetVersion = targetVersion;
        LOGGER.info("Target version set to: {}", targetVersion);
        
        try {
            File modDir = findLatestModDirectory();
            if (modDir == null) {
                throw new IllegalStateException("No decompiled directory found");
            }

            File modJsonFile = new File(modDir, MOD_JSON_FILE);
            if (!modJsonFile.exists()) {
                throw new IllegalStateException("Mod JSON file not found");
            }

            processModJson(modJsonFile);
            
        } catch (Exception e) {
            LOGGER.error("Mod processing failed: {}", e.getMessage());
            throw new RuntimeException("Mod processing failed", e);
        }
    }

    private File findLatestModDirectory() {
        try {
            Path decompiledDirPath = Paths.get(DirectoryConfig.DECOMPILED_DIR);
            if (!Files.exists(decompiledDirPath)) {
                Files.createDirectories(decompiledDirPath);
            }
            
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

    public String getOriginalVersion() {
        return this.originalVersion;
    }

    private void processModJson(File modJsonFile) throws IOException {
        JsonObject modJson = readJsonFile(modJsonFile);
        JsonObject depends = getOrCreateDependsObject(modJson);
        
        String currentVersion = depends.has("minecraft") ? 
            depends.get("minecraft").getAsString() : "";
        
        this.originalVersion = currentVersion;
        String cleanVersion = currentVersion.replaceAll("[>=<]", "").trim();
        
        LOGGER.info("Original version: '{}' -> Clean version: '{}'", 
            currentVersion, cleanVersion);
        
        versionHandler.setCleanVersion(cleanVersion);
        versionHandler.setMcVersion(this.targetVersion);
        
        depends.addProperty("minecraft", this.targetVersion);
        saveJsonFile(modJsonFile, modJson);
        
        LOGGER.info("Successfully processed mod.json with target version: {}", this.targetVersion);
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
