package core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import core.event.DecompilationCompleteEvent;

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

    @EventListener
    public void handleDecompilationComplete(DecompilationCompleteEvent event) {
        LOGGER.info("Received decompilation complete event for mod: {}", event.getModName());
        scanModFiles(event.getModName());
    }

    public List<ModFile> scanModFiles(String version) {
        List<ModFile> modFiles = new ArrayList<>();
        Path decompiledPath = Paths.get("decompiled_mods", version);
        
        try {
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

            appendToVersionDiffFile(modFiles, version);
            
            LOGGER.info("Scanned {} files from mod: {}", modFiles.size(), version);
            return modFiles;
        } catch (IOException e) {
            LOGGER.error("Error scanning mod files: {}", e.getMessage());
            throw new RuntimeException("Failed to scan mod files", e);
        }
    }

    private void appendToVersionDiffFile(List<ModFile> modFiles, String version) throws IOException {
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
                    String timestamp = String.valueOf(System.currentTimeMillis());
                    return Paths.get(DIFF_OUTPUT_DIR, 
                        String.format("diff_report_%s_%s.txt", version, timestamp));
                });
        }

        try (PrintWriter writer = new PrintWriter(new FileWriter(reportPath.toFile(), true))) {
            writer.println("\n=== Mod Files List ===");
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
        if (fileName.endsWith(".class")) return "CLASS";
        if (fileName.endsWith(".java")) return "JAVA";
        if (fileName.endsWith(".json")) return "JSON";
        return "OTHER";
    }
}
