package core;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/logs")
public class LogController {
    private static final Logger LOGGER = LoggerFactory.getLogger(LogController.class);
    private static final String LOG_DIR = "logs";
    private static final String APP_LOG = "application.log";

    @GetMapping
    public ResponseEntity<Map<String, List<String>>> getLogs() {
        Map<String, List<String>> allLogs = new HashMap<>();

        try {
            // Ensure the logs directory exists
            Path logDirPath = Paths.get(LOG_DIR);
            if (!Files.exists(logDirPath)) {
                Files.createDirectories(logDirPath);
                LOGGER.warn("Log directory '{}' did not exist and was created.", LOG_DIR);
            }

            // Read application logs
            Path appLogPath = logDirPath.resolve(APP_LOG);
            if (Files.exists(appLogPath)) {
                List<String> logEntries = Files.readAllLines(appLogPath);
                allLogs.put("application", logEntries);
                LOGGER.info("Successfully retrieved {} log entries from '{}'.", logEntries.size(), appLogPath);
            } else {
                LOGGER.warn("Log file '{}' not found.", appLogPath);
                allLogs.put("application", List.of("No logs found."));
            }

        } catch (IOException e) {
            LOGGER.error("Error reading logs: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", List.of("Failed to retrieve logs: " + e.getMessage())));
        }

        return ResponseEntity.ok(allLogs);
    }
}
