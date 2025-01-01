package core;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.JSONObject;
import org.springframework.stereotype.Component;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.text.SimpleDateFormat;
import java.util.Date;

@Component
public class VersionComparator {
    private static final Logger LOGGER = LoggerFactory.getLogger(VersionComparator.class);
    private static final String VERSIONS_DIR = "versions";
    private static final String COMPARISON_REPORT_DIR = "version_comparison_reports";

    public void compareVersions(String sourceVersion, String targetVersion) {
        try {
            LOGGER.info("Starting comparison between versions {} and {}", sourceVersion, targetVersion);

            // Validate versions exist
            Path sourceDir = Paths.get(VERSIONS_DIR, sourceVersion);
            Path targetDir = Paths.get(VERSIONS_DIR, targetVersion);

            if (!Files.exists(sourceDir) || !Files.exists(targetDir)) {
                LOGGER.error("One or both version directories not found: {} {}", sourceDir, targetDir);
                return;
            }

            // Create comparison report
            JsonObject report = new JsonObject();
            report.addProperty("sourceVersion", sourceVersion);
            report.addProperty("targetVersion", targetVersion);
            report.addProperty("comparisonDate", new Date().toString());

            // Compare and add differences
            compareDirectories(sourceDir, targetDir, report);

            // Save report
            saveComparisonReport(report, sourceVersion, targetVersion);

        } catch (Exception e) {
            LOGGER.error("Error during version comparison: {}", e.getMessage(), e);
        }
    }

    private void compareDirectories(Path sourceDir, Path targetDir, JsonObject report) throws IOException {
        JsonObject changes = new JsonObject();
        
        // Track different types of changes
        JsonArray addedClasses = new JsonArray();
        JsonArray removedClasses = new JsonArray();
        JsonArray modifiedClasses = new JsonArray();

        // Get all files from both directories
        Map<String, Path> sourceFiles = findAllFiles(sourceDir);
        Map<String, Path> targetFiles = findAllFiles(targetDir);

        // Find added and removed classes
        findAddedAndRemovedClasses(sourceFiles, targetFiles, addedClasses, removedClasses);

        // Compare modified classes
        findModifiedClasses(sourceFiles, targetFiles, modifiedClasses);

        // Add all changes to report
        changes.add("addedClasses", addedClasses);
        changes.add("removedClasses", removedClasses);
        changes.add("modifiedClasses", modifiedClasses);
        report.add("changes", changes);
    }

    private Map<String, Path> findAllFiles(Path directory) throws IOException {
        Map<String, Path> allFiles = new HashMap<>();
        Files.walk(directory)
            .forEach(path -> {
                String relativePath = directory.relativize(path).toString();
                allFiles.put(relativePath, path);
            });
        return allFiles;
    }

    private void findAddedAndRemovedClasses(
            Map<String, Path> sourceFiles, 
            Map<String, Path> targetFiles,
            JsonArray addedClasses,
            JsonArray removedClasses) {
        
        // Find added classes
        targetFiles.keySet().stream()
            .filter(file -> !sourceFiles.containsKey(file))
            .forEach(file -> {
                JsonObject added = new JsonObject();
                added.addProperty("file", file);
                addedClasses.add(added);
            });

        // Find removed classes
        sourceFiles.keySet().stream()
            .filter(file -> !targetFiles.containsKey(file))
            .forEach(file -> {
                JsonObject removed = new JsonObject();
                removed.addProperty("file", file);
                removedClasses.add(removed);
            });
    }

    private void findModifiedClasses(
            Map<String, Path> sourceFiles,
            Map<String, Path> targetFiles,
            JsonArray modifiedClasses) {
        
        sourceFiles.forEach((file, sourcePath) -> {
            if (targetFiles.containsKey(file)) {
                try {
                    Path targetPath = targetFiles.get(file);
                    compareFiles(sourcePath, targetPath, file, modifiedClasses);
                } catch (IOException e) {
                    LOGGER.error("Error comparing files: {}", file, e);
                }
            }
        });
    }

    private void compareFiles(Path sourcePath, Path targetPath, String fileName, JsonArray modifiedClasses) 
            throws IOException {
        
        if (Files.isDirectory(sourcePath) && Files.isDirectory(targetPath)) {
            // If both are directories, we can skip as they are not modified
            return;
        }

        // Compare file contents
        if (!Files.isDirectory(sourcePath) && !Files.isDirectory(targetPath)) {
            if (!Files.readAllBytes(sourcePath).equals(Files.readAllBytes(targetPath))) {
                JsonObject classChanges = new JsonObject();
                classChanges.addProperty("file", fileName);
                classChanges.addProperty("type", "MODIFIED");
                modifiedClasses.add(classChanges);
            }
        }
    }

    private void saveComparisonReport(JsonObject report, String sourceVersion, String targetVersion) 
            throws IOException {
        File reportDir = new File(COMPARISON_REPORT_DIR);
        if (!reportDir.exists()) {
            reportDir.mkdirs();
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String fileName = String.format("mc_comparison_%s_to_%s_%s.json", 
            sourceVersion.replace('.', '_'), 
            targetVersion.replace('.', '_'), 
            timestamp);

        File reportFile = new File(reportDir, fileName);
        try (FileWriter writer = new FileWriter(reportFile)) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(report, writer);
            LOGGER.info("Saved comparison report to: {}", reportFile.getAbsolutePath());
        }
    }
}