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
import net.querz.nbt.io.NBTInputStream;
import net.querz.nbt.tag.CompoundTag;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Color;

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
    
            if (oldFileName.endsWith(".png") || newFileName.endsWith(".png")) {
                if (!comparePngFiles(oldFile, newFile)) {
                    differences.add("Modified (PNG): " + oldFile.toString() + " vs. " + newFile.toString());
                }
            } else if (oldFileName.endsWith(".class") || newFileName.endsWith(".class")) {
                if (Files.mismatch(oldFile, newFile) != -1) {
                    differences.add("Modified (binary): " + oldFile.toString() + " vs. " + newFile.toString());
                }
            } else if (oldFileName.endsWith(".nbt") || newFileName.endsWith(".nbt")) {
                if (!compareNbtFiles(oldFile, newFile)) {
                    differences.add("Modified (NBT): " + oldFile.toString() + " vs. " + newFile.toString());
                }
            } else {
                List<String> oldLines = Files.readAllLines(oldFile);
                List<String> newLines = Files.readAllLines(newFile);
                if (!oldLines.equals(newLines)) {
                    differences.add("Modified: " + oldFile.toString() + " vs. " + newFile.toString());
                }
            }
        } catch (IOException e) {
            LOGGER.error("Error reading files: {}. Old File: {}, New File: {}", e.getMessage(), oldFile.toString(), newFile.toString());
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
        LOGGER.info("Comparing NBT files: {} and {}", oldFile.getFileName(), newFile.getFileName());
        // Validate file names first
        if (!isValidNbtFile(oldFile) || !isValidNbtFile(newFile)) {
            LOGGER.warn("Invalid NBT file name detected: {} or {}", oldFile.getFileName(), newFile.getFileName());
            return false;
        }
    
        try (NBTInputStream oldNbtStream = new NBTInputStream(new BufferedInputStream(new FileInputStream(oldFile.toFile())));
             NBTInputStream newNbtStream = new NBTInputStream(new BufferedInputStream(new FileInputStream(newFile.toFile())))) {
            
            // Increase max depth and add buffering for larger files
            net.querz.nbt.io.NamedTag oldNamedTag = readAndValidateNbtTag(oldNbtStream, oldFile, 8192);
            if (oldNamedTag == null) {
                LOGGER.error("Failed to read old NBT file: {}", oldFile);
                return false;
            }
            
            net.querz.nbt.io.NamedTag newNamedTag = readAndValidateNbtTag(newNbtStream, newFile, 8192);
            if (newNamedTag == null) {
                LOGGER.error("Failed to read new NBT file: {}", newFile);
                return false;
            }
            
            boolean namesEqual = oldNamedTag.getName().equals(newNamedTag.getName());
            boolean tagsEqual = oldNamedTag.getTag().equals(newNamedTag.getTag());
            
            if (!namesEqual || !tagsEqual) {
                LOGGER.debug("NBT comparison failed - Names equal: {}, Tags equal: {}", namesEqual, tagsEqual);
            }
            
            return namesEqual && tagsEqual;
        } catch (IOException e) {
            LOGGER.error("Error reading NBT files: {} - {} - Stack trace: {}", 
                e.getMessage(), 
                e.getClass().getSimpleName(),
                Arrays.toString(e.getStackTrace()));
            return false;
        }
    }
    
    private boolean isValidNbtFile(Path file) {
        try {
            String fileName = file.getFileName().toString().toLowerCase();
            boolean isValid = fileName.endsWith(".nbt") && 
                             Files.exists(file) && 
                             Files.isRegularFile(file) &&
                             Files.isReadable(file) &&
                             Files.size(file) > 0;
            
            if (!isValid) {
                LOGGER.warn("NBT file validation failed for {}: exists={}, isFile={}, isReadable={}, size={}",
                    file,
                    Files.exists(file),
                    Files.isRegularFile(file),
                    Files.isReadable(file),
                    Files.size(file));
            }
            return isValid;
        } catch (IOException e) {
            LOGGER.error("Error validating NBT file {}: {}", file, e.getMessage());
            return false;
        }
    }
    
    private net.querz.nbt.io.NamedTag readAndValidateNbtTag(NBTInputStream stream, Path file, int maxDepth) {
        try {
            net.querz.nbt.io.NamedTag tag = stream.readTag(maxDepth);
            if (tag == null) {
                LOGGER.error("Null tag read from NBT file: {}", file);
                return null;
            }
            if (tag.getTag() == null) {
                LOGGER.error("Null inner tag in NBT file: {}", file);
                return null;
            }
            LOGGER.debug("Successfully read NBT tag from {}: name={}, tag type={}", 
                file,
                tag.getName(),
                tag.getTag().getClass().getSimpleName());
            return tag;
        } catch (IOException e) {
            LOGGER.error("Failed to read NBT data from {}: {} - Stack trace: {}", 
                file, 
                e.getMessage(),
                Arrays.toString(e.getStackTrace()));
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