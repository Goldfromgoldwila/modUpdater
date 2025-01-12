package core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

@Component
public class MinecraftVersionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftVersionHandler.class);
    private static final String DECOMPILED_DIR = "versions";
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
            // Validate meld installation
            if (!isMeldInstalled()) {
                LOGGER.error("Meld is not installed or not accessible in PATH");
                return;
            }

            File oldVersionDir = findVersionDirectory(cleanVersion);
            File newVersionDir = findVersionDirectory(mcVersion);

            if (oldVersionDir == null || !oldVersionDir.exists()) {
                LOGGER.error("Old version directory not found: {}", oldVersionDir);
                logDirectoryContents(new File(DECOMPILED_DIR));
                return;
            }

            if (newVersionDir == null || !newVersionDir.exists()) {
                LOGGER.error("New version directory not found: {}", newVersionDir);
                logDirectoryContents(new File(DECOMPILED_DIR));
                return;
            }

            LOGGER.info("Found both version directories:");
            LOGGER.info("  Old: {}", oldVersionDir.getAbsolutePath());
            LOGGER.info("  New: {}", newVersionDir.getAbsolutePath());

            launchMeld(oldVersionDir.toPath(), newVersionDir.toPath());

        } catch (Exception e) {
            LOGGER.error("Error during version comparison: {}", e.getMessage(), e);
        }
    }

    private boolean isMeldInstalled() {
        try {
            Process process = new ProcessBuilder("which", "meld").start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String meldPath = reader.readLine();
                LOGGER.info("Found Meld at: {}", meldPath);
                return true;
            }
            LOGGER.error("Meld not found in PATH (exit code: {})", exitCode);
            return false;
        } catch (Exception e) {
            LOGGER.error("Error checking Meld installation: {}", e.getMessage());
            return false;
        }
    }

    private void logDirectoryContents(File dir) {
        LOGGER.info("Contents of directory {}: ", dir.getAbsolutePath());
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    LOGGER.info("  - {}", file.getName());
                }
            } else {
                LOGGER.info("  (empty or not readable)");
            }
        } else {
            LOGGER.info("  (directory does not exist)");
        }
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

    private void launchMeld(Path oldPath, Path newPath) {
        try {
            LOGGER.info("\n=== Launching Meld Comparison ===");
            LOGGER.info("Command: meld --diff {} {}", oldPath, newPath);

            ProcessBuilder processBuilder = new ProcessBuilder(
                MELD_PATH,
                "--diff",
                oldPath.toString(),
                newPath.toString()
            );

            // Redirect error stream to output stream
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            // Log process output in real-time
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOGGER.info("Meld output: {}", line);
                }
            }

            // Wait for process with timeout
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            
            if (!completed) {
                LOGGER.error("Meld process timed out after 30 seconds");
                process.destroyForcibly();
            } else {
                int exitCode = process.exitValue();
                LOGGER.info("Meld comparison completed with exit code: {} ({})", 
                    exitCode, interpretExitCode(exitCode));
            }

        } catch (IOException | InterruptedException e) {
            LOGGER.error("Error launching Meld: {}", e.getMessage(), e);
            LOGGER.error("Stack trace:", e);
        }
    }

    private String interpretExitCode(int exitCode) {
        switch (exitCode) {
            case 0: return "Success - No differences found";
            case 1: return "Success - Differences found";
            case 2: return "Error - Failed to parse command line";
            case 3: return "Error - Internal error";
            default: return "Unknown exit code";
        }
    }

    public String getCleanVersion() {
        return cleanVersion;
    }

    public String getMcVersion() {
        return mcVersion;
    }
}