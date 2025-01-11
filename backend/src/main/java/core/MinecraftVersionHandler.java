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
                compareFile(oldVersionDir.toPath(), newVersionDir.toPath(), relativePath, result);
            }
        } catch (Exception e) {
            LOGGER.error("Error during incremental comparison", e);
            throw new ComparisonException("Failed to perform incremental comparison", e);
        }
    }

    private void performFullComparison(File oldVersionDir, File newVersionDir, ComparisonResult result) throws IOException {
        LOGGER.info("Starting full comparison between versions");
        
        // Detailed logging setup
        long startTime = System.currentTimeMillis();
        AtomicInteger processedFiles = new AtomicInteger(0);
        AtomicInteger addedFilesCount = new AtomicInteger(0);
        AtomicInteger removedFilesCount = new AtomicInteger(0);
        AtomicInteger modifiedFilesCount = new AtomicInteger(0);
        AtomicInteger unchangedFilesCount = new AtomicInteger(0);
        
        // Logging collector for detailed file changes
        ConcurrentMap<String, List<String>> fileChangeDetails = new ConcurrentHashMap<>();

        // Get all files from both directories and sort them alphabetically
        List<Path> oldFiles = Files.walk(oldVersionDir.toPath())
            .filter(Files::isRegularFile)
            .sorted()
            .collect(Collectors.toList());

        List<Path> newFiles = Files.walk(newVersionDir.toPath())
            .filter(Files::isRegularFile)
            .sorted()
            .collect(Collectors.toList());

        // Process old files
        oldFiles.parallelStream().forEach(path -> {
            Path relativePath = oldVersionDir.toPath().relativize(path);
            processedFiles.incrementAndGet();
            
            try {
                Path correspondingNewPath = newVersionDir.toPath().resolve(relativePath);
                
                if (!Files.exists(correspondingNewPath)) {
                    // File removed
                    result.addRemovedFile(relativePath.toString());
                    removedFilesCount.incrementAndGet();
                    
                    fileChangeDetails.computeIfAbsent("REMOVED", k -> new CopyOnWriteArrayList<>())
                        .add(relativePath.toString());
                } else {
                    // Compare files
                    try {
                        boolean wasModified = compareFile(oldVersionDir.toPath(), newVersionDir.toPath(), relativePath, result);
                        if (wasModified) {
                            modifiedFilesCount.incrementAndGet();
                            // Log modified file details
                            long oldSize = Files.size(oldVersionDir.toPath().resolve(relativePath));
                            long newSize = Files.size(newVersionDir.toPath().resolve(relativePath));
                            String changeType = determineChangeType(oldSize, newSize);
                            
                            fileChangeDetails.computeIfAbsent("MODIFIED", k -> new CopyOnWriteArrayList<>())
                                .add(String.format("%s (%s, Old size: %d bytes, New size: %d bytes)", 
                                    relativePath, changeType, oldSize, newSize));
                        } else {
                            // File unchanged
                            unchangedFilesCount.incrementAndGet();
                            fileChangeDetails.computeIfAbsent("UNCHANGED", k -> new CopyOnWriteArrayList<>())
                                .add(relativePath.toString());
                        }
                    } catch (IOException e) {
                        LOGGER.error("Error comparing files for path {}", relativePath, e);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Error processing old file {}", relativePath, e);
            }
        });

        // Check for added files
        newFiles.parallelStream().forEach(path -> {
            Path relativePath = newVersionDir.toPath().relativize(path);
            Path correspondingOldPath = oldVersionDir.toPath().resolve(relativePath);
            
            try {
                if (!Files.exists(correspondingOldPath)) {
                    result.addAddedFile(relativePath.toString());
                    addedFilesCount.incrementAndGet();
                    
                    try {
                        long fileSize = Files.size(path);
                        String fileType = determineFileType(relativePath.toString());
                        
                        fileChangeDetails.computeIfAbsent("ADDED", k -> new CopyOnWriteArrayList<>())
                            .add(String.format("%s (Size: %d bytes, Type: %s)", 
                                relativePath, fileSize, fileType));
                    } catch (IOException e) {
                        LOGGER.warn("Could not get details for added file {}", relativePath);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Error checking added file {}", relativePath, e);
            }
        });

        // Generate comprehensive comparison report with unchanged files
        generateComparisonReport(
            startTime, 
            processedFiles.get(), 
            addedFilesCount.get(), 
            removedFilesCount.get(), 
            modifiedFilesCount.get(),
            unchangedFilesCount.get(),
            fileChangeDetails
        );
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
        
        // Optional: Log some unchanged files for verification (limit to first 10)
        if (!fileChangeDetails.getOrDefault("UNCHANGED", Collections.emptyList()).isEmpty()) {
            LOGGER.debug("Sample of Unchanged Files (first 10):");
            fileChangeDetails.get("UNCHANGED").stream()
                .limit(10)
                .forEach(file -> LOGGER.debug("  = {}", file));
        }
        
        // Rest of the detailed file changes logging...
        // ... (keep existing logging for added/removed/modified files)
    }

    private boolean compareFile(Path oldBasePath, Path newBasePath, Path relativePath, ComparisonResult result) throws IOException {
        Path oldPath = oldBasePath.resolve(relativePath);
        Path newPath = newBasePath.resolve(relativePath);
        boolean wasModified = false;

        try {
            // Compare file sizes first (quick check)
            long oldSize = Files.size(oldPath);
            long newSize = Files.size(newPath);
            
            if (oldSize != newSize) {
                String sizeChange = String.format("Size changed from %d to %d bytes (difference: %d bytes)", 
                    oldSize, newSize, (newSize - oldSize));
                LOGGER.debug("File size change detected in {}: {}", relativePath, sizeChange);
                result.addModifiedFile(relativePath.toString(), "SIZE_CHANGED", sizeChange);
                return true;
            }
            
            // Compare content
            String oldHash = fileCache.getFileHash(oldPath);
            String newHash = fileCache.getFileHash(newPath);
            
            if (!oldHash.equals(newHash)) {
                wasModified = true;
                // Optimize file comparison based on file type
                if (relativePath.toString().endsWith(".class")) {
                    compareClassFiles(oldPath, newPath, relativePath.toString(), result);
                } else if (relativePath.toString().endsWith(".nbt")) {
                    compareNbtFiles(oldPath, newPath, relativePath.toString(), result);
                } else if (isTextFile(relativePath.toString())) {
                    compareTextFiles(oldPath, newPath, relativePath.toString(), result);
                } else {
                    String details = String.format("Binary content changed (size: %d bytes)", oldSize);
                    LOGGER.debug("Binary file modified: {}", relativePath);
                    result.addModifiedFile(relativePath.toString(), "BINARY_MODIFIED", details);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error comparing file: {} - {}", relativePath, e.getMessage());
            result.addModifiedFile(relativePath.toString(), "COMPARISON_ERROR", 
                "Error during comparison: " + e.getMessage());
            return true;
        }
        
        return wasModified;
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

    private boolean isTextFile(String path) {
        return path.endsWith(".java") || 
               path.endsWith(".txt") || 
               path.endsWith(".json") || 
               path.endsWith(".yml") || 
               path.endsWith(".properties") || 
               path.endsWith(".mcmeta") || 
               path.endsWith(".xml") || 
               path.endsWith(".cfg") ||
               path.endsWith(".JAVA") || 
               path.endsWith(".TXT") || 
               path.endsWith(".JSON") || 
               path.endsWith(".YML") || 
               path.endsWith(".PROPERTIES") || 
               path.endsWith(".MCMETA") || 
               path.endsWith(".XML") || 
               path.endsWith(".CFG");
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
        
        private static class CacheEntry {
            final String hash;
            final long lastModified;
            
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
        private final Map<String, FileModification> modifications = new HashMap<>();
        private final Set<String> added = new HashSet<>();
        private final Set<String> removed = new HashSet<>();
        
        public void addModifiedFile(String path, String type, String details) {
            LOGGER.info("Adding modified file: {} ({})", path, type);
            modifications.put(path, new FileModification(type, details));
        }
        
        public void addAddedFile(String path) {
            LOGGER.info("Adding new file: {}", path);
            added.add(path);
        }
        
        public void addRemovedFile(String path) {
            LOGGER.info("Adding removed file: {}", path);
            removed.add(path);
        }
        
        public Map<String, FileModification> getModifications() {
            return new HashMap<>(modifications);
        }
        
        public Set<String> getAddedFiles() {
            return new HashSet<>(added);
        }
        
        public Set<String> getRemovedFiles() {
            return new HashSet<>(removed);
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Comparison Results:\n");
            sb.append(String.format("Added files: %d\n", added.size()));
            sb.append(String.format("Removed files: %d\n", removed.size()));
            sb.append(String.format("Modified files: %d\n", modifications.size()));
            
            if (!modifications.isEmpty()) {
                sb.append("\nModified Files Details:\n");
                modifications.forEach((path, mod) -> {
                    sb.append(String.format("- %s\n", path));
                    sb.append(String.format("  Type: %s\n", mod.getType()));
                    sb.append(String.format("  Changes: %s\n", mod.getDetails()));
                });
            }
            
            return sb.toString();
        }
    }

    public static class FileModification {
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
}