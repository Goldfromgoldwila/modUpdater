package core;

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import java.nio.file.*;
import java.util.*;
import java.io.*;

@RestController
@RequestMapping("/api/logs")
public class LogController {
    private static final Logger LOGGER = LoggerFactory.getLogger(LogController.class);
    private static final String LOG_DIR = "logs";
    private static final String DIFF_DIR = "diff_results";

    @GetMapping("/version-comparison")
    public ResponseEntity<Map<String, Object>> getVersionComparisonLogs() {
        Map<String, Object> response = new HashMap<>();
        try {
            // Get the latest log file
            File logFile = getLatestFile(LOG_DIR, "minecraft-mod-updater");
            if (logFile != null) {
                List<String> logs = Files.readAllLines(logFile.toPath());
                response.put("logs", logs);
            } else {
                response.put("logs", new ArrayList<>());
            }

            // Get the latest diff report
            File diffReport = getLatestFile(DIFF_DIR, "diff_report");
            if (diffReport != null) {
                String diffContent = Files.readString(diffReport.toPath());
                response.put("diffReport", diffContent);
            } else {
                response.put("diffReport", "");
            }

            response.put("success", true);
            return ResponseEntity.ok(response);

        } catch (IOException e) {
            LOGGER.error("Error reading logs: {}", e.getMessage());
            response.put("success", false);
            response.put("error", "Failed to read logs: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    private File getLatestFile(String directory, String prefix) {
        try {
            File dir = new File(directory);
            if (!dir.exists()) {
                dir.mkdirs();
                return null;
            }

            File[] files = dir.listFiles((d, name) -> name.startsWith(prefix));
            if (files == null || files.length == 0) {
                return null;
            }

            return Arrays.stream(files)
                    .max(Comparator.comparingLong(File::lastModified))
                    .orElse(null);
        } catch (Exception e) {
            LOGGER.error("Error getting latest file: {}", e.getMessage());
            return null;
        }
    }

    @GetMapping("/stream")
    public List<String> getLatestLogs(@RequestParam(defaultValue = "100") int lines) {
        try {
            File logFile = getLatestFile(LOG_DIR, "minecraft-mod-updater");
            if (logFile == null) {
                return Collections.emptyList();
            }

            List<String> allLines = Files.readAllLines(logFile.toPath());
            int startIndex = Math.max(0, allLines.size() - lines);
            return allLines.subList(startIndex, allLines.size());
        } catch (Exception e) {
            LOGGER.error("Error streaming logs: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
