package core;

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;

@RestController
@RequestMapping("/api/logs")
public class LogController {
    private static final Logger LOGGER = LoggerFactory.getLogger(LogController.class);
    private static final String LOG_DIR = "logs";
    private static final String DIFF_DIR = "diff_results";
    private static final String DIFF_OUTPUT_DIR = "mod_file";

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

    @GetMapping("/diff-reports")
    public ResponseEntity<List<Map<String, String>>> getDiffReports() {
        try {
            List<Map<String, String>> reports = new ArrayList<>();
            Path diffDirPath = Paths.get(DIFF_DIR);
            
            if (Files.exists(diffDirPath)) {
                Files.list(diffDirPath)
                    .filter(path -> path.toString().endsWith(".txt"))
                    .forEach(path -> {
                        Map<String, String> report = new HashMap<>();
                        report.put("filename", path.getFileName().toString());
                        report.put("path", path.toString());
                        report.put("created", String.valueOf(path.toFile().lastModified()));
                        reports.add(report);
                    });
            }
            
            return ResponseEntity.ok(reports);
        } catch (Exception e) {
            LOGGER.error("Error listing diff reports: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/diff-report/{filename}")
    public ResponseEntity<String> getDiffReport(@PathVariable String filename) {
        try {
            Path reportPath = Paths.get(DIFF_DIR, filename);
            if (!Files.exists(reportPath)) {
                return ResponseEntity.notFound().build();
            }

            String content = Files.readString(reportPath);
            return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(content);
        } catch (Exception e) {
            LOGGER.error("Error reading diff report {}: {}", filename, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/latest-diff")
    public ResponseEntity<Map<String, Object>> getLatestDiffReport() {
        try {
            Path diffDirPath = Paths.get(DIFF_DIR);
            if (!Files.exists(diffDirPath)) {
                return ResponseEntity.notFound().build();
            }

            Optional<Path> latestReport = Files.list(diffDirPath)
                .filter(path -> path.toString().endsWith(".txt"))
                .max(Comparator.comparingLong(path -> path.toFile().lastModified()));

            if (latestReport.isPresent()) {
                String content = Files.readString(latestReport.get());
                Map<String, Object> response = new HashMap<>();
                response.put("content", content);
                response.put("filename", latestReport.get().getFileName().toString());
                response.put("timestamp", latestReport.get().toFile().lastModified());
                
                return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            LOGGER.error("Error reading latest diff report: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
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

    @GetMapping("/mod-file-diff")
    public ResponseEntity<Resource> downloadModFileDiff() {
        try {
            Path diffDirPath = Paths.get(DIFF_OUTPUT_DIR);
            if (!Files.exists(diffDirPath)) {
                LOGGER.error("Diff directory not found: {}", DIFF_OUTPUT_DIR);
                return ResponseEntity.notFound().build();
            }

            // Find the latest mod file diff report
            Optional<Path> latestReport = Files.list(diffDirPath)
                .filter(path -> path.getFileName().toString().startsWith("diff_report_mod"))
                .max(Comparator.comparingLong(path -> path.toFile().lastModified()));

            if (latestReport.isEmpty()) {
                LOGGER.error("No mod file diff report found");
                return ResponseEntity.notFound().build();
            }

            Path reportPath = latestReport.get();
            Resource resource = new FileSystemResource(reportPath.toFile());

            LOGGER.info("Serving mod file diff report: {}", reportPath);

            return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, 
                    "attachment; filename=\"" + reportPath.getFileName().toString() + "\"")
                .body(resource);
        } catch (Exception e) {
            LOGGER.error("Error downloading mod file diff report: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
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
}
