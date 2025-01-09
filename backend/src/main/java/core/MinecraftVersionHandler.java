package core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import net.querz.nbt.io.NBTInputStream;
import net.querz.nbt.tag.CompoundTag;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Color;

@Component
public class MinecraftVersionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftVersionHandler.class);
    private static final String DECOMPILED_DIR = "versions";
    private static final int THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private final ExecutorService executorService;
    private final VersionComparisonMetrics metrics;

    public MinecraftVersionHandler() {
        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        this.metrics = new VersionComparisonMetrics();
    }

    public static class VersionComparisonMetrics {
        private int addedFiles = 0;
        private int removedFiles = 0;
        private int modifiedFiles = 0;
        private long totalFilesProcessed = 0;
        private long totalBytesProcessed = 0;
        private Map<String, Integer> changesByFileType = new ConcurrentHashMap<>();
        private long startTime;
        private long endTime;

        public void startComparison() {
            startTime = System.currentTimeMillis();
        }

        public void endComparison() {
            endTime = System.currentTimeMillis();
        }

        public double getComparisonTimeInSeconds() {
            return (endTime - startTime) / 1000.0;
        }

        public synchronized void incrementAddedFiles() {
            addedFiles++;
        }

        public synchronized void incrementRemovedFiles() {
            removedFiles++;
        }

        public synchronized void incrementModifiedFiles() {
            modifiedFiles++;
        }

        public synchronized void addProcessedBytes(long bytes) {
            totalBytesProcessed += bytes;
            totalFilesProcessed++;
        }

        public synchronized void recordFileTypeChange(String fileExtension) {
            changesByFileType.merge(fileExtension, 1, Integer::sum);
        }

        public Map<String, Object> generateMetricsReport() {
            Map<String, Object> report = new HashMap<>();
            report.put("addedFiles", addedFiles);
            report.put("removedFiles", removedFiles);
            report.put("modifiedFiles", modifiedFiles);
            report.put("totalFilesProcessed", totalFilesProcessed);
            report.put("totalBytesProcessed", totalBytesProcessed);
            report.put("processingTimeSeconds", getComparisonTimeInSeconds());
            report.put("changesByFileType", changesByFileType);
            report.put("changePercentage", calculateChangePercentage());
            return report;
        }

        private double calculateChangePercentage() {
            long totalChanges = addedFiles + removedFiles + modifiedFiles;
            return totalFilesProcessed > 0 ? 
                   (double) totalChanges / totalFilesProcessed * 100 : 0;
        }
    }

    public static class ComparisonResult {
        private final List<String> differences = Collections.synchronizedList(new ArrayList<>());
        private Map<String, String> renames = new HashMap<>();
        private Map<String, Object> metrics = new HashMap<>();

        public synchronized void addDifference(String difference) {
            differences.add(difference);
        }

        public List<String> getDifferences() {
            return new ArrayList<>(differences);
        }

        public void setRenames(Map<String, String> renames) {
            this.renames = renames;
        }

        public Map<String, String> getRenames() {
            return renames;
        }

        public void setMetrics(Map<String, Object> metrics) {
            this.metrics = metrics;
        }

        public Map<String, Object> getMetrics() {
            return metrics;
        }
    }

    public ComparisonResult compareMinecraftVersions(String cleanVersion, String mcVersion) {
        metrics.startComparison();
        ComparisonResult result = new ComparisonResult();

        try {
            File oldVersionDir = findVersionDirectory(cleanVersion);
            File newVersionDir = findVersionDirectory(mcVersion);

            if (oldVersionDir == null || newVersionDir == null) {
                LOGGER.error("Failed to find version directories");
                return result;
            }

            LOGGER.info("Starting comparison between versions: {} and {}", cleanVersion, mcVersion);
            
            // Parallel file mapping
            CompletableFuture<Map<String, Path>> oldFilesFuture = CompletableFuture.supplyAsync(
                () -> mapFiles(oldVersionDir), executorService);
            CompletableFuture<Map<String, Path>> newFilesFuture = CompletableFuture.supplyAsync(
                () -> mapFiles(newVersionDir), executorService);

            Map<String, Path> oldFiles = oldFilesFuture.get();
            Map<String, Path> newFiles = newFilesFuture.get();

            // Process differences in parallel
            List<CompletableFuture<Void>> comparisons = new ArrayList<>();
            Set<String> allKeys = new HashSet<>();
            allKeys.addAll(oldFiles.keySet());
            allKeys.addAll(newFiles.keySet());

            for (String key : allKeys) {
                comparisons.add(CompletableFuture.runAsync(() -> {
                    if (!newFiles.containsKey(key)) {
                        result.addDifference("Removed: " + key);
                        metrics.incrementRemovedFiles();
                        metrics.recordFileTypeChange(getFileExtension(key));
                    } else if (!oldFiles.containsKey(key)) {
                        result.addDifference("Added: " + key);
                        metrics.incrementAddedFiles();
                        metrics.recordFileTypeChange(getFileExtension(key));
                    } else {
                        compareFileContents(oldFiles.get(key), newFiles.get(key), result);
                    }
                }, executorService));
            }

            // Wait for all comparisons to complete
            CompletableFuture.allOf(comparisons.toArray(new CompletableFuture[0])).get();

            // Process potential renames
            List<Path> removedFiles = getRemovedFiles(result.getDifferences()).stream()
                .map(Paths::get)
                .collect(Collectors.toList());
            List<Path> addedFiles = getAddedFiles(result.getDifferences()).stream()
                .map(Paths::get)
                .collect(Collectors.toList());
                
            Map<String, String> renames = findPotentialRenames(removedFiles, addedFiles);
            result.setRenames(renames);

            metrics.endComparison();
            result.setMetrics(metrics.generateMetricsReport());

            generateDetailedReport(result);
            saveDifferences(result.getDifferences());

        } catch (Exception e) {
            LOGGER.error("Error during version comparison: {}", e.getMessage(), e);
        }

        return result;
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1) : "unknown";
    }

    private File findVersionDirectory(String version) {
        try {
            Path versionPath = Paths.get(DECOMPILED_DIR, version);
            LOGGER.info("Checking directory for version: {} at Path: {}", version, versionPath.toAbsolutePath());

            if (Files.exists(versionPath)) {
                LOGGER.info("Found directory for version: {}", version);
                return versionPath.toFile();
            } else {
                LOGGER.error("Directory not found for version: {} at Path: {}", version, versionPath.toAbsolutePath());
            }
        } catch (Exception e) {
            LOGGER.error("Error finding version directory for version {}: {}", version, e.getMessage());
        }
        return null;
    }

    private Map<String, Path> mapFiles(File rootDir) {
        Map<String, Path> allFiles = new HashMap<>();
        try {
            Files.walk(rootDir.toPath())
                .filter(Files::isRegularFile)
                .forEach(handleCheckedException(path -> {
                    String relativePath = rootDir.toPath().relativize(path).toString();
                    allFiles.put(relativePath, path);
                }));
        } catch (IOException e) {
            LOGGER.error("Error mapping files: {}", e.getMessage());
        }
        return allFiles;
    }

    private <T> Consumer<T> handleCheckedException(CheckedConsumer<T> consumer) {
        return t -> {
            try {
                consumer.accept(t);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    @FunctionalInterface
    private interface CheckedConsumer<T> {
        void accept(T t) throws IOException;
    }

    private void compareFileContents(Path oldFile, Path newFile, ComparisonResult result) {
        try {
            String oldFileName = oldFile.getFileName().toString();
            long fileSize = Files.size(oldFile) + Files.size(newFile);
            metrics.addProcessedBytes(fileSize);

            if (oldFileName.endsWith(".png")) {
                handlePngComparison(oldFile, newFile, result);
            } else if (oldFileName.endsWith(".class")) {
                handleBinaryComparison(oldFile, newFile, result);
            } else if (oldFileName.endsWith(".nbt")) {
                handleNbtComparison(oldFile, newFile, result);
            } else {
                handleTextFileComparison(oldFile, newFile, result);
            }
        } catch (IOException e) {
            LOGGER.error("Error comparing files: {}", e.getMessage());
        }
    }

    private void handlePngComparison(Path oldFile, Path newFile, ComparisonResult result) {
        if (!comparePngFiles(oldFile, newFile)) {
            result.addDifference("Modified (PNG): " + oldFile + " vs. " + newFile);
            metrics.incrementModifiedFiles();
            metrics.recordFileTypeChange("png");
        }
    }

    private void handleBinaryComparison(Path oldFile, Path newFile, ComparisonResult result) {
        try {
            if (Files.mismatch(oldFile, newFile) != -1) {
                result.addDifference("Modified (binary): " + oldFile + " vs. " + newFile);
                metrics.incrementModifiedFiles();
                metrics.recordFileTypeChange("class");
            }
        } catch (IOException e) {
            LOGGER.error("Error comparing binary files: {}", e.getMessage());
        }
    }

    private void handleNbtComparison(Path oldFile, Path newFile, ComparisonResult result) {
        if (!compareNbtFiles(oldFile, newFile)) {
            result.addDifference("Modified (NBT): " + oldFile + " vs. " + newFile);
            metrics.incrementModifiedFiles();
            metrics.recordFileTypeChange("nbt");
        }
    }

    private void handleTextFileComparison(Path oldFile, Path newFile, ComparisonResult result) {
        try {
            List<String> oldLines = Files.readAllLines(oldFile);
            List<String> newLines = Files.readAllLines(newFile);
            if (!oldLines.equals(newLines)) {
                result.addDifference("Modified: " + oldFile + " vs. " + newFile);
                metrics.incrementModifiedFiles();
                metrics.recordFileTypeChange(getFileExtension(oldFile.toString()));
            }
        } catch (IOException e) {
            LOGGER.error("Error comparing text files: {}", e.getMessage());
        }
    }


    private List<String> getRemovedFiles(List<String> differences) {
        return differences.stream()
            .filter(diff -> diff.startsWith("Removed: "))
            .map(diff -> diff.substring("Removed: ".length()))
            .collect(Collectors.toList());
    }
    
    private List<String> getAddedFiles(List<String> differences) {
        return differences.stream()
            .filter(diff -> diff.startsWith("Added: "))
            .map(diff -> diff.substring("Added: ".length()))
            .collect(Collectors.toList());
    }

    private void generateDetailedReport(ComparisonResult result) {
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get("detailed_report.txt"))) {
            writer.write("Detailed Comparison Report\n\n");
            
            // Write differences
            writer.write("Changes:\n");
            for (String difference : result.getDifferences()) {
                writer.write(difference + "\n");
            }
            
            // Write renames
            writer.write("\nRenamed Files:\n");
            for (Map.Entry<String, String> rename : result.getRenames().entrySet()) {
                writer.write(rename.getKey() + " -> " + rename.getValue() + "\n");
            }
            
            // Write metrics
            writer.write("\nMetrics:\n");
            for (Map.Entry<String, Object> metric : result.getMetrics().entrySet()) {
                writer.write(metric.getKey() + ": " + metric.getValue() + "\n");
            }
        } catch (IOException e) {
            LOGGER.error("Error generating detailed report: {}", e.getMessage());
        }
    }

    private boolean comparePngFiles(Path oldFile, Path newFile) {
        LOGGER.info("Comparing PNG files: {} and {}", oldFile.getFileName(), newFile.getFileName());
    
        try {
            BufferedImage oldImage = ImageIO.read(oldFile.toFile());
            BufferedImage newImage = ImageIO.read(newFile.toFile());
    
            if (oldImage == null || newImage == null) {
                LOGGER.error("Failed to read one of the PNG files: Old File: {}, New File: {}", oldFile, newFile);
                return false;
            }
    
            if (oldImage.getWidth() != newImage.getWidth() || oldImage.getHeight() != newImage.getHeight()) {
                LOGGER.warn("Image dimensions do not match: Old File: {} ({}x{}), New File: {} ({}x{})",
                        oldFile, oldImage.getWidth(), oldImage.getHeight(), newFile, newImage.getWidth(), newImage.getHeight());
                return false;
            }
    
            for (int y = 0; y < oldImage.getHeight(); y++) {
                for (int x = 0; x < oldImage.getWidth(); x++) {
                    if (!colorsAreEqual(new Color(oldImage.getRGB(x, y)), new Color(newImage.getRGB(x, y)))) {
                        LOGGER.debug("Pixel mismatch found at (x={}, y={}): Old File: {} ({}), New File: {} ({})",
                                x, y, oldFile, oldImage.getRGB(x, y), newFile, newImage.getRGB(x, y));
                        return false;
                    }
                }
            }
            LOGGER.info("PNG files are identical: {} and {}", oldFile.getFileName(), newFile.getFileName());
            return true;
        } catch (IOException e) {
            LOGGER.error("Error reading PNG files: Old File: {}, New File: {}, Error: {}", oldFile, newFile, e.getMessage(), e);
            return false;
        }
    }
    
    private boolean colorsAreEqual(Color color1, Color color2) {
        return color1.getRGB() == color2.getRGB();
    }

    private boolean compareNbtFiles(Path oldFile, Path newFile) {
        LOGGER.info("Comparing NBT files: {} and {}", oldFile.getFileName(), newFile.getFileName());
        
        if (!isValidNbtFile(oldFile) || !isValidNbtFile(newFile)) {
            LOGGER.warn("Invalid NBT file detected: {} or {}", oldFile.getFileName(), newFile.getFileName());
            return false;
        }
        
        try (NBTInputStream oldNbtStream = new NBTInputStream(new BufferedInputStream(new FileInputStream(oldFile.toFile())));
             NBTInputStream newNbtStream = new NBTInputStream(new BufferedInputStream(new FileInputStream(newFile.toFile())))) {
    
            net.querz.nbt.io.NamedTag oldNamedTag = readNbtTag(oldNbtStream, oldFile);
            net.querz.nbt.io.NamedTag newNamedTag = readNbtTag(newNbtStream, newFile);
    
            if (oldNamedTag == null || newNamedTag == null) {
                return false;
            }
    
            boolean namesEqual = Objects.equals(oldNamedTag.getName(), newNamedTag.getName());
            boolean tagsEqual = Objects.equals(oldNamedTag.getTag(), newNamedTag.getTag());
    
            if (!namesEqual || !tagsEqual) {
                LOGGER.debug("NBT comparison failed - Names equal: {}, Tags equal: {}", namesEqual, tagsEqual);
            }
    
            return namesEqual && tagsEqual;
        } catch (IOException e) {
            LOGGER.error("Error reading NBT files: {} - {} - Stack trace: {}", e.getMessage(), e.getClass().getSimpleName(), Arrays.toString(e.getStackTrace()));
            return false;
        }
    }
    
    private boolean isValidNbtFile(Path file) {
        try {
            String fileName = file.getFileName().toString().toLowerCase();
            boolean isValid = fileName.endsWith(".nbt") && Files.exists(file) && Files.isRegularFile(file) && Files.isReadable(file) && Files.size(file) > 0;
    
            if (!isValid) {
                LOGGER.warn("NBT file validation failed for {}: exists={}, isFile={}, isReadable={}, size={}", file, Files.exists(file), Files.isRegularFile(file), Files.isReadable(file), Files.size(file));
            }
            return isValid;
        } catch (IOException e) {
            LOGGER.error("Error validating NBT file {}: {}", file, e.getMessage());
            return false;
        }
    }
    
    private net.querz.nbt.io.NamedTag readNbtTag(NBTInputStream stream, Path file) {
        try {
            net.querz.nbt.io.NamedTag tag = stream.readTag(8192); // Add depth parameter
            if (tag == null || tag.getTag() == null) {
                LOGGER.error("Null tag or inner tag read from NBT file: {}", file);
                return null;
            }
            LOGGER.debug("Successfully read NBT tag from {}: name={}, tag type={}", 
                file, tag.getName(), tag.getTag().getClass().getSimpleName());
            return tag;
        } catch (IOException e) {
            LOGGER.error("Failed to read NBT data from {}: {} - Stack trace: {}", 
                file, e.getMessage(), Arrays.toString(e.getStackTrace()));
            return null;
        }
    }
    
    private void saveDifferences(List<String> differences) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter("differences.txt"))) {
            for (String difference : differences) {
                writer.write(difference);
                writer.newLine();
            }
            LOGGER.info("Differences saved to differences.txt");
        } catch (IOException e) {
            LOGGER.error("Error saving differences: {}", e.getMessage());
        }
    }
    

    private Map<String, String> findPotentialRenames(List<Path> removedFiles, List<Path> addedFiles) {
        Map<String, String> renames = new HashMap<>();
        for (Path removed : removedFiles) {
            for (Path added : addedFiles) {
                if (areFilesSimilar(removed, added)) {
                    renames.put(removed.toString(), added.toString());
                    break;
                }
            }
        }
        return renames;
    }

    private boolean areFilesSimilar(Path file1, Path file2) {
        try {
            String hash1 = Files.isRegularFile(file1) ? computeHash(file1) : null;
            String hash2 = Files.isRegularFile(file2) ? computeHash(file2) : null;
            return hash1 != null && hash1.equals(hash2);
        } catch (IOException | NoSuchAlgorithmException e) {
            LOGGER.error("Error comparing files for similarity: {}", e.getMessage());
        }
        return false;
    }

    private String computeHash(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] fileBytes = Files.readAllBytes(file);
        byte[] hashBytes = digest.digest(fileBytes);
        return Base64.getEncoder().encodeToString(hashBytes);
    }

    private void generateReport(List<String> differences, Map<String, String> renames) {
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get("differences_report.txt"))) {
            writer.write("Differences Report\n\n");
            writer.write("Added Files:\n");
            differences.stream().filter(d -> d.startsWith("Added")).forEach(diff -> writeLine(writer, diff));
            writer.write("\nRemoved Files:\n");
            differences.stream().filter(d -> d.startsWith("Removed")).forEach(diff -> writeLine(writer, diff));
            writer.write("\nRenamed Files:\n");
            renames.forEach((oldName, newName) -> writeLine(writer, oldName + " -> " + newName));
            writer.write("\nModified Files:\n");
            differences.stream().filter(d -> d.startsWith("Modified")).forEach(diff -> writeLine(writer, diff));
        } catch (IOException e) {
            LOGGER.error("Error generating report: {}", e.getMessage());
        }
    }

    private void writeLine(BufferedWriter writer, String line) {
        try {
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            LOGGER.error("Error writing line to report: {}", e.getMessage());
        }
    }
}