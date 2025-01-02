package core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin(origins = {"https://goldfromgoldwila.github.io", "https://modupdater.onrender.com"})
@RestController
@RequestMapping("/api")
public class ConvertController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConvertController.class);

    @PostMapping("/convert")
    public ResponseEntity<?> convertVersion(@RequestBody Map<String, String> payload) {
        String mcVersion = payload.get("version");
        LOGGER.info("Received conversion request for version: {}", mcVersion);

        try {
            ExtractJson extractJson = new ExtractJson();
            extractJson.processMod(mcVersion);
            LOGGER.info("Conversion successful for version: {}", mcVersion);
            return ResponseEntity.ok(Map.of("message", "Conversion completed successfully!"));
        } catch (Exception e) {
            LOGGER.error("Error during conversion: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error."));
        }
    }
}