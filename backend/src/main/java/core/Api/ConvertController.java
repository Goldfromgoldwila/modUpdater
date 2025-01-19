package core.Api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import core.Comparer.MinecraftVersionHandler;
import core.Extracter.ExtractJson;

import java.util.Map;

@CrossOrigin(origins = {"https://goldfromgoldwila.github.io", "https://modupdater.onrender.com"})
@RestController
@RequestMapping("/api")
public class ConvertController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConvertController.class);
    private final MinecraftVersionHandler minecraftVersionHandler;
    private final ExtractJson extractJson;

    @Autowired
    public ConvertController(MinecraftVersionHandler minecraftVersionHandler, ExtractJson extractJson) {
        this.minecraftVersionHandler = minecraftVersionHandler;
        this.extractJson = extractJson;
    }

    @PostMapping("/convert")
    public ResponseEntity<?> convertVersion(@RequestBody Map<String, String> payload) {
        String targetVersion = payload.get("version");
        LOGGER.info("Received conversion request for version: {}", targetVersion);

        try {
            // First process the mod.json to get original version
            extractJson.processMod(targetVersion);  // Pass the target version
            
            // Then process the conversion
            minecraftVersionHandler.processMod(targetVersion);
            LOGGER.info("Conversion successful for version: {}", targetVersion);
            return ResponseEntity.ok(Map.of("message", "Conversion completed successfully!"));
        } catch (Exception e) {
            LOGGER.error("Error during conversion: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error."));
        }
    }
}