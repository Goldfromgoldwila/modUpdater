package core.Api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import core.Decompiler.ModDecompilerService;
import core.Extracter.ExtractJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"https://goldfromgoldwila.github.io", "https://modupdater.onrender.com"},
             allowedHeaders = "*",
             methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, 
                       RequestMethod.DELETE, RequestMethod.OPTIONS},
             allowCredentials = "true")
public class UploadController {
    
    private static final Logger logger = LoggerFactory.getLogger(UploadController.class);
    
    @Autowired
    private ModDecompilerService modDecompilerService;
    
    @Autowired
    private ExtractJson extractJson;

    private String originalVersion;
    private String targetVersion;
    private String fileName;

    @PostMapping("/upload")
    public ResponseEntity<?> handleFileUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("targetVersion") String targetVersion) {
        try {
            this.fileName = file.getOriginalFilename();
            this.targetVersion = targetVersion;
            
            logger.info("=== Starting Mod Processing ===");
            logger.info("Received file: {}", this.fileName);
            logger.info("Target version requested: {}", this.targetVersion);
            
            // Handle file upload
            modDecompilerService.handleFileUpload(file);
            
            // Decompile and process
            modDecompilerService.decompileLatestMod();
            
            // Get original version from mod.json
            this.originalVersion = extractJson.getOriginalVersion();
            logger.info("Original mod version: {}", this.originalVersion);
            
            // Process version update
            extractJson.processMod(targetVersion);
            
            logger.info("=== Mod Processing Summary ===");
            logger.info("File: {}", this.fileName);
            logger.info("Original Version: {}", this.originalVersion);
            logger.info("Target Version: {}", this.targetVersion);
            
            return ResponseEntity.ok(Map.of(
                "message", "File uploaded and processed successfully",
                "filename", this.fileName,
                "originalVersion", this.originalVersion,
                "targetVersion", this.targetVersion
            ));
        } catch (Exception e) {
            logger.error("Error processing upload: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                        "error", "Failed to process upload: " + e.getMessage(),
                        "filename", file.getOriginalFilename()
                    ));
        }
    }

    // Getters for stored values
    public String getOriginalVersion() {
        return originalVersion;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public String getFileName() {
        return fileName;
    }
}

