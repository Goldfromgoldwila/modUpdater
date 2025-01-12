package core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.*;
import java.nio.file.*;

@Component
public class MinecraftVersionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftVersionHandler.class);
    private static final String DECOMPILED_DIR = "versions";
    private static final String MELD_PATH = "meld"; // Meld is available in PATH on Linux

    private String cleanVersion;
    private String mcVersion;

    public void setCleanVersion(String cleanVersion) {
        this.cleanVersion = cleanVersion;
    }

    public void setMcVersion(String mcVersion) {
        this.mcVersion = mcVersion;
    }

    public void compareVersions(String cleanVersion, String mcVersion) {
        LOGGER.info("Comparing versions: {} -> {}", cleanVersion, mcVersion);
        this.cleanVersion = cleanVersion;
        this.mcVersion = mcVersion;

        try {
            File oldVersionDir = findVersionDirectory(cleanVersion);
            File newVersionDir = findVersionDirectory(mcVersion);

            if (oldVersionDir == null || !oldVersionDir.exists()) {
                LOGGER.error("Old version directory not found for version: {}", cleanVersion);
                return;
            }

            if (newVersionDir == null || !newVersionDir.exists()) {
                LOGGER.error("New version directory not found for version: {}", mcVersion);
                return;
            }

            launchMeld(oldVersionDir.toPath(), newVersionDir.toPath());

        } catch (Exception e) {
            LOGGER.error("Error during version comparison: {}", e.getMessage(), e);
        }
    }

    private File findVersionDirectory(String version) {
        File baseDir = new File(DECOMPILED_DIR);
        if (!baseDir.exists()) {
            LOGGER.error("Decompiled directory not found: {}", DECOMPILED_DIR);
            return null;
        }

        File versionDir = new File(baseDir, version);
        if (!versionDir.exists()) {
            LOGGER.error("Version directory not found: {}", version);
            return null;
        }

        return versionDir;
    }

    private void launchMeld(Path oldPath, Path newPath) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                MELD_PATH,
                "--diff",
                oldPath.toString(),
                newPath.toString()
            );

            LOGGER.info("Launching Meld to compare:");
            LOGGER.info("  Old version ({}): {}", cleanVersion, oldPath);
            LOGGER.info("  New version ({}): {}", mcVersion, newPath);

            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            
            LOGGER.info("Meld comparison completed with exit code: {}", exitCode);

        } catch (IOException | InterruptedException e) {
            LOGGER.error("Error launching Meld: {}", e.getMessage(), e);
        }
    }

    public String getCleanVersion() {
        return cleanVersion;
    }

    public String getMcVersion() {
        return mcVersion;
    }
}