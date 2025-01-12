package core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Patch;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.DeltaType;
import com.github.difflib.text.DiffRow;
import com.github.difflib.text.DiffRowGenerator;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.CompletableFuture;
import net.jpountz.xxhash.XXHashFactory;
import net.jpountz.xxhash.XXHash64;
import java.util.Map.Entry;
import java.util.AbstractMap.SimpleEntry;
import java.util.stream.Collectors;
import java.util.TreeMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.nio.file.attribute.BasicFileAttributes;
import lombok.Data;

@EnableScheduling
@Component
@Validated
@CacheConfig(cacheNames = {"versionComparisons"})
public class MinecraftVersionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftVersionHandler.class);
    private static final String DECOMPILED_DIR = "versions";
    private static final String ASSETS_DIR = "assets";
    private static final String NBT_DIR = "data";
    private static final String CACHE_DIR = "cache";
    private static final int LARGE_FILE_THRESHOLD = 10 * 2048 * 2048; // 20MB
    private static final int CHUNK_SIZE = 2048 * 2048; // 2MB chunks for large files

    private final Map<String, FileComparison> fileComparisons = new ConcurrentHashMap<>();
    private final Set<String> addedFiles = ConcurrentHashMap.newKeySet();
    private final Set<String> removedFiles = ConcurrentHashMap.newKeySet();
    private final Set<String> modifiedFiles = ConcurrentHashMap.newKeySet();
    private final FileCache fileCache;
    private final ValidationService validationService;
    private final DiffGenerator diffGenerator;

    private String cleanVersion;
    private String mcVersion;

    @Autowired
    private FileHashCache fileHashCache;

    @Autowired
    public MinecraftVersionHandler(
            FileCache fileCache,
            ValidationService validationService,
            DiffGenerator diffGenerator) {
        this.fileCache = fileCache;
        this.validationService = validationService;
        this.diffGenerator = diffGenerator;
    }

    public void setCleanVersion(String cleanVersion) {
        this.cleanVersion = cleanVersion;
    }

    public void setMcVersion(String mcVersion) {
        this.mcVersion = mcVersion;
    }

    public ComparisonResult compareMinecraftVersions(String cleanVersion, String mcVersion) throws IOException {
        LOGGER.info("=== Starting New Comparison Process ===");
        LOGGER.info("Attempt to compare versions: {} -> {}", cleanVersion, mcVersion);
        
        this.cleanVersion = cleanVersion;
        this.mcVersion = mcVersion;
        
        try {
            validateVersions(this.cleanVersion, this.mcVersion);
            ComparisonResult result = new ComparisonResult();
            
            File oldVersionDir = findAndValidateDirectory(this.cleanVersion);
            File newVersionDir = findAndValidateDirectory(this.mcVersion);

            if (!oldVersionDir.exists()) {
                LOGGER.error("Old version directory not found: {}", oldVersionDir.getAbsolutePath());
                throw new ValidationException("Old version directory not found: " + this.cleanVersion);
            }

            if (!newVersionDir.exists()) {
                LOGGER.error("New version directory not found: {}", newVersionDir.getAbsolutePath());
                throw new ValidationException("New version directory not found: " + this.mcVersion);
            }

            LOGGER.info("Directories validated successfully:");
            LOGGER.info("Old version directory: {}", oldVersionDir.getAbsolutePath());
            LOGGER.info("New version directory: {}", newVersionDir.getAbsolutePath());

            long startTime = System.currentTimeMillis();
            LOGGER.info("Starting comparison at: {}", new Date(startTime));

            try {
                performComparison(oldVersionDir, newVersionDir, this.cleanVersion, this.mcVersion, result);
            } catch (Exception e) {
                LOGGER.error("Error during comparison process", e);
                throw new ComparisonException("Comparison process failed", e);
            }

            try {
                saveComparisonState(this.cleanVersion, this.mcVersion, result);
            } catch (Exception e) {
                LOGGER.error("Error saving comparison state", e);
                // Continue despite save error
            }

            long endTime = System.currentTimeMillis();
            LOGGER.info("Comparison process completed in {} ms", endTime - startTime);
            
            // Verify result is not empty
            if (result.getAddedFiles().isEmpty() && 
                result.getRemovedFiles().isEmpty() && 
                result.getModifications().isEmpty()) {
                LOGGER.warn("Warning: Comparison result is empty. This might indicate a problem.");
            }
            
            return result;
        } catch (ValidationException e) {
            LOGGER.error("Validation error during comparison: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            LOGGER.error("Unexpected error during comparison process", e);
            throw new ComparisonException("Failed to compare versions", e);
        } finally {
            LOGGER.info("=== Comparison Process Ended ===\n");
        }
    }

    private void validateVersions(String cleanVersion, String mcVersion) {
        if (!validationService.isValidVersion(cleanVersion)) {
            throw new ValidationException("Invalid clean version format: " + cleanVersion);
        }
        if (!validationService.isValidVersion(mcVersion)) {
            throw new ValidationException("Invalid MC version format: " + mcVersion);
        }
    }

    private void performComparison(File oldVersionDir, File newVersionDir, 
            String cleanVersion, String mcVersion, ComparisonResult result) throws IOException {
        LOGGER.info("Starting performComparison method for versions {} and {}", cleanVersion, mcVersion);
        
        Optional<ComparisonState> previousState = loadPreviousComparisonState(cleanVersion, mcVersion);
        
        if (previousState.isPresent()) {
            LOGGER.info("Performing incremental comparison for versions {} and {}", cleanVersion, mcVersion);
            try {
                performIncrementalComparison(oldVersionDir, newVersionDir, previousState.get(), result);
            } catch (IOException e) {
                LOGGER.error("Error during incremental comparison: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to perform incremental comparison", e);
            }
        } else {
            LOGGER.info("Performing full comparison for versions {} and {}", cleanVersion, mcVersion);
            performFullComparison(oldVersionDir, newVersionDir, result);
        }
        
        LOGGER.info("Comparison completed. Added files: {}, Removed files: {}, Modified files: {}", 
            result.getAddedFiles().size(), 
            result.getRemovedFiles().size(), 
            result.getModifications().size());
    }

    private void performIncrementalComparison(File oldVersionDir, File newVersionDir, 
            ComparisonState previousState, ComparisonResult result) throws IOException {
        LOGGER.info("Starting incremental comparison");
        try {
            Set<Path> changedFiles = findChangedFilesSinceLastComparison(oldVersionDir, newVersionDir, previousState);
            
            LOGGER.info("Found {} changed files", changedFiles.size());
            
            for (Path relativePath : changedFiles) {
                compareFile(oldVersionDir.toPath(), newVersionDir.toPath(), relativePath.toString(), result);
            }
        } catch (Exception e) {
            LOGGER.error("Error during incremental comparison", e);
            throw new ComparisonException("Failed to perform incremental comparison", e);
        }
    }

    private void performFullComparison(File oldVersionDir, File newVersionDir, ComparisonResult result) {
        LOGGER.info("Starting full comparison between versions");
        long startTime = System.currentTimeMillis();
        AtomicInteger unchangedCount = new AtomicInteger(0);
        
        try {
            Map<String, String> oldFileHashes = new ConcurrentHashMap<>();
            Map<String, String> newFileHashes = new ConcurrentHashMap<>();
            
            // Process directories in parallel with caching
            CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> processDirectoryWithCache(oldVersionDir, oldFileHashes)),
                CompletableFuture.runAsync(() -> processDirectoryWithCache(newVersionDir, newFileHashes))
            ).join();

            // Compare files using cached hashes
            compareFilesWithCache(oldVersionDir, newVersionDir, oldFileHashes, newFileHashes, result, unchangedCount);
            
            result.setUnchangedFiles(unchangedCount.get());
        } finally {
            fileHashCache.saveCache();
        }
    }

    private void processDirectoryWithCache(File dir, Map<String, String> fileHashes) {
        try {
            Path basePath = dir.toPath();
            if (fileHashCache.isDirectoryUnchanged(basePath)) {
                LOGGER.info("Directory unchanged, using cached data: {}", basePath);
                return;
            }

            Files.walk(basePath)
                .parallel()
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    try {
                        String relativePath = basePath.relativize(path).toString();
                        String hash = fileHashCache.getHash(path)
                            .orElseGet(() -> {
                                try {
                                    String newHash = calculateQuickHash(path);
                                    fileHashCache.putHash(path, newHash);
                                    return newHash;
                                } catch (IOException e) {
                                    LOGGER.error("Error calculating hash: {}", path, e);
                                    return "";
                                }
                            });
                        fileHashes.put(relativePath, hash);
                    } catch (Exception e) {
                        LOGGER.error("Error processing file: {}", path, e);
                    }
                });
        } catch (IOException e) {
            LOGGER.error("Error walking directory: {}", dir, e);
        }
    }

    // Quick hash calculation using xxHash for better performance
    private String calculateQuickHash(Path path) throws IOException {
        XXHashFactory factory = XXHashFactory.fastestInstance();
        XXHash64 hash = factory.hash64();
        
        try (InputStream is = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int read;
            while ((read = is.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            byte[] data = baos.toByteArray();
            return Long.toHexString(hash.hash(data, 0, data.length, 0));
        }
    }

    // Detailed comparison only for modified files
    private void compareFileDetails(Path oldPath, Path newPath, String relativePath, ComparisonResult result) {
        try {
            long oldSize = Files.size(oldPath);
            long newSize = Files.size(newPath);
            String sizeChange = String.format("Size changed from %d to %d bytes (difference: %d bytes)", 
                oldSize, newSize, newSize - oldSize);

            // Generate diff for text files
            if (isTextFile(relativePath)) {
                try {
                    String diff = diffGenerator.generateTextDiff(oldPath, newPath);
                    result.addModifiedFile(relativePath, "CONTENT_CHANGED", 
                        sizeChange + "\nDifferences:\n" + diff);
                } catch (IOException e) {
                    LOGGER.warn("Failed to generate diff for {}: {}", relativePath, e.getMessage());
                    result.addModifiedFile(relativePath, "SIZE_CHANGED", sizeChange);
                }
            } else {
                // For binary files, just show size change
                result.addModifiedFile(relativePath, "SIZE_CHANGED", sizeChange);
            }
        } catch (IOException e) {
            LOGGER.error("Error comparing files: {} and {}", oldPath, newPath, e);
        }
    }

    private boolean isTextFile(String path) {
        return path.endsWith(".java") || 
               path.endsWith(".txt") || 
               path.endsWith(".json") || 
               path.endsWith(".xml") || 
               path.endsWith(".yml") || 
               path.endsWith(".properties") || 
               path.endsWith(".mcmeta") || 
               path.endsWith(".lang") || 
               path.endsWith(".cfg") || 
               path.endsWith(".toml");
    }

    private void compareBinaryFiles(Path oldPath, Path newPath, String relativePath, ComparisonResult result) throws IOException {
        try (InputStream oldIs = new BufferedInputStream(Files.newInputStream(oldPath));
             InputStream newIs = new BufferedInputStream(Files.newInputStream(newPath))) {
            
            byte[] oldBuffer = new byte[8192];
            byte[] newBuffer = new byte[8192];
            
            int oldRead, newRead;
            long position = 0;
            
            while ((oldRead = oldIs.read(oldBuffer)) != -1) {
                newRead = newIs.read(newBuffer);
                
                if (oldRead != newRead || !Arrays.equals(oldBuffer, 0, oldRead, newBuffer, 0, newRead)) {
                    result.addModifiedFile(relativePath, "CONTENT_MODIFIED", 
                        "Binary content differs at position " + position);
                    return;
                }
                position += oldRead;
            }
        }
    }

    private String determineFileType(String filename) {
        if (filename.endsWith(".class")) return "Java Class";
        if (filename.endsWith(".java")) return "Java Source";
        if (filename.endsWith(".json")) return "JSON";
        if (filename.endsWith(".txt")) return "Text";
        if (filename.endsWith(".xml")) return "XML";
        if (filename.endsWith(".properties")) return "Properties";
        if (filename.endsWith(".yml") || filename.endsWith(".yaml")) return "YAML";
        if (filename.endsWith(".nbt")) return "NBT Data";
        if (filename.endsWith(".mcmeta")) return "Minecraft Metadata";
        if (filename.endsWith(".dat")) return "Data File";
        if (filename.endsWith(".png") || filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "Image";
        
        return "Unknown File Type";
    }

    private void generateComparisonReport(
            long startTime, 
            int processedFiles, 
            int addedFiles, 
            int removedFiles, 
            int modifiedFiles,
            int unchangedFiles,
            ConcurrentMap<String, List<String>> fileChangeDetails) {
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        // Detailed Logging Report
        LOGGER.info("===== Comparison Report =====");
        LOGGER.info("Total Processing Time: {} ms", duration);
        LOGGER.info("Total Files Processed: {}", processedFiles);
        LOGGER.info("Unchanged Files: {}", unchangedFiles);
        LOGGER.info("Added Files: {}", addedFiles);
        LOGGER.info("Removed Files: {}", removedFiles);
        LOGGER.info("Modified Files: {}", modifiedFiles);
        
        // Verification total
        LOGGER.info("Verification: {} (total) = {} (unchanged) + {} (added) + {} (removed) + {} (modified)",
            processedFiles, unchangedFiles, addedFiles, removedFiles, modifiedFiles);
        
        // Detailed File Changes
        LOGGER.info("===== Detailed File Changes =====");
        
        // Added Files
        if (!fileChangeDetails.getOrDefault("ADDED", Collections.emptyList()).isEmpty()) {
            LOGGER.info("Added Files:");
            fileChangeDetails.get("ADDED").stream()
                .sorted()
                .forEach(file -> LOGGER.info("  + {}", file));
        }
        
        // Removed Files
        if (!fileChangeDetails.getOrDefault("REMOVED", Collections.emptyList()).isEmpty()) {
            LOGGER.info("Removed Files:");
            fileChangeDetails.get("REMOVED").stream()
                .sorted()
                .forEach(file -> LOGGER.info("  - {}", file));
        }
        
        // Modified Files
        if (!fileChangeDetails.getOrDefault("MODIFIED", Collections.emptyList()).isEmpty()) {
            LOGGER.info("Modified Files:");
            fileChangeDetails.get("MODIFIED").stream()
                .sorted()
                .forEach(file -> LOGGER.info("  ~ {}", file));
        }
    }

    private boolean compareFile(Path oldPath, Path newPath, String relativePath, ComparisonResult result) {
        try {
            // Quick size comparison first
            long oldSize = Files.size(oldPath);
            long newSize = Files.size(newPath);
            
            if (oldSize != newSize) {
                String details = String.format("Size changed from %d to %d bytes (difference: %d bytes)", 
                    oldSize, newSize, (newSize - oldSize));
                result.addModifiedFile(relativePath, "SIZE_CHANGED", details);
                return true;
            }
            
            // Only do content comparison if sizes match
            byte[] oldContent = Files.readAllBytes(oldPath);
            byte[] newContent = Files.readAllBytes(newPath);
            
            if (!Arrays.equals(oldContent, newContent)) {
                result.addModifiedFile(relativePath, "CONTENT_MODIFIED", 
                    "Content changed while size remained same");
                return true;
            }
            
            return false;
        } catch (IOException e) {
            LOGGER.error("Error comparing files: {} and {}", oldPath, newPath, e);
            return false;
        }
    }

    private void compareTextFiles(Path oldPath, Path newPath, String relativePath, ComparisonResult result) throws IOException {
        try {
            // Use a more efficient diff algorithm
            Patch<String> patch = DiffUtils.diff(
                Files.readAllLines(oldPath), 
                Files.readAllLines(newPath)
            );
            
            List<AbstractDelta<String>> deltas = patch.getDeltas();
            
            if (!deltas.isEmpty()) {
                int addedLines = 0;
                int removedLines = 0;
                int modifiedLines = 0;
                
                for (AbstractDelta<String> delta : deltas) {
                    switch (delta.getType()) {
                        case INSERT:
                            addedLines += delta.getTarget().size();
                            break;
                        case DELETE:
                            removedLines += delta.getSource().size();
                            break;
                        case CHANGE:
                            modifiedLines += Math.max(delta.getSource().size(), delta.getTarget().size());
                            break;
                    }
                }
                
                String summary = String.format(
                    "Text file changes: +%d lines, -%d lines, ~%d lines modified", 
                    addedLines, removedLines, modifiedLines
                );
                
                result.addModifiedFile(relativePath, "TEXT_MODIFIED", summary);
            }
        } catch (Exception e) {
            LOGGER.error("Error generating diff for {}: {}", relativePath, e.getMessage());
            result.addModifiedFile(relativePath, "DIFF_ERROR", 
                "Error generating diff: " + e.getMessage());
        }
    }

    private void compareClassFiles(Path oldPath, Path newPath, String relativePath, ComparisonResult result) {
        LOGGER.info("Comparing class files: {} and {}", oldPath, newPath);
        try {
            byte[] oldContent = Files.readAllBytes(oldPath);
            byte[] newContent = Files.readAllBytes(newPath);
            
            if (!Arrays.equals(oldContent, newContent)) {
                String details = String.format("Class file modified (size: %d bytes -> %d bytes)", 
                    oldContent.length, newContent.length);
                LOGGER.info("Class file changed: {} - {}", relativePath, details);
                result.addModifiedFile(relativePath, "CLASS_MODIFIED", details);
            }
        } catch (Exception e) {
            LOGGER.warn("Error comparing class files: {} - {}", relativePath, e.getMessage());
            result.addModifiedFile(relativePath, "CLASS_COMPARISON_ERROR", 
                "Error comparing class file: " + e.getMessage());
        }
    }

    private void compareNbtFiles(Path oldPath, Path newPath, String relativePath, ComparisonResult result) {
        LOGGER.info("Comparing NBT files: {} and {}", oldPath, newPath);
        try {
            // Read NBT files using binary streams
            try (InputStream oldIn = new BufferedInputStream(Files.newInputStream(oldPath));
                 InputStream newIn = new BufferedInputStream(Files.newInputStream(newPath))) {
                
                // Compare NBT data as binary
                byte[] oldBuffer = oldIn.readAllBytes();
                byte[] newBuffer = newIn.readAllBytes();
                
                if (!Arrays.equals(oldBuffer, newBuffer)) {
                    result.addModifiedFile(relativePath, "NBT_MODIFIED", 
                        "NBT data changed");
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Error comparing NBT files: {} - {}", relativePath, e.getMessage());
            result.addModifiedFile(relativePath, "NBT_COMPARISON_ERROR", 
                "Error comparing NBT file: " + e.getMessage());
        }
    }

    @Cacheable(key = "'texture-' + #oldPath + '-' + #newPath")
    private double compareTextures(Path oldPath, Path newPath) throws IOException {
        BufferedImage oldImage = ImageIO.read(oldPath.toFile());
        BufferedImage newImage = ImageIO.read(newPath.toFile());

        if (oldImage.getWidth() != newImage.getWidth() || oldImage.getHeight() != newImage.getHeight()) {
            return 0.0;
        }

        long differences = 0;
        long totalPixels = oldImage.getWidth() * oldImage.getHeight();

        for (int x = 0; x < oldImage.getWidth(); x++) {
            for (int y = 0; y < oldImage.getHeight(); y++) {
                Color oldColor = new Color(oldImage.getRGB(x, y), true);
                Color newColor = new Color(newImage.getRGB(x, y), true);
                
                differences += calculateColorDifference(oldColor, newColor);
            }
        }

        return 1.0 - (double) differences / (totalPixels * 255 * 4); // 4 channels (RGBA)
    }

    private int calculateColorDifference(Color c1, Color c2) {
        return Math.abs(c1.getRed() - c2.getRed()) +
               Math.abs(c1.getGreen() - c2.getGreen()) +
               Math.abs(c1.getBlue() - c2.getBlue()) +
               Math.abs(c1.getAlpha() - c2.getAlpha());
    }

    private File findAndValidateDirectory(String version) {
        File directory = findVersionDirectory(version);
        LOGGER.debug("Checking directory for version {}: {}", version, 
            directory != null ? directory.getAbsolutePath() : "null");
        
        if (directory == null || !directory.exists()) {
            LOGGER.error("Directory not found for version: {}", version);
            throw new ValidationException("Invalid version directory: " + version);
        }
        
        if (!directory.isDirectory()) {
            LOGGER.error("Path exists but is not a directory: {}", directory.getAbsolutePath());
            throw new ValidationException("Invalid version directory: " + version);
        }
        
        File[] files = directory.listFiles();
        if (files == null || files.length == 0) {
            LOGGER.error("Directory is empty: {}", directory.getAbsolutePath());
            throw new ValidationException("Empty version directory: " + version);
        }
        
        LOGGER.debug("Directory validated successfully: {} (contains {} files)", 
            directory.getAbsolutePath(), files.length);
        
        return directory;
    }

    // Additional utility classes

    @Component
    public static class FileCache {
        private final Map<Path, CacheEntry> cache = new ConcurrentHashMap<>();
        
        public String getFileHash(Path path) throws IOException {
            CacheEntry entry = cache.get(path);
            if (entry != null && entry.isValid(path)) {
                return entry.hash;
            }
            
            String hash = calculateFileHash(path);
            cache.put(path, new CacheEntry(hash, Files.getLastModifiedTime(path).toMillis()));
            return hash;
        }
        
        private String calculateFileHash(Path path) throws IOException {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(Files.readAllBytes(path));
                return Base64.getEncoder().encodeToString(hash);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("SHA-256 algorithm not found", e);
            }
        }
        
        private static class CacheEntry implements Serializable {
            private static final long serialVersionUID = 1L;
            private String hash;
            private long lastModified;
            
            CacheEntry(String hash, long lastModified) {
                this.hash = hash;
                this.lastModified = lastModified;
            }
            
            boolean isValid(Path path) throws IOException {
                return Files.getLastModifiedTime(path).toMillis() == lastModified;
            }
        }
    }

    @Component
    public static class ValidationService {
        public boolean isValidVersion(String version) {
            if (version == null || version.trim().isEmpty()) {
                LOGGER.error("Version string is null or empty");
                return false;
            }
            
            boolean isValid = version.matches("^[0-9]+(\\.[0-9]+)*$");
            LOGGER.debug("Version validation: {} -> {}", version, isValid);
            return isValid;  
        }
    }

    @Component
    public static class DiffGenerator {
        public String generateTextDiff(Path oldPath, Path newPath) throws IOException {
            List<String> oldLines = Files.readAllLines(oldPath);
            List<String> newLines = Files.readAllLines(newPath);
            
            try {
                DiffRowGenerator generator = DiffRowGenerator.create()
                    .showInlineDiffs(true)
                    .inlineDiffByWord(true)
                    .oldTag(f -> "[-")
                    .newTag(f -> "{+")
                    .oldTag(f -> "-]")
                    .newTag(f -> "+}")
                    .build();
                
                List<DiffRow> rows = generator.generateDiffRows(oldLines, newLines);
                return rows.stream()
                    .map(DiffRow::toString)
                    .collect(Collectors.joining("\n"));
            } catch (Exception e) {
                LOGGER.error("Error generating diff: {}", e.getMessage());
                return "Error generating diff: " + e.getMessage();
            }
        }
        
        public byte[] generateBinaryDiff(Path oldPath, Path newPath) throws IOException {
            try (InputStream oldStream = Files.newInputStream(oldPath);
                 InputStream newStream = Files.newInputStream(newPath);
                 ByteArrayOutputStream diffOutput = new ByteArrayOutputStream()) {
                
                byte[] oldBuffer = new byte[CHUNK_SIZE];
                byte[] newBuffer = new byte[CHUNK_SIZE];
                int position = 0;
                
                try (GZIPOutputStream gzipOut = new GZIPOutputStream(diffOutput)) {
                    while (true) {
                        int oldRead = oldStream.read(oldBuffer);
                        int newRead = newStream.read(newBuffer);
                        
                        if (oldRead == -1 && newRead == -1) break;
                        
                        if (oldRead != newRead || !Arrays.equals(
                                oldBuffer, 0, Math.max(oldRead, 0),
                                newBuffer, 0, Math.max(newRead, 0))) {
                            // Write diff information
                            writeDiffChunk(gzipOut, position,
                                    oldBuffer, 0, Math.max(oldRead, 0),
                                    newBuffer, 0, Math.max(newRead, 0));
                        }
                        position += Math.max(oldRead, 0);
                        if (oldRead == -1 || newRead == -1) break;
                    }
                }
                return diffOutput.toByteArray();
            }
        }

        private void writeDiffChunk(GZIPOutputStream out, int position,
                                  byte[] oldData, int oldOffset, int oldLength,
                                  byte[] newData, int newOffset, int newLength) throws IOException {
            // Write position and lengths
            writeInt(out, position);
            writeInt(out, oldLength);
            writeInt(out, newLength);
            
            // Write actual data
            if (oldLength > 0) out.write(oldData, oldOffset, oldLength);
            if (newLength > 0) out.write(newData, newOffset, newLength);
        }

        private void writeInt(OutputStream out, int value) throws IOException {
            out.write((value >>> 24) & 0xFF);
            out.write((value >>> 16) & 0xFF);
            out.write((value >>> 8) & 0xFF);
            out.write(value & 0xFF);
        }
    }

    public static class ComparisonResult {
        private final Map<String, FileModification> modifications = new ConcurrentHashMap<>();
        private final Set<String> added = ConcurrentHashMap.newKeySet();
        private final Set<String> removed = ConcurrentHashMap.newKeySet();
        private int unchangedFiles = 0;

        public Set<String> getAddedFiles() {
            return Collections.unmodifiableSet(added);
        }

        public Set<String> getRemovedFiles() {
            return Collections.unmodifiableSet(removed);
        }

        public Map<String, FileModification> getModifications() {
            return Collections.unmodifiableMap(modifications);
        }

        public void addModifiedFile(String path, String type, String details) {
            modifications.put(path, new FileModification(type, details));
        }

        public void setUnchangedFiles(int count) {
            this.unchangedFiles = count;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("===== Comparison Results =====\n\n");

            // Summary with unchanged files
            int totalFiles = added.size() + removed.size() + modifications.size() + unchangedFiles;
            sb.append(String.format("Total Files Processed: %d\n", totalFiles));
            sb.append(String.format("Unchanged Files: %d\n", unchangedFiles));
            sb.append(String.format("Changed Files: %d\n", added.size() + removed.size() + modifications.size()));
            sb.append(String.format("  - Added Files: %d\n", added.size()));
            sb.append(String.format("  - Removed Files: %d\n", removed.size()));
            sb.append(String.format("  - Modified Files: %d\n\n", modifications.size()));

            // Group files by type
            Map<String, List<String>> addedByType = groupFilesByType(added);
            Map<String, List<String>> removedByType = groupFilesByType(removed);
            Map<String, List<Entry<String, FileModification>>> modifiedByType = groupModificationsByType();

            // Added Files
            if (!added.isEmpty()) {
                sb.append("=== Added Files ===\n");
                addedByType.forEach((type, files) -> {
                    if (!files.isEmpty()) {
                        sb.append(String.format("\n%s:\n", type));
                        files.stream()
                            .sorted()
                            .forEach(file -> sb.append(String.format("  + %s\n", file)));
                    }
                });
                sb.append("\n");
            }

            // Removed Files - Ensure all removed files are shown
            if (!removed.isEmpty()) {
                sb.append("=== Removed Files ===\n");
                removedByType.forEach((type, files) -> {
                    if (!files.isEmpty()) {
                        sb.append(String.format("\n%s:\n", type));
                        files.stream()
                            .sorted()
                            .forEach(file -> sb.append(String.format("  - %s\n", file)));
                    }
                });
                sb.append("\n");
            }

            // Modified Files with better diff formatting
            if (!modifications.isEmpty()) {
                sb.append("=== Modified Files ===\n");
                List<Map.Entry<String, FileModification>> sortedMods = 
                    new ArrayList<>(modifications.entrySet());
                Collections.sort(sortedMods, Map.Entry.comparingByKey());
                
                for (Map.Entry<String, FileModification> entry : sortedMods) {
                    sb.append(String.format("\n  ~ %s\n", entry.getKey()));
                    FileModification mod = entry.getValue();
                    
                    if (mod.getType().equals("CONTENT_CHANGED")) {
                        sb.append("    Type: Content Changed\n");
                        String[] lines = mod.getDetails().split("\n");
                        for (String line : lines) {
                            sb.append("    ").append(line).append("\n");
                        }
                    } else {
                        sb.append(String.format("    Type: %s\n", mod.getType()));
                        sb.append(String.format("    %s\n", mod.getDetails()));
                    }
                }
            }

            return sb.toString();
        }

        private String truncateOutput(Set<String> files, int limit) {
            StringBuilder sb = new StringBuilder();
            int count = 0;
            int total = files.size();
            
            List<String> sortedFiles = new ArrayList<>(files);
            Collections.sort(sortedFiles);
            
            for (String file : sortedFiles) {
                if (count < limit) {
                    sb.append(String.format("  + %s\n", file));
                }
                count++;
            }
            
            if (count > limit) {
                sb.append(String.format("\n  ... and %d more files\n", total - limit));
            }
            
            return sb.toString();
        }

        private String formatModification(FileModification mod) {
            if (mod.getType().equals("SIZE_CHANGED")) {
                return String.format("    Size changed: %s\n", mod.getDetails());
            }
            return String.format("    %s: %s\n", mod.getType(), mod.getDetails());
        }

        private Map<String, List<String>> groupFilesByType(Set<String> files) {
            return files.stream()
                .collect(Collectors.groupingBy(
                    this::getFileCategory,
                    TreeMap::new,
                    Collectors.mapping(
                        file -> file,
                        Collectors.toList()
                    )
                ));
        }

        private Map<String, List<Entry<String, FileModification>>> groupModificationsByType() {
            return modifications.entrySet().stream()
                .collect(Collectors.groupingBy(
                    entry -> getFileCategory(entry.getKey()),
                    TreeMap::new,
                    Collectors.toList()
                ));
        }

        private String getFileCategory(String path) {
            if (path.endsWith(".class")) {
                if (path.startsWith("net/minecraft/")) {
                    return "Minecraft Classes";
                }
                return "Other Classes";
            }
            if (path.startsWith("assets/")) return "Resource Files";
            if (path.endsWith(".json")) return "Configuration Files";
            return "Other Files";
        }

        public void addAddedFile(String path) {
            added.add(path);
        }

        public void addRemovedFile(String path) {
            removed.add(path);
        }
    }

    public static class FileModification implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String type;
        private final String details;
        
        public FileModification(String type, String details) {
            this.type = type;
            this.details = details;
        }
        
        public String getType() { return type; }
        public String getDetails() { return details; }
    }

    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }
    }

    public static class ComparisonException extends RuntimeException {
        public ComparisonException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public class FileComparison {
        private String path;
        private String diffType;
        private String details;
        
        // Add getters/setters as needed
    }

    public static class ComparisonState implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private Map<String, String> fileHashes;
        private long timestamp;
        
        public ComparisonState() {
            this.fileHashes = new HashMap<>();
            this.timestamp = System.currentTimeMillis();
        }
        
        public void setFileHashes(Map<String, String> fileHashes) {
            this.fileHashes = new HashMap<>(fileHashes);
        }
        
        public Map<String, String> getFileHashes() {
            return new HashMap<>(fileHashes);
        }
        
        public long getTimestamp() {
            return timestamp;
        }
        
        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
    }

    private Optional<ComparisonState> loadPreviousComparisonState(String oldVersion, String newVersion) {
        Path statePath = getStateFilePath(oldVersion, newVersion);
        if (!Files.exists(statePath)) {
            return Optional.empty();
        }
        
        try (ObjectInputStream ois = new ObjectInputStream(
                new GZIPInputStream(Files.newInputStream(statePath)))) {
            ComparisonState state = (ComparisonState) ois.readObject();
            // Validate state timestamp
            if (System.currentTimeMillis() - state.getTimestamp() > TimeUnit.DAYS.toMillis(1)) {
                return Optional.empty(); // State too old
            }
            return Optional.of(state);
        } catch (Exception e) {
            LOGGER.warn("Failed to load comparison state: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void saveComparisonState(String oldVersion, String newVersion, ComparisonResult result) {
        ComparisonState state = new ComparisonState();
        Map<String, String> fileHashes = new HashMap<>();
        
        try {
            // Save hashes for both versions
            File oldDir = findVersionDirectory(oldVersion);
            File newDir = findVersionDirectory(newVersion);
            
            walkDirectory(oldDir.toPath(), path -> {
                try {
                    String relativePath = oldDir.toPath().relativize(path).toString();
                    fileHashes.put(relativePath, fileCache.getFileHash(path));
                } catch (IOException e) {
                    LOGGER.warn("Failed to hash file: {}", path, e);
                }
            });
            
            walkDirectory(newDir.toPath(), path -> {
                try {
                    String relativePath = newDir.toPath().relativize(path).toString();
                    fileHashes.put(relativePath, fileCache.getFileHash(path));
                } catch (IOException e) {
                    LOGGER.warn("Failed to hash file: {}", path, e);
                }
            });
            
            state.setFileHashes(fileHashes);
            state.setTimestamp(System.currentTimeMillis());
            
            // Save state to file
            Path statePath = getStateFilePath(oldVersion, newVersion);
            Files.createDirectories(statePath.getParent());
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new GZIPOutputStream(Files.newOutputStream(statePath)))) {
                oos.writeObject(state);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save comparison state", e);
        }
    }

    private Path getStateFilePath(String oldVersion, String newVersion) {
        return Paths.get(CACHE_DIR, "state", oldVersion + "_" + newVersion + ".state");
    }

    private File findVersionDirectory(String version) {
        return new File(DECOMPILED_DIR, version);
    }

    private Set<Path> findChangedFilesSinceLastComparison(File oldDir, File newDir, ComparisonState previousState) throws IOException {
        Set<Path> changedFiles = new HashSet<>();
        Map<String, String> currentHashes = new HashMap<>();
        
        // Walk through both directories
        walkDirectory(oldDir.toPath(), path -> {
            String relativePath = oldDir.toPath().relativize(path).toString();
            try {
                String hash = fileCache.getFileHash(path);
                currentHashes.put(relativePath, hash);
                
                String previousHash = previousState.getFileHashes().get(relativePath);
                if (previousHash == null || !previousHash.equals(hash)) {
                    changedFiles.add(oldDir.toPath().relativize(path));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        
        walkDirectory(newDir.toPath(), path -> {
            String relativePath = newDir.toPath().relativize(path).toString();
            if (!currentHashes.containsKey(relativePath)) {
                changedFiles.add(newDir.toPath().relativize(path));
            }
        });
        
        return changedFiles;
    }

    private void walkDirectory(Path dir, Consumer<Path> fileConsumer) {
        try (Stream<Path> pathStream = Files.walk(dir)) {
            pathStream
                .filter(Files::isRegularFile)
                .forEach(fileConsumer);
        } catch (IOException e) {
            LOGGER.error("Error walking directory: {}", dir, e);
            throw new ComparisonException("Failed to walk directory", e);
        }
    }

    private String determineChangeType(long oldSize, long newSize) {
        if (oldSize == newSize) return "Content changed";
        else if (oldSize < newSize) return "Size increased";
        else return "Size decreased";
    }

    public static class FileCategory {
        private static final Map<String, String> CATEGORY_PATTERNS = new HashMap<>();
        static {
            CATEGORY_PATTERNS.put("net/minecraft/client/", "Client Classes");
            CATEGORY_PATTERNS.put("net/minecraft/server/", "Server Classes");
            CATEGORY_PATTERNS.put("net/minecraft/world/", "World Classes");
            CATEGORY_PATTERNS.put("assets/minecraft/textures/", "Textures");
            CATEGORY_PATTERNS.put("assets/minecraft/models/", "Models");
            CATEGORY_PATTERNS.put("assets/minecraft/sounds/", "Sounds");
            CATEGORY_PATTERNS.put("assets/minecraft/blockstates/", "Blockstates");
            CATEGORY_PATTERNS.put("assets/minecraft/lang/", "Languages");
            CATEGORY_PATTERNS.put("assets/minecraft/shaders/", "Shaders");
            CATEGORY_PATTERNS.put("data/minecraft/loot_tables/", "Loot Tables");
            CATEGORY_PATTERNS.put("data/minecraft/recipes/", "Recipes");
            CATEGORY_PATTERNS.put("data/minecraft/advancements/", "Advancements");
            CATEGORY_PATTERNS.put("data/minecraft/tags/", "Tags");
        }

        public static String categorizeFile(String path) {
            // Check for specific file types first
            if (path.endsWith(".class")) {
                if (path.contains("net/minecraft/")) {
                    return findDetailedCategory(path);
                }
                return "Other Classes";
            }
            
            // Check for resource types
            for (Map.Entry<String, String> category : CATEGORY_PATTERNS.entrySet()) {
                if (path.startsWith(category.getKey())) {
                    return category.getValue();
                }
            }
            
            // Check file extensions
            if (path.endsWith(".json")) return "Configuration Files";
            if (path.endsWith(".png")) return "Textures";
            if (path.endsWith(".ogg")) return "Sounds";
            if (path.endsWith(".mcmeta")) return "Metadata";
            
            return "Other Files";
        }

        private static String findDetailedCategory(String path) {
            for (Map.Entry<String, String> category : CATEGORY_PATTERNS.entrySet()) {
                if (path.contains(category.getKey())) {
                    return category.getValue();
                }
            }
            return "Minecraft Classes";
        }
    }

    @Component
    public class FileHashCache {
        private static final String CACHE_FILE = "file_hash_cache.dat";
        private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
        private final Set<String> unchangedDirectories = ConcurrentHashMap.newKeySet();
        
        @Data
        public class CacheEntry implements Serializable {
            private static final long serialVersionUID = 1L;
            private String hash;
            private long lastModified;
            private long fileSize;
            private long lastChecked;
            private int hitCount;
            
            public CacheEntry() {
            }
            
            public CacheEntry(String hash, long lastModified, long fileSize, long lastChecked, int hitCount) {
                this.hash = hash;
                this.lastModified = lastModified;
                this.fileSize = fileSize;
                this.lastChecked = lastChecked;
                this.hitCount = hitCount;
            }
            
            // Getters and Setters
            public String getHash() { return hash; }
            public void setHash(String hash) { this.hash = hash; }
            
            public long getLastModified() { return lastModified; }
            public void setLastModified(long lastModified) { this.lastModified = lastModified; }
            
            public long getFileSize() { return fileSize; }
            public void setFileSize(long fileSize) { this.fileSize = fileSize; }
            
            public long getLastChecked() { return lastChecked; }
            public void setLastChecked(long lastChecked) { this.lastChecked = lastChecked; }
            
            public int getHitCount() { return hitCount; }
            public void setHitCount(int hitCount) { this.hitCount = hitCount; }
            
            public boolean isValid(Path path) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                    return attrs.lastModifiedTime().toMillis() == lastModified 
                        && attrs.size() == fileSize;
                } catch (IOException e) {
                    return false;
                }
            }
        }

        public void initialize() {
            loadCache();
            scheduleCleanup();
        }

        @Scheduled(fixedRate = 24 * 60 * 60 * 1000) // Daily cleanup
        public void scheduleCleanup() {
            cleanupCache();
        }

        public Optional<String> getHash(Path path) {
            String key = path.toString();
            CacheEntry entry = cache.get(key);
            
            if (entry != null && entry.isValid(path)) {
                entry.hitCount++;
                return Optional.of(entry.hash);
            }
            
            return Optional.empty();
        }

        public void putHash(Path path, String hash) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                CacheEntry entry = new CacheEntry(
                    hash,
                    attrs.lastModifiedTime().toMillis(),
                    attrs.size(),
                    System.currentTimeMillis(),
                    0
                );
                cache.put(path.toString(), entry);
            } catch (IOException e) {
                LOGGER.error("Error caching hash for: {}", path, e);
            }
        }

        public boolean isDirectoryUnchanged(Path dir) {
            return unchangedDirectories.contains(dir.toString());
        }

        public void markDirectoryUnchanged(Path dir) {
            unchangedDirectories.add(dir.toString());
        }

        private void cleanupCache() {
            long now = System.currentTimeMillis();
            long maxAge = TimeUnit.DAYS.toMillis(7); // Keep entries for 7 days
            
            cache.entrySet().removeIf(entry -> {
                CacheEntry cacheEntry = entry.getValue();
                // Remove if old and rarely used
                return (now - cacheEntry.lastChecked > maxAge && cacheEntry.hitCount < 3);
            });
            
            saveCache();
        }

        private void loadCache() {
            Path cacheFile = Paths.get(CACHE_FILE);
            if (Files.exists(cacheFile)) {
                try (ObjectInputStream ois = new ObjectInputStream(
                        new BufferedInputStream(Files.newInputStream(cacheFile)))) {
                    @SuppressWarnings("unchecked")
                    Map<String, CacheEntry> loaded = (Map<String, CacheEntry>) ois.readObject();
                    cache.putAll(loaded);
                } catch (Exception e) {
                    LOGGER.error("Error loading cache", e);
                }
            }
        }

        private void saveCache() {
            Path cacheFile = Paths.get(CACHE_FILE);
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(cacheFile)))) {
                oos.writeObject(new HashMap<>(cache));
            } catch (IOException e) {
                LOGGER.error("Error saving cache", e);
            }
        }
    }

    private void compareFilesWithCache(
            File oldVersionDir, 
            File newVersionDir, 
            Map<String, String> oldFileHashes, 
            Map<String, String> newFileHashes, 
            ComparisonResult result, 
            AtomicInteger unchangedCount) {
        
        oldFileHashes.entrySet().parallelStream().forEach(entry -> {
            String relativePath = entry.getKey();
            String oldHash = entry.getValue();
            String newHash = newFileHashes.remove(relativePath);

            if (newHash == null) {
                result.addRemovedFile(relativePath);
            } else if (!oldHash.equals(newHash)) {
                Path oldPath = oldVersionDir.toPath().resolve(relativePath);
                Path newPath = newVersionDir.toPath().resolve(relativePath);
                compareFileDetails(oldPath, newPath, relativePath, result);
            } else {
                unchangedCount.incrementAndGet();
            }
        });

        newFileHashes.keySet().forEach(path -> result.addAddedFile(path));
    }
}