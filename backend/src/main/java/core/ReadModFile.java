package core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Component
public class ReadModFile {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReadModFile.class);
    private static final String DIFF_OUTPUT_DIR = "diff_results";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY = 2000; // 2 seconds

    @Autowired
    private ModDecompilerService modDecompilerService;

    @Retryable(
        value = {RuntimeException.class},
        maxAttempts = MAX_RETRIES,
        backoff = @Backoff(delay = RETRY_DELAY)
    )
    public List<ModFile> scanModFiles(String version) {
        List<ModFile> modFiles = new ArrayList<>();
        String currentModName = null;
        int attempts = 0;

        while (attempts < MAX_RETRIES) {
            currentModName = modDecompilerService.getCurrentModName();
            if (currentModName != null) {
                break;
            }
            attempts++;
            LOGGER.warn("Attempt {} of {}: Waiting for mod name to be available...", 
                attempts, MAX_RETRIES);
            try {
                Thread.sleep(RETRY_DELAY);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.error("Retry interrupted", e);
                break;
            }
        }

        if (currentModName == null) {
            LOGGER.error("No current mod name found after {} attempts", MAX_RETRIES);
            throw new RuntimeException("Failed to get mod name after retries");
        }

        Path decompiledPath = Paths.get("decompiled_mods", currentModName);
        
        try {
            // Scan all files in the decompiled directory
            Files.walk(decompiledPath)
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    try {
                        ModFile modFile = new ModFile(
                            path.getFileName().toString(),
                            path.toString(),
                            getFileType(path),
                            Files.size(path)
                        );
                        modFiles.add(modFile);
                    } catch (IOException e) {
                        LOGGER.error("Error processing file {}: {}", path, e.getMessage());
                    }
                });

            // Append to the version diff file
            appendToVersionDiffFile(modFiles, version);
            
            LOGGER.info("Scanned {} files from mod: {} for version {}", 
                modFiles.size(), currentModName, version);
            return modFiles;
        } catch (IOException e) {
            LOGGER.error("Error scanning mod files: {}", e.getMessage());
            throw new RuntimeException("Failed to scan mod files", e);
        }
    }

    private void appendToVersionDiffFile(List<ModFile> modFiles, String version) throws IOException {
        // Find the latest diff report for this version
        Path diffDir = Paths.get(DIFF_OUTPUT_DIR);
        String filePrefix = "diff_report_" + version;
        
        Path reportPath;
        try (Stream<Path> files = Files.list(diffDir)) {
            reportPath = files
                .filter(path -> path.getFileName().toString().startsWith(filePrefix))
                .max(Comparator.comparingLong(path -> {
                    try {
                        return Files.getLastModifiedTime(path).toMillis();
                    } catch (IOException e) {
                        return 0L;
                    }
                }))
                .orElseGet(() -> {
                    // If no existing file found, create new one with timestamp
                    String timestamp = String.valueOf(System.currentTimeMillis());
                    return Paths.get(DIFF_OUTPUT_DIR, 
                        String.format("diff_report_%s_%s.txt", version, timestamp));
                });
        }

        // Append to the existing file
        try (PrintWriter writer = new PrintWriter(new FileWriter(reportPath.toFile(), true))) {
            writer.println("\n=== Mod Files List ===");
            writer.println("Mod Name: " + modDecompilerService.getCurrentModName());
            writer.println("Generated: " + LocalDateTime.now());
            writer.println("Total Files: " + modFiles.size());
            writer.println();

            for (ModFile file : modFiles) {
                writer.printf("File: %s%n", file.getName());
                writer.printf("Path: %s%n", file.getPath());
                writer.printf("Type: %s%n", file.getType());
                writer.printf("Size: %d bytes%n", file.getSize());
                writer.println();
            }
            
            writer.println("----------------------------------------\n");
        }
        
        LOGGER.info("Appended mod files list to diff report: {}", reportPath);
    }

    private String getFileType(Path path) {
        String fileName = path.toString().toLowerCase();
        
        // Code files
        if (fileName.endsWith(".class")) return "CLASS";
        if (fileName.endsWith(".java")) return "JAVA";
        
        // Data files
        if (fileName.endsWith(".json")) return "JSON";
        if (fileName.endsWith(".mcmeta")) return "MCMETA";
        if (fileName.endsWith(".toml")) return "TOML";
        if (fileName.endsWith(".properties")) return "PROPERTIES";
        if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) return "YAML";
        if (fileName.endsWith(".xml")) return "XML";
        
        // Resource files
        if (fileName.endsWith(".png")) return "TEXTURE";
        if (fileName.endsWith(".ogg") || fileName.endsWith(".wav")) return "SOUND";
        if (fileName.endsWith(".lang")) return "LANGUAGE";
        if (fileName.endsWith(".nbt")) return "NBT";
        if (fileName.endsWith(".dat")) return "DATA";
        
        // Model files
        if (fileName.endsWith(".obj")) return "3D_MODEL";
        if (fileName.endsWith(".mtl")) return "MATERIAL";
        
        // Config files
        if (fileName.endsWith(".cfg")) return "CONFIG";
        if (fileName.endsWith(".info")) return "INFO";
        
        // Mod specific files
        if (fileName.endsWith("mixin.json")) return "MIXIN_CONFIG";
        if (fileName.endsWith("fabric.mod.json")) return "FABRIC_MOD";
        if (fileName.endsWith("forge.toml")) return "FORGE_MOD";
        if (fileName.endsWith(".accesswidener")) return "ACCESS_WIDENER";
        
        // Misc files
        if (fileName.endsWith(".txt")) return "TEXT";
        if (fileName.endsWith(".md")) return "MARKDOWN";
        if (fileName.endsWith(".csv")) return "CSV";
        
        return "OTHER";
    }
}
