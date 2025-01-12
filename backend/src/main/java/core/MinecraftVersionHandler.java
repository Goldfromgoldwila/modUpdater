package core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class MinecraftVersionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftVersionHandler.class);
    private static final String DECOMPILED_DIR = "versions";
    private static final String DIFF_OUTPUT_DIR = "diff_results";
    private static final String MELD_PATH = "meld";

    private String cleanVersion;
    private String mcVersion;

    public void setCleanVersion(String cleanVersion) {
        this.cleanVersion = cleanVersion;
    }

    public void setMcVersion(String mcVersion) {
        this.mcVersion = mcVersion;
    }

    public void compareVersions(String cleanVersion, String mcVersion) {
        LOGGER.info("\n=== Starting Version Comparison ===");
        LOGGER.info("Source version: {}", cleanVersion);
        LOGGER.info("Target version: {}", mcVersion);
        
        this.cleanVersion = cleanVersion;
        this.mcVersion = mcVersion;

        try {
            File oldVersionDir = findVersionDirectory(cleanVersion);
            File newVersionDir = findVersionDirectory(mcVersion);

            if (!validateDirectories(oldVersionDir, newVersionDir)) {
                return;
            }

            // Create diff output directory
            createDiffOutputDirectory();

            // Perform comparison using different methods
            Map<String, List<String>> changes = compareDirectories(oldVersionDir.toPath(), newVersionDir.toPath());
            generateComparisonReport(changes);

            // Use Meld for visual comparison if available
            if (isMeldAvailable()) {
                launchMeldComparison(oldVersionDir.toPath(), newVersionDir.toPath());
            } else {
                LOGGER.warn("Meld is not available. Using fallback comparison method.");
                performFallbackComparison(oldVersionDir, newVersionDir);
            }

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

    private void createDiffOutputDirectory() {
        File diffDir = new File(DIFF_OUTPUT_DIR);
        if (!diffDir.exists()) {
            diffDir.mkdirs();
            LOGGER.info("Created diff output directory: {}", diffDir.getAbsolutePath());
        }
    }

    private Map<String, List<String>> compareDirectories(Path oldPath, Path newPath) throws IOException {
        Map<String, List<String>> changes = new HashMap<>();
        changes.put("added", new ArrayList<>());
        changes.put("modified", new ArrayList<>());
        changes.put("deleted", new ArrayList<>());

        // Get all files from both directories
        Set<String> oldFiles = getRelativeFilePaths(oldPath);
        Set<String> newFiles = getRelativeFilePaths(newPath);

        // Find added files
        newFiles.stream()
            .filter(file -> !oldFiles.contains(file))
            .forEach(file -> changes.get("added").add(file));

        // Find deleted files
        oldFiles.stream()
            .filter(file -> !newFiles.contains(file))
            .forEach(file -> changes.get("deleted").add(file));

        // Find modified files
        oldFiles.stream()
            .filter(newFiles::contains)
            .filter(file -> isFileModified(oldPath.resolve(file), newPath.resolve(file)))
            .forEach(file -> changes.get("modified").add(file));

        return changes;
    }

    private Set<String> getRelativeFilePaths(Path basePath) throws IOException {
        try (Stream<Path> paths = Files.walk(basePath)) {
            return paths
                .filter(Files::isRegularFile)
                .map(path -> basePath.relativize(path).toString())
                .collect(Collectors.toSet());
        }
    }

    private boolean isFileModified(Path oldFile, Path newFile) {
        try {
            byte[] oldContent = Files.readAllBytes(oldFile);
            byte[] newContent = Files.readAllBytes(newFile);
            return !Arrays.equals(oldContent, newContent);
        } catch (IOException e) {
            LOGGER.error("Error comparing files: {} and {}", oldFile, newFile, e);
            return false;
        }
    }

    private void generateComparisonReport(Map<String, List<String>> changes) {
        LOGGER.info("\n=== Comparison Report ===");
        LOGGER.info("Added files ({}): ", changes.get("added").size());
        changes.get("added").forEach(file -> LOGGER.info("  + {}", file));
        
        LOGGER.info("\nModified files ({}): ", changes.get("modified").size());
        changes.get("modified").forEach(file -> LOGGER.info("  * {}", file));
        
        LOGGER.info("\nDeleted files ({}): ", changes.get("deleted").size());
        changes.get("deleted").forEach(file -> LOGGER.info("  - {}", file));
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
}