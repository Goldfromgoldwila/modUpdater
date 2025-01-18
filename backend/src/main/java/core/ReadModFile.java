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
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Arrays;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReadModFile {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReadModFile.class);
    private static final String DIFF_OUTPUT_DIR = "diff_results";
    private static final Set<String> IMPORTANT_EXTENSIONS = Set.of(
        // Source files
        ".java", ".kt", ".class",
        // Metadata files
        ".json", ".mcmeta", ".toml",
        // Config files
        ".cfg", ".yml", ".properties",
        // Resource files
        ".png", ".ogg",
        // Language files
        ".lang",
        // Data files
        ".nbt",
        // Build files
        ".gradle",
        // Documentation
        ".md", ".txt",
        // Manifest
        ".mf"
    );
    private static final Set<String> MISSING_DEPENDENCIES = new HashSet<>();
    private static final Pattern DECOMPILER_HEADER_PATTERN = Pattern.compile(
        "(?s)/\\*.*?Could not load the following classes:(.*?)\\*/");

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
                        String content = null;
                        if (shouldReadContent(path)) {
                            content = Files.readString(path);
                        }
                        
                        ModFile modFile = new ModFile(
                            path.getFileName().toString(),
                            path.toString(),
                            getFileType(path),
                            Files.size(path),
                            content
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

    private boolean shouldReadContent(Path path) {
        String fileName = path.toString().toLowerCase();
        
        // Don't read binary files
        if (fileName.endsWith(".png") || 
            fileName.endsWith(".ogg") || 
            fileName.endsWith(".class") || 
            fileName.endsWith(".nbt")) {
            return false;
        }
        
        // Read all text-based files
        return IMPORTANT_EXTENSIONS.stream()
            .filter(ext -> !ext.equals(".png") && 
                          !ext.equals(".ogg") && 
                          !ext.equals(".class") && 
                          !ext.equals(".nbt"))
            .anyMatch(fileName::endsWith);
    }

    private void appendToVersionDiffFile(List<ModFile> modFiles, String version) throws IOException {
        Path diffDir = Paths.get(DIFF_OUTPUT_DIR);
        if (!Files.exists(diffDir)) {
            Files.createDirectories(diffDir);
        }
        
        String timestamp = String.valueOf(System.currentTimeMillis());
        Path reportPath = Paths.get(DIFF_OUTPUT_DIR, 
            String.format("diff_report_mod%s_%s.txt", version, timestamp));

        try (PrintWriter writer = new PrintWriter(new FileWriter(reportPath.toFile()))) {
            writer.println("=== Mod Files Analysis Report ===");
            writer.println("Generated: " + LocalDateTime.now());
            writer.println("Version: " + version);
            writer.println("Total Files: " + modFiles.size());
            writer.println("\n=== File Details ===\n");

            // Add dependency analysis section
            writer.println("\n=== Dependency Analysis ===");
            if (!MISSING_DEPENDENCIES.isEmpty()) {
                writer.println("\nMissing Dependencies:");
                MISSING_DEPENDENCIES.forEach(dep -> writer.println("- " + dep));
                writer.println("\nNote: These dependencies are required but were not found during decompilation.");
            }

            // Group files by type
            Map<String, List<ModFile>> filesByType = modFiles.stream()
                .collect(Collectors.groupingBy(ModFile::getType));

            filesByType.forEach((type, files) -> {
                writer.println("\n=== " + type.toUpperCase() + " Files ===");
                files.forEach(file -> {
                    writer.println("\nFile: " + file.getName());
                    writer.println("Path: " + file.getPath());
                    writer.println("Size: " + formatFileSize(file.getSize()));
                    
                    if (file.getContent() != null) {
                        processDecompiledContent(file, writer);
                    }
                    writer.println();
                });
            });
        }
        
        LOGGER.info("Generated mod files report: {}", reportPath);
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp-1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private String getFileType(Path path) {
        String fileName = path.toString().toLowerCase();
        
        // Source files
        if (fileName.endsWith(".java")) return "java_source";
        if (fileName.endsWith(".kt")) return "kotlin_source";
        if (fileName.endsWith(".class")) return "compiled_class";
        
        // Metadata files
        if (fileName.endsWith("fabric.mod.json")) return "fabric_metadata";
        if (fileName.contains("mixins.json")) return "mixin_config";
        if (fileName.endsWith(".mcmeta")) return "minecraft_metadata";
        if (fileName.endsWith("mods.toml")) return "forge_metadata";
        if (fileName.endsWith(".toml")) return "toml_config";
        
        // Config files
        if (fileName.endsWith(".cfg")) return "config";
        if (fileName.endsWith(".yml")) return "yaml_config";
        if (fileName.endsWith(".properties")) return "properties";
        
        // Resource files
        if (fileName.endsWith(".png")) return "texture";
        if (fileName.endsWith(".ogg")) return "sound";
        if (fileName.contains("/assets/") && fileName.endsWith(".json")) return "asset_data";
        
        // Language files
        if (fileName.endsWith(".lang")) return "language_legacy";
        if (fileName.contains("/lang/") && fileName.endsWith(".json")) return "language";
        
        // Data files
        if (fileName.contains("/data/") && fileName.endsWith(".json")) return "game_data";
        if (fileName.endsWith(".nbt")) return "nbt_data";
        
        // Build files
        if (fileName.endsWith(".gradle")) return "build_script";
        if (fileName.endsWith(".bat")) return "batch_script";
        if (fileName.endsWith(".sh")) return "shell_script";
        
        // Documentation
        if (fileName.endsWith(".md")) return "markdown";
        if (fileName.endsWith(".txt")) return "text";
        
        // Manifest
        if (fileName.endsWith(".mf") || fileName.contains("manifest")) return "manifest";
        
        return "other";
    }

    private void processDecompiledContent(ModFile modFile, PrintWriter writer) {
        if (modFile.getContent() == null) return;
        
        // Check for decompiler warnings about missing classes
        Matcher matcher = DECOMPILER_HEADER_PATTERN.matcher(modFile.getContent());
        if (matcher.find()) {
            String missingClasses = matcher.group(1).trim();
            Arrays.stream(missingClasses.split("\n"))
                .map(String::trim)
                .filter(s -> s.startsWith("*"))
                .map(s -> s.substring(1).trim())
                .forEach(MISSING_DEPENDENCIES::add);
            
            writer.println("\nDecompilation Warnings:");
            writer.println("Missing Dependencies:");
            writer.println(missingClasses);
        }
        
        writer.println("\nContent:");
        writer.println("----------------------------------------");
        writer.println(modFile.getContent());
        writer.println("----------------------------------------");
    }
}
