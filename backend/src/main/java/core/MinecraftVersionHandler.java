package core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import core.model.FileChange;
import core.model.FileInfo;
import core.service.FileChangeService;

@Component
public class MinecraftVersionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftVersionHandler.class);
    private static final String DECOMPILED_DIR = "versions";
    private static final String DIFF_OUTPUT_DIR = "diff_results";
    private static final String MELD_PATH = "meld";

    private String cleanVersion;
    private String mcVersion;
    private FileChangeService fileChangeService;

    @Autowired
    public void setFileChangeService(FileChangeService fileChangeService) {
        if (fileChangeService == null) {
            throw new IllegalArgumentException("FileChangeService cannot be null");
        }
        this.fileChangeService = fileChangeService;
    }

    @PostConstruct
    private void init() {
        createDiffOutputDirectory();
    }

    private void createDiffOutputDirectory() {
        try {
            Files.createDirectories(Paths.get(DIFF_OUTPUT_DIR));
            Files.createDirectories(Paths.get(DECOMPILED_DIR));
        } catch (IOException e) {
            LOGGER.error("Failed to create directories: {}", e.getMessage());
            throw new RuntimeException("Failed to create required directories", e);
        }
    }

    public void setCleanVersion(String cleanVersion) {
        this.cleanVersion = cleanVersion;
    }

    public void setMcVersion(String mcVersion) {
        this.mcVersion = mcVersion;
    }

    public void compareVersions(String cleanVersion, String mcVersion) {
        LOGGER.info("\n=== Starting Version Comparison ===");
        
        try {
            File oldVersionDir = findVersionDirectory(cleanVersion);
            File newVersionDir = findVersionDirectory(mcVersion);

            if (!validateDirectories(oldVersionDir, newVersionDir)) {
                return;
            }

            FileChange fileChange = new FileChange(cleanVersion, mcVersion);
            compareDirectories(oldVersionDir.toPath(), newVersionDir.toPath(), fileChange);
            
            // Add statistics
            Map<String, String> stats = new HashMap<>();
            stats.put("totalAdded", String.valueOf(fileChange.getAddedFiles().size()));
            stats.put("totalModified", String.valueOf(fileChange.getModifiedFiles().size()));
            stats.put("totalDeleted", String.valueOf(fileChange.getDeletedFiles().size()));
            stats.put("totalChanges", String.valueOf(
                fileChange.getAddedFiles().size() + 
                fileChange.getModifiedFiles().size() + 
                fileChange.getDeletedFiles().size()
            ));
            fileChange.setStatistics(stats);

            // Generate and save report
            String reportPath = generateComparisonReport(fileChange);
            fileChange.setDiffReportPath(reportPath);
            fileChangeService.saveChange(fileChange);

        } catch (Exception e) {
            LOGGER.error("Error during version comparison: {}", e.getMessage(), e);
        }
    }

    private boolean validateDirectories(File oldDir, File newDir) {
        if (oldDir == null || !oldDir.exists()) {
            LOGGER.error("Old version directory not found");
            return false;
        }
        if (newDir == null || !newDir.exists()) {
            LOGGER.error("New version directory not found");
            return false;
        }
        return true;
    }

    private void compareDirectories(Path oldDir, Path newDir, FileChange fileChange) throws IOException {
        List<FileInfo> addedFiles = new ArrayList<>();
        List<FileInfo> modifiedFiles = new ArrayList<>();
        List<FileInfo> deletedFiles = new ArrayList<>();

        // Find added and modified files
        Files.walk(newDir)
            .filter(Files::isRegularFile)
            .forEach(newPath -> {
                Path relativePath = newDir.relativize(newPath);
                Path oldPath = oldDir.resolve(relativePath);
                
                if (!Files.exists(oldPath)) {
                    // Added file
                    addedFiles.add(new FileInfo(
                        relativePath.getFileName().toString(),
                        relativePath.toString(),
                        "ADDED"
                    ));
                } else if (filesAreDifferent(oldPath, newPath)) {
                    // Modified file
                    modifiedFiles.add(new FileInfo(
                        relativePath.getFileName().toString(),
                        oldPath.toString(),
                        newPath.toString()
                    ));
                }
            });

        // Find deleted files
        Files.walk(oldDir)
            .filter(Files::isRegularFile)
            .forEach(oldPath -> {
                Path relativePath = oldDir.relativize(oldPath);
                Path newPath = newDir.resolve(relativePath);
                
                if (!Files.exists(newPath)) {
                    deletedFiles.add(new FileInfo(
                        relativePath.getFileName().toString(),
                        relativePath.toString(),
                        "DELETED"
                    ));
                }
            });

        fileChange.setAddedFiles(addedFiles);
        fileChange.setModifiedFiles(modifiedFiles);
        fileChange.setDeletedFiles(deletedFiles);
    }

    private String generateComparisonReport(FileChange fileChange) {
        String reportPath = Paths.get(DIFF_OUTPUT_DIR, 
            String.format("diff_report_%s_to_%s.txt", 
                fileChange.getSourceVersion(), 
                fileChange.getTargetVersion()
            )).toString();
        
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(reportPath))) {
            writer.write(String.format("Comparison between %s and %s\n", 
                fileChange.getSourceVersion(), fileChange.getTargetVersion()));
            writer.write("Generated at: " + fileChange.getTimestamp() + "\n\n");

            // Write statistics
            writer.write("=== Statistics ===\n");
            fileChange.getStatistics().forEach((key, value) -> {
                try {
                    writer.write(String.format("%s: %s\n", key, value));
                } catch (IOException e) {
                    LOGGER.error("Error writing statistics: {}", e.getMessage());
                }
            });

            // Write detailed changes
            writer.write("\n=== Added Files ===\n");
            for (FileInfo file : fileChange.getAddedFiles()) {
                writer.write(String.format("+ %s (%s)\n", file.getFileName(), file.getFilePath()));
            }

            writer.write("\n=== Modified Files ===\n");
            for (FileInfo file : fileChange.getModifiedFiles()) {
                writer.write(String.format("* %s\n  From: %s\n  To: %s\n", 
                    file.getFileName(), file.getOriginalPath(), file.getNewPath()));
            }

            writer.write("\n=== Deleted Files ===\n");
            for (FileInfo file : fileChange.getDeletedFiles()) {
                writer.write(String.format("- %s (%s)\n", file.getFileName(), file.getFilePath()));
            }

            return reportPath;
        } catch (IOException e) {
            LOGGER.error("Error writing comparison report: {}", e.getMessage());
            return null;
        }
    }

    private boolean filesAreDifferent(Path file1, Path file2) {
        try {
            // Returns the position of first mismatch if files are different
            long mismatch = Files.mismatch(file1, file2);
            return mismatch != -1;
        } catch (IOException e) {
            LOGGER.error("Error comparing files '{}' and '{}': {}", 
                file1.getFileName(), 
                file2.getFileName(), 
                e.getMessage());
            return true; // Assume files are different if we can't compare them
        }
    }

    private boolean isMeldAvailable() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("which", "meld");
            Process process = processBuilder.start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void launchMeldComparison(Path oldPath, Path newPath) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                MELD_PATH,
                "--diff",
                oldPath.toString(),
                newPath.toString()
            );

            // Set up environment for headless operation
            Map<String, String> env = processBuilder.environment();
            env.put("DISPLAY", ":0");
            env.put("XAUTHORITY", "/root/.Xauthority");

            LOGGER.info("Launching Meld comparison...");
            Process process = processBuilder.start();

            // Handle process output
            handleProcessOutput(process);

        } catch (Exception e) {
            LOGGER.error("Error launching Meld: {}", e.getMessage());
        }
    }

    private void handleProcessOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains("gtk_icon_theme_get_for_screen")) {
                    LOGGER.info("Meld: {}", line);
                }
            }
        }
    }

    private void performFallbackComparison(File oldDir, File newDir) {
        // Implement fallback comparison logic here if needed
        LOGGER.info("Performing fallback comparison between {} and {}", oldDir, newDir);
    }

    private File findVersionDirectory(String version) {
        File baseDir = new File(DECOMPILED_DIR);
        if (!baseDir.exists()) {
            LOGGER.error("Decompiled directory not found: {}", baseDir.getAbsolutePath());
            return null;
        }

        File versionDir = new File(baseDir, version);
        if (!versionDir.exists()) {
            LOGGER.error("Version directory not found: {}", versionDir.getAbsolutePath());
            return null;
        }

        return versionDir;
    }

    public String getCleanVersion() {
        return cleanVersion;
    }

    public String getMcVersion() {
        return mcVersion;
    }

    // Add this method to configure logging
    private void configureLogging() {
        // Get the root logger
        ch.qos.logback.classic.Logger rootLogger = 
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

        // Create a rolling file appender
        ch.qos.logback.core.rolling.RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> fileAppender = 
            new ch.qos.logback.core.rolling.RollingFileAppender<>();

        // Set rolling policy
        ch.qos.logback.core.rolling.TimeBasedRollingPolicy<?> rollingPolicy = 
            new ch.qos.logback.core.rolling.TimeBasedRollingPolicy<>();
        rollingPolicy.setFileNamePattern("logs/minecraft-mod-updater.%d{yyyy-MM-dd}.log");
        rollingPolicy.setMaxHistory(7); // Keep 7 days of logs
        rollingPolicy.setParent(fileAppender);
        fileAppender.setRollingPolicy(rollingPolicy);

        // Set encoder
        ch.qos.logback.classic.encoder.PatternLayoutEncoder encoder = 
            new ch.qos.logback.classic.encoder.PatternLayoutEncoder();
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n");
        encoder.setContext(rootLogger.getLoggerContext());
        encoder.start();
        fileAppender.setEncoder(encoder);

        // Start appender
        fileAppender.setContext(rootLogger.getLoggerContext());
        fileAppender.start();

        // Add appender to root logger
        rootLogger.addAppender(fileAppender);
    }

    // Call this in constructor
    public MinecraftVersionHandler() {
        // Create diff_results directory if it doesn't exist
        new File(DIFF_OUTPUT_DIR).mkdirs();
    }
}