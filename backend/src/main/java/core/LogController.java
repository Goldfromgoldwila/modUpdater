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

    @GetMapping("/download-diff")
    public ResponseEntity<Resource> downloadDiff() {
        try {
            Path diffDirPath = Paths.get(DIFF_DIR);
            if (!Files.exists(diffDirPath)) {
                LOGGER.error("Diff directory not found: {}", DIFF_DIR);
                return ResponseEntity.notFound().build();
            }

            Optional<Path> latestReport = Files.list(diffDirPath)
                .filter(path -> path.getFileName().toString().startsWith("diff_report_"))
                .max(Comparator.comparingLong(path -> path.toFile().lastModified()));

            if (latestReport.isEmpty()) {
                LOGGER.error("No diff report found");
                return ResponseEntity.notFound().build();
            }

            Path reportPath = latestReport.get();
            Resource resource = new FileSystemResource(reportPath.toFile());

            LOGGER.info("Serving diff report: {}", reportPath);

            return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, 
                    "attachment; filename=\"" + reportPath.getFileName().toString() + "\"")
                .body(resource);
        } catch (Exception e) {
            LOGGER.error("Error downloading diff report: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/mod-file-diff")
    public ResponseEntity<Resource> downloadModFileDiff() {
        try {
            Path diffDirPath = Paths.get(DIFF_DIR);
            if (!Files.exists(diffDirPath)) {
                LOGGER.error("Diff directory not found: {}", DIFF_DIR);
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
