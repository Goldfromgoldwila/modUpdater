package core.Comparer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class MinecraftVersionHandler implements VersionHandlerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftVersionHandler.class);
    private static final String DECOMPILED_DIR = "versions";
    private static final String DIFF_OUTPUT_DIR = "diff_results";
    private static final String MELD_PATH = "meld";

    private String cleanVersion;
    private String mcVersion;

    @PostConstruct
    public void init() {
        createRequiredDirectories();
        checkMeldAvailability();
    }

    private void createRequiredDirectories() {
        try {
            Files.createDirectories(Paths.get(DECOMPILED_DIR));
            Files.createDirectories(Paths.get(DIFF_OUTPUT_DIR));
            LOGGER.info("Required directories created successfully");
        } catch (IOException e) {
            LOGGER.error("Failed to create directories: {}", e.getMessage());
        }
    }

    private boolean checkMeldAvailability() {
        try {
            Process process = new ProcessBuilder("which", MELD_PATH).start();
            boolean available = process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
            if (available) {
                LOGGER.info("Meld is available for comparison");
            } else {
                LOGGER.warn("Meld is not available. Please install Meld for visual diff comparison");
            }
            return available;
        } catch (Exception e) {
            LOGGER.error("Error checking Meld availability: {}", e.getMessage());
            return false;
        }
    }

    public void compareVersions(String oldVersion, String newVersion) {
        LOGGER.info("\n=== Starting Version Comparison ===");
        LOGGER.info("Comparing versions {} and {}", oldVersion, newVersion);
        
        try {
            Path oldVersionPath = Paths.get(DECOMPILED_DIR, oldVersion);
            Path newVersionPath = Paths.get(DECOMPILED_DIR, newVersion);
            
            if (!Files.exists(oldVersionPath) || !Files.exists(newVersionPath)) {
                LOGGER.error("One or both version directories not found");
                return;
            }

            // Create lists to store file changes
            List<String> addedFiles = new ArrayList<>();
            List<String> modifiedFiles = new ArrayList<>();
            List<String> deletedFiles = new ArrayList<>();

            // Get statistics and populate file lists
            Map<String, Integer> stats = collectChangeStatistics(oldVersionPath, newVersionPath, 
                addedFiles, modifiedFiles, deletedFiles);

            logChangeStatistics(stats);
            generateMeldDiff(oldVersionPath, newVersionPath);
            generateTextReport(oldVersionPath, newVersionPath, stats, 
                addedFiles, modifiedFiles, deletedFiles);

            LOGGER.info("Version comparison completed successfully");
        } catch (Exception e) {
            LOGGER.error("Error comparing versions: {}", e.getMessage());
        }
    }

    private Map<String, Integer> collectChangeStatistics(Path oldPath, Path newPath,
            List<String> addedFiles, List<String> modifiedFiles, List<String> deletedFiles) throws IOException {
        Map<String, Integer> stats = new HashMap<>();
        
        stats.put("added", 0);
        stats.put("modified", 0);
        stats.put("deleted", 0);

        // Count added and modified files
        try (Stream<Path> newFiles = Files.walk(newPath)) {
            newFiles.filter(Files::isRegularFile).forEach(newFile -> {
                Path relativePath = newPath.relativize(newFile);
                Path oldFile = oldPath.resolve(relativePath);
                String fullPath = newFile.toAbsolutePath().toString();
                
                if (!Files.exists(oldFile)) {
                    stats.put("added", stats.get("added") + 1);
                    addedFiles.add(relativePath.toString() + "|" + fullPath);
                } else if (filesAreDifferent(oldFile, newFile)) {
                    stats.put("modified", stats.get("modified") + 1);
                    modifiedFiles.add(relativePath.toString() + "|" + fullPath);
                }
            });
        }

        // Count deleted files
        try (Stream<Path> oldFiles = Files.walk(oldPath)) {
            oldFiles.filter(Files::isRegularFile).forEach(oldFile -> {
                Path relativePath = oldPath.relativize(oldFile);
                Path newFile = newPath.resolve(relativePath);
                String fullPath = oldFile.toAbsolutePath().toString();
                
                if (!Files.exists(newFile)) {
                    stats.put("deleted", stats.get("deleted") + 1);
                    deletedFiles.add(relativePath.toString() + "|" + fullPath);
                }
            });
        }

        return stats;
    }

    private boolean filesAreDifferent(Path file1, Path file2) {
        try {
            return Files.mismatch(file1, file2) != -1;
        } catch (IOException e) {
            LOGGER.error("Error comparing files: {}", e.getMessage());
            return true;
        }
    }

    private void generateMeldDiff(Path oldPath, Path newPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                MELD_PATH,
                oldPath.toString(),
                newPath.toString()
            );
            
            Process process = pb.start();
            LOGGER.info("Launched Meld comparison");
            
            // Handle Meld output
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOGGER.debug("Meld: {}", line);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to launch Meld: {}", e.getMessage());
        }
    }

    private void generateTextReport(Path oldPath, Path newPath, Map<String, Integer> stats, 
            List<String> addedFiles, List<String> modifiedFiles, List<String> deletedFiles) {
        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            Path reportPath = Paths.get(DIFF_OUTPUT_DIR, 
                String.format("diff_report_%s_to_%s_%s.txt", 
                    oldPath.getFileName(),
                    newPath.getFileName(),
                    timestamp
                ));

            try (BufferedWriter writer = Files.newBufferedWriter(reportPath)) {
                // Write header and statistics
                writer.write(String.format("Comparison Report: %s -> %s\n", 
                    oldPath.getFileName(), newPath.getFileName()));
                writer.write("Generated at: " + new Date() + "\n\n");
                
                // Write statistics summary
                writer.write("=== Statistics Summary ===\n");
                writer.write(String.format("Added files: %d\n", stats.get("added")));
                writer.write(String.format("Modified files: %d\n", stats.get("modified")));
                writer.write(String.format("Deleted files: %d\n", stats.get("deleted")));
                writer.write(String.format("Total changes: %d\n\n", 
                    stats.get("added") + stats.get("modified") + stats.get("deleted")));

                // Write added files with content
                writer.write("=== Added Files Content ===\n");
                for (String file : addedFiles) {
                    String[] parts = file.split("\\|");
                    String relativePath = parts[0];
                    writer.write("\nFile: " + relativePath + "\n");
                    writer.write("----------------------------------------\n");
                    
                    Path newFilePath = newPath.resolve(relativePath);
                    writer.write("Content:\n");
                    writer.write(Files.readString(newFilePath) + "\n");
                    writer.write("========================================\n\n");
                }

                // Write modified files with content
                writer.write("=== Modified Files Content ===\n");
                for (String file : modifiedFiles) {
                    String[] parts = file.split("\\|");
                    String relativePath = parts[0];
                    writer.write("\nFile: " + relativePath + "\n");
                    writer.write("----------------------------------------\n");
                    
                    Path oldFilePath = oldPath.resolve(relativePath);
                    writer.write("Old Version (" + oldPath.getFileName() + "):\n");
                    writer.write(Files.readString(oldFilePath) + "\n");
                    writer.write("----------------------------------------\n");
                    
                    Path newFilePath = newPath.resolve(relativePath);
                    writer.write("New Version (" + newPath.getFileName() + "):\n");
                    writer.write(Files.readString(newFilePath) + "\n");
                    writer.write("========================================\n\n");
                }

                // Write deleted files with content
                writer.write("=== Deleted Files Content ===\n");
                for (String file : deletedFiles) {
                    String[] parts = file.split("\\|");
                    String relativePath = parts[0];
                    writer.write("\nFile: " + relativePath + "\n");
                    writer.write("----------------------------------------\n");
                    
                    Path oldFilePath = oldPath.resolve(relativePath);
                    writer.write("Content:\n");
                    writer.write(Files.readString(oldFilePath) + "\n");
                    writer.write("========================================\n\n");
                }
            }
            
            LOGGER.info("Generated detailed diff report with file contents: {}", reportPath);
        } catch (IOException e) {
            LOGGER.error("Failed to generate text report: {}", e.getMessage());
        }
    }

    private void logChangeStatistics(Map<String, Integer> stats) {
        int totalChanges = stats.get("added") + stats.get("modified") + stats.get("deleted");
        
        // Log summary first
        LOGGER.info("\n=== Change Statistics Summary ===");
        LOGGER.info("Total files changed: {}", totalChanges);
        LOGGER.info("  - Added:    {}", stats.get("added"));
        LOGGER.info("  - Modified: {}", stats.get("modified"));
        LOGGER.info("  - Deleted:  {}", stats.get("deleted"));
        
        // Then log file details
        LOGGER.info("\n=== Detailed File Changes ===");
    }

    @Override
    public void processMod(String targetVersion) {
        try {
            // cleanVersion should already be set from ExtractJson
            if (this.cleanVersion == null) {
                LOGGER.error("Clean version not set before processing");
                return;
            }
            
            // Update mcVersion with the target version from frontend
            this.mcVersion = targetVersion;
            LOGGER.info("Processing mod upgrade from {} to {}", this.cleanVersion, targetVersion);
            
            createRequiredDirectories();
            compareVersions(this.cleanVersion, targetVersion);
            
            LOGGER.info("Mod processing completed successfully");
        } catch (Exception e) {
            LOGGER.error("Error processing mod: {}", e.getMessage());
            throw new RuntimeException("Failed to process mod", e);
        }
    }
 
    // Getters and setters remain the same
    public String getCleanVersion() {
        return cleanVersion;
    }

    public void setCleanVersion(String version) {
        this.cleanVersion = version;
        LOGGER.info("Clean version set to: {}", version);
    }

    public String getMcVersion() {
        return mcVersion;
    }

    public void setMcVersion(String version) {
        this.mcVersion = version;
        LOGGER.info("Minecraft version set to: {}", version);
    }
}