package core;

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    public Map<String, Object> getVersionComparisonLogs() {
        Map<String, Object> response = new HashMap<>();
        try {
            // Get the latest log file
            File logFile = getLatestFile(LOG_DIR, "minecraft-mod-updater");
            if (logFile != null) {
                List<String> logs = Files.readAllLines(logFile.toPath());
                response.put("logs", logs);
            }

            // Get the latest diff report
            File diffReport = getLatestFile(DIFF_DIR, "diff_report");
            if (diffReport != null) {
                List<String> diffContent = Files.readAllLines(diffReport.toPath());
                response.put("diffReport", diffContent);
            }

            response.put("success", true);
        } catch (Exception e) {
            LOGGER.error("Error reading logs: {}", e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return response;
    }

    private File getLatestFile(String directory, String prefix) {
        File dir = new File(directory);
        if (!dir.exists()) return null;

        File[] files = dir.listFiles((d, name) -> name.startsWith(prefix));
        if (files == null || files.length == 0) return null;

        return Arrays.stream(files)
                .max(Comparator.comparingLong(File::lastModified))
                .orElse(null);
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
