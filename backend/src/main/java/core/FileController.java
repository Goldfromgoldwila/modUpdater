package core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class FileController {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileController.class);

    @GetMapping("/file")
    public ResponseEntity<String> readFile(@RequestParam String filePath) {
        try {
            File file = new File(filePath);
            if (file.exists()) {
                LOGGER.info("File found: {}", file.getPath());
                String fileContent = readFileContent(file);
                return ResponseEntity.ok(fileContent);
            } else {
                LOGGER.error("File not found: {}", file.getPath);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("File not found");
            }
        } catch (Exception e) {
            LOGGER.error("Error reading file: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error reading file");
        }
    }

    private String readFileContent(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            return content.toString();
        } catch (IOException e) {
            LOGGER.error("Error reading file: {}", e.getMessage());
            return "Error reading file";
        }
    }
}