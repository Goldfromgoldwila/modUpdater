package core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Color;
import net.kyori.adventure.nbt.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Patch;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.text.DiffRow;
import com.github.difflib.text.DiffRowGenerator;
import java.util.stream.Stream;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.Serializable;

@Component
@Validated
@CacheConfig(cacheNames = {"versionComparisons"})
public class MinecraftVersionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftVersionHandler.class);
    private static final String DECOMPILED_DIR = "versions";
    private static final String ASSETS_DIR = "assets";
    private static final String NBT_DIR = "data";
    private static final String CACHE_DIR = "cache";
    private static final int LARGE_FILE_THRESHOLD = 10 * 1024 * 1024; // 10MB
    private static final int CHUNK_SIZE = 1024 * 1024; // 1MB chunks for large files

    private final Map<String, FileComparison> fileComparisons = new ConcurrentHashMap<>();
    private final Set<String> addedFiles = ConcurrentHashMap.newKeySet();
    private final Set<String> removedFiles = ConcurrentHashMap.newKeySet();
    private final Set<String> modifiedFiles = ConcurrentHashMap.newKeySet();
    private final FileCache fileCache;
    private final ValidationService validationService;
    private final DiffGenerator diffGenerator;

    @Autowired
    public MinecraftVersionHandler(
            FileCache fileCache,
            ValidationService validationService,
            DiffGenerator diffGenerator) {
        this.fileCache = fileCache;
        this.validationService = validationService;
        this.diffGenerator = diffGenerator;
    }

    @Cacheable(key = "#cleanVersion + '-' + #mcVersion")
    public ComparisonResult compareMinecraftVersions(
            @NotBlank @Pattern(regexp = "^[0-9.]+$") String cleanVersion,
            @NotBlank @Pattern(regexp = "^[0-9.]+$") String mcVersion) {
        validateVersions(cleanVersion, mcVersion);
        ComparisonResult result = new ComparisonResult();
        
        try {
            File oldVersionDir = findAndValidateDirectory(cleanVersion);
            File newVersionDir = findAndValidateDirectory(mcVersion);

            LOGGER.info("Starting comparison between versions: {} and {}", cleanVersion, mcVersion);
            long startTime = System.currentTimeMillis();

            // Load previous comparison state if exists
            Optional<ComparisonState> previousState = loadPreviousComparisonState(cleanVersion, mcVersion);
            
            // Perform incremental comparison
            if (previousState.isPresent()) {
                performIncrementalComparison(oldVersionDir, newVersionDir, previousState.get(), result);
            } else {
                performFullComparison(oldVersionDir, newVersionDir, result);
            }

            // Save current comparison state
            saveComparisonState(cleanVersion, mcVersion, result);

            long endTime = System.currentTimeMillis();
            LOGGER.info("Comparison completed in {} ms", endTime - startTime);
            
            return result;
        } catch (ValidationException e) {
            LOGGER.error("Validation error: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            LOGGER.error("Error during comparison: {}", e.getMessage(), e);
            throw new ComparisonException("Failed to compare versions", e);
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

    private void performIncrementalComparison(File oldDir, File newDir, ComparisonState previousState, ComparisonResult result) {
        try {
            Set<Path> changedFiles = findChangedFilesSinceLastComparison(oldDir, newDir, previousState);
            for (Path changedFile : changedFiles) {
                compareFile(oldDir.toPath(), newDir.toPath(), changedFile, result);
            }
        } catch (IOException e) {
            LOGGER.error("Error during incremental comparison: {}", e.getMessage());
            throw new ComparisonException("Incremental comparison failed", e);
        }
    }

    private void compareFile(Path oldBasePath, Path newBasePath, Path relativePath, ComparisonResult result) throws IOException {
        Path oldPath = oldBasePath.resolve(relativePath);
        Path newPath = newBasePath.resolve(relativePath);

        if (!Files.exists(oldPath) && Files.exists(newPath)) {
            result.addAddedFile(relativePath.toString());
        } else if (Files.exists(oldPath) && !Files.exists(newPath)) {
            result.addRemovedFile(relativePath.toString());
        } else if (Files.exists(oldPath) && Files.exists(newPath)) {
            compareSingleFile(oldPath, newPath, relativePath.toString(), result);
        }
    }

    private void compareSingleFile(Path oldPath, Path newPath, String relativePath, ComparisonResult result) throws IOException {
        // Check file size first
        long oldSize = Files.size(oldPath);
        long newSize = Files.size(newPath);

        if (oldSize > LARGE_FILE_THRESHOLD || newSize > LARGE_FILE_THRESHOLD) {
            compareLargeFiles(oldPath, newPath, relativePath, result);
        } else {
            compareRegularFiles(oldPath, newPath, relativePath, result);
        }
    }

    private void compareLargeFiles(Path oldPath, Path newPath, String relativePath, ComparisonResult result) throws IOException {
        try (InputStream oldIs = new BufferedInputStream(Files.newInputStream(oldPath));
             InputStream newIs = new BufferedInputStream(Files.newInputStream(newPath))) {
            
            byte[] oldBuffer = new byte[CHUNK_SIZE];
            byte[] newBuffer = new byte[CHUNK_SIZE];
            boolean different = false;
            long position = 0;

            while (true) {
                int oldRead = oldIs.read(oldBuffer);
                int newRead = newIs.read(newBuffer);

                if (oldRead == -1 && newRead == -1) break;
                if (oldRead != newRead) {
                    different = true;
                    break;
                }
                if (oldRead == -1 || newRead == -1) break;

                if (!Arrays.equals(oldBuffer, 0, oldRead, newBuffer, 0, newRead)) {
                    different = true;
                    break;
                }
                position += oldRead;
            }

            if (different) {
                result.addModifiedFile(relativePath, "CONTENT_MODIFIED", 
                    String.format("Files differ at position: %d", position));
            }
        }
    }

    private void compareRegularFiles(Path oldPath, Path newPath, String relativePath, ComparisonResult result) throws IOException {
        // Get cached hashes if available
        String oldHash = fileCache.getFileHash(oldPath);
        String newHash = fileCache.getFileHash(newPath);

        if (!oldHash.equals(newHash)) {
            // Generate detailed diff for text files
            if (isTextFile(relativePath)) {
                String diff = diffGenerator.generateTextDiff(oldPath, newPath);
                result.addModifiedFile(relativePath, "TEXT_MODIFIED", diff);
            } else {
                // Generate binary diff for binary files
                byte[] binaryDiff = diffGenerator.generateBinaryDiff(oldPath, newPath);
                result.addBinaryDiff(relativePath, binaryDiff);
            }
        }
    }

    private boolean isTextFile(String path) {
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
        return Arrays.asList("txt", "json", "yml", "properties", "mcmeta").contains(extension);
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
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            throw new ValidationException("Invalid version directory: " + version);
        }
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
            return version != null && version.matches("^[0-9]+(\\.[0-9]+)*$");
        }
    }

    @Component
    public static class DiffGenerator {
        public String generateTextDiff(Path oldPath, Path newPath) throws IOException {
            List<String> oldLines = Files.readAllLines(oldPath);
            List<String> newLines = Files.readAllLines(newPath);
            
            Patch<String> patch = DiffUtils.diff(oldLines, newLines);
            
            // Use DiffRowGenerator instead of UnifiedDiffUtils
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
        private final Map<String, DiffEntry> diffs = new HashMap<>();
        private final Set<String> added = new HashSet<>();
        private final Set<String> removed = new HashSet<>();
        
        public void addModifiedFile(String path, String type, String details) {
            diffs.put(path, new DiffEntry(type, details));
        }
        
        public void addBinaryDiff(String path, byte[] diff) {
            diffs.put(path, new DiffEntry("BINARY_MODIFIED", Base64.getEncoder().encodeToString(diff)));
        }
        
        public void addAddedFile(String path) {
            added.add(path);
        }
        
        public void addRemovedFile(String path) {
            removed.add(path);
        }
    }

    private static class DiffEntry {
        final String type;
        final String details;
        
        DiffEntry(String type, String details) {
            this.type = type;
            this.details = details;
        }
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

    private void performFullComparison(File oldDir, File newDir, ComparisonResult result) {
        try {
            LOGGER.info("Starting full comparison between directories");
            Set<Path> oldFiles = new HashSet<>();
            Set<Path> newFiles = new HashSet<>();
            
            LOGGER.info("Collecting files from old directory...");
            walkDirectory(oldDir.toPath(), path -> 
                oldFiles.add(oldDir.toPath().relativize(path)));
            
            LOGGER.info("Collecting files from new directory...");
            walkDirectory(newDir.toPath(), path -> 
                newFiles.add(newDir.toPath().relativize(path)));
            
            LOGGER.info("Found {} files in old directory, {} files in new directory",
                oldFiles.size(), newFiles.size());
            
            // Process differences
            Set<Path> added = new HashSet<>(newFiles);
            added.removeAll(oldFiles);
            added.forEach(path -> {
                LOGGER.debug("Added file: {}", path);
                result.addAddedFile(path.toString());
            });
            
            Set<Path> removed = new HashSet<>(oldFiles);
            removed.removeAll(newFiles);
            removed.forEach(path -> {
                LOGGER.debug("Removed file: {}", path);
                result.addRemovedFile(path.toString());
            });
            
            Set<Path> common = new HashSet<>(oldFiles);
            common.retainAll(newFiles);
            LOGGER.info("Processing {} common files for modifications", common.size());
            
            int processed = 0;
            for (Path path : common) {
                try {
                    compareSingleFile(
                        oldDir.toPath().resolve(path),
                        newDir.toPath().resolve(path),
                        path.toString(),
                        result
                    );
                    processed++;
                    if (processed % 100 == 0) {
                        LOGGER.info("Processed {}/{} common files", processed, common.size());
                    }
                } catch (IOException e) {
                    LOGGER.error("Error comparing file: {}", path, e);
                }
            }
            
            LOGGER.info("Full comparison completed successfully");
            
        } catch (Exception e) {
            LOGGER.error("Error during full comparison", e);
            throw new ComparisonException("Failed to perform full comparison", e);
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
}