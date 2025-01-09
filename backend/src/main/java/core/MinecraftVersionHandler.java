package core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Color;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;

public class MinecraftVersionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftVersionHandler.class);
    private static final String DECOMPILED_DIR = "versions";

    public void compareMinecraftVersions(String cleanVersion, String mcVersion) {
        try {
            File oldVersionDir = findVersionDirectory(cleanVersion);
            if (oldVersionDir == null) {
                LOGGER.error("Failed to find directory for clean version: {}", cleanVersion);
                return;
            }

            File newVersionDir = findVersionDirectory(mcVersion);
            if (newVersionDir == null) {
                LOGGER.error("Failed to find directory for mc version: {}", mcVersion);
                return;
            }

            LOGGER.info("Proceeding with comparison between cleanVersion: {} and mcVersion: {}", cleanVersion, mcVersion);
            List<String> differences = findDifferences(oldVersionDir, newVersionDir);
            LOGGER.info("Differences found: {}", differences);
        } catch (Exception e) {
            LOGGER.error("Error while comparing versions: {}", e.getMessage(), e);
        }
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

    private List<String> findDifferences(File oldVersionDir, File newVersionDir) {
        List<String> differences = new ArrayList<>();

        try {
            Map<String, Path> oldFiles = mapFiles(oldVersionDir);
            Map<String, Path> newFiles = mapFiles(newVersionDir);

            Set<String> allKeys = new HashSet<>();
            allKeys.addAll(oldFiles.keySet());
            allKeys.addAll(newFiles.keySet());

            for (String key : allKeys) {
                if (!newFiles.containsKey(key)) {
                    differences.add("Removed: " + key);
                } else if (!oldFiles.containsKey(key)) {
                    differences.add("Added: " + key);
                } else {
                    compareFileContents(oldFiles.get(key), newFiles.get(key), differences);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Error comparing versions: {}", e.getMessage());
        }

        return differences;
    }

    private Map<String, Path> mapFiles(File rootDir) throws IOException {
        Map<String, Path> allFiles = new HashMap<>();
        Files.walk(rootDir.toPath())
            .filter(Files::isRegularFile)
            .forEach(handleCheckedException(path -> {
                String relativePath = rootDir.toPath().relativize(path).toString();
                allFiles.put(relativePath, path);
            }));
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
        private void compareFileContents(Path oldFile, Path newFile, List<String> differences) {
            try {
                String oldFileName = oldFile.getFileName().toString();
                String newFileName = newFile.getFileName().toString();
        
                if (oldFileName.endsWith(".class")) {
                    // Handle obfuscated Minecraft class files
                    compareMinecraftClassFiles(oldFile, newFile, differences);
                } else if (oldFileName.endsWith(".nbt")) {
                    if (!compareNbtFiles(oldFile, newFile)) {
                        differences.add(String.format("Modified (NBT): %s -> %s", 
                            oldFile.toAbsolutePath(), 
                            newFile.toAbsolutePath()));
                    }
                } else if (oldFileName.endsWith(".png")) {
                    if (!comparePngFiles(oldFile, newFile)) {
                        differences.add(String.format("Modified (PNG): %s -> %s", 
                            oldFile.toAbsolutePath(), 
                            newFile.toAbsolutePath()));
                    }
                } else {
                    compareTextFiles(oldFile, newFile, differences);
                }
            } catch (Exception e) {  // Changed from IOException to Exception
                LOGGER.error("Error comparing files: {} vs {}: {}", 
                    oldFile.toAbsolutePath(), 
                    newFile.toAbsolutePath(), 
                    e.getMessage());
            }
        }
        private void compareTextFiles(Path oldFile, Path newFile, List<String> differences) {
            try {
                List<String> oldLines = Files.readAllLines(oldFile);
                List<String> newLines = Files.readAllLines(newFile);
                
                if (!oldLines.equals(newLines)) {
                    differences.add(String.format("Modified (Text): %s -> %s", 
                        oldFile.toAbsolutePath(), 
                        newFile.toAbsolutePath()));
                }
            } catch (IOException e) {
                LOGGER.error("Error comparing text files: {} vs {}: {}", 
                    oldFile.toAbsolutePath(), 
                    newFile.toAbsolutePath(), 
                    e.getMessage());
            }
        }

        private void compareMinecraftClassFiles(Path oldFile, Path newFile, List<String> differences) {
            try {
                byte[] oldBytes = Files.readAllBytes(oldFile);
                byte[] newBytes = Files.readAllBytes(newFile);
        
                if (!Arrays.equals(oldBytes, newBytes)) {
                    String className = oldFile.getFileName().toString().replace(".class", "");
                    boolean isInnerClass = className.contains("$");
                    String classType = isInnerClass ? "Inner Class" : "Class";
                    String outerClass = isInnerClass ? className.substring(0, className.indexOf("$")) : null;
        
                    StringBuilder diffMessage = new StringBuilder();
                    diffMessage.append(String.format("Modified (%s): %s\n", classType, className));
                    diffMessage.append(String.format("  Old path: %s\n", oldFile.toAbsolutePath()));
                    diffMessage.append(String.format("  New path: %s\n", newFile.toAbsolutePath()));
                    diffMessage.append(String.format("  Size: %d -> %d bytes\n", oldBytes.length, newBytes.length));
        
                    if (isInnerClass) {
                        diffMessage.append(String.format("  Outer class: %s\n", outerClass));
                    }
        
                    differences.add(diffMessage.toString());
                    LOGGER.debug("Found difference in class file: {}", className);
                }
            } catch (IOException e) {
                LOGGER.error("Error comparing class files: {} vs {}: {}", 
                    oldFile.getFileName(), 
                    newFile.getFileName(), 
                    e.getMessage());
            }
        }


    private boolean comparePngFiles(Path oldFile, Path newFile) {
        try {
            BufferedImage oldImage = ImageIO.read(oldFile.toFile());
            BufferedImage newImage = ImageIO.read(newFile.toFile());
    
            if (oldImage == null || newImage == null) {
                LOGGER.error("Failed to read one of the PNG files: Old File: {}, New File: {}", oldFile.toString(), newFile.toString());
                return false;
            }
    
            if (oldImage.getWidth() != newImage.getWidth() || oldImage.getHeight() != newImage.getHeight()) {
                return false;
            }
    
            for (int y = 0; y < oldImage.getHeight(); y++) {
                for (int x = 0; x < oldImage.getWidth(); x++) {
                    if (!colorsAreEqual(new Color(oldImage.getRGB(x, y)), new Color(newImage.getRGB(x, y)))) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException e) {
            LOGGER.error("Error reading PNG files: Old File: {}, New File: {}, Error: {}", oldFile.toString(), newFile.toString(), e.getMessage());
            return false;
        }
    }
    
    private boolean colorsAreEqual(Color color1, Color color2) {
        return color1.getRGB() == color2.getRGB();
    }
    
    private boolean compareNbtFiles(Path oldFile, Path newFile) {
        LOGGER.info("Comparing NBT files:\nOld: {}\nNew: {}", 
            oldFile.toAbsolutePath(), 
            newFile.toAbsolutePath());
        
        try {
            // Create a reader
            BinaryTagIO.Reader reader = BinaryTagIO.reader();
    
            // Read both files
            CompoundBinaryTag oldRoot = null;
            CompoundBinaryTag newRoot = null;
    
            try {
                oldRoot = reader.read(oldFile);
            } catch (Exception e) {
                LOGGER.warn("Failed to read old NBT file: {}", oldFile);
                return false;
            }
    
            try {
                newRoot = reader.read(newFile);
            } catch (Exception e) {
                LOGGER.warn("Failed to read new NBT file: {}", newFile);
                return false;
            }
    
            if (oldRoot == null || newRoot == null) {
                LOGGER.error("Failed to read NBT files");
                return false;
            }
    
            // Compare the NBT data
            return compareNbtTags(oldRoot, newRoot);
    
        } catch (Exception e) {
            LOGGER.error("Error comparing NBT files: {} vs {}\nError: {} - Stack trace: {}", 
                oldFile.toAbsolutePath(), 
                newFile.toAbsolutePath(),
                e.getMessage(),
                Arrays.toString(e.getStackTrace()));
            return false;
        }
    }
    
    private boolean compareNbtTags(CompoundBinaryTag tag1, CompoundBinaryTag tag2) {
        Set<String> keys1 = tag1.keySet();
        Set<String> keys2 = tag2.keySet();
    
        if (!keys1.equals(keys2)) {
            LOGGER.debug("NBT tags have different keys");
            return false;
        }
    
        for (String key : keys1) {
            BinaryTag value1 = tag1.get(key);
            BinaryTag value2 = tag2.get(key);
    
            if (value1 == null || value2 == null) {
                return false;
            }
    
            if (!value1.equals(value2)) {
                LOGGER.debug("Values differ for key {}", key);
                return false;
            }
        }
    
        return true;
    }
    
    private boolean isValidNbtFile(Path file) {
        if (!Files.exists(file) || !Files.isRegularFile(file) || !Files.isReadable(file)) {
            LOGGER.warn("Invalid file: {}", file.toAbsolutePath());
            return false;
        }
    
        try {
            BinaryTagIO.Reader reader = BinaryTagIO.reader();
            reader.read(file);
            return true;
        } catch (Exception e) {
            LOGGER.warn("File is not a valid NBT file: {} - {}", file.toAbsolutePath(), e.getMessage());
            return false;
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