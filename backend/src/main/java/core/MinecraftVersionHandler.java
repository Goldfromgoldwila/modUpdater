package core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class MinecraftVersionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftVersionHandler.class);
    private static final String DECOMPILED_DIR = "versions";

    /**
     * Step 1: Load and compare Minecraft versions.
     *
     * @param cleanVersion The older Minecraft version.
     * @param mcVersion    The newer Minecraft version.
     */
    public void compareMinecraftVersions(String cleanVersion, String mcVersion) {
        try {
            // Log and check the path for the clean version
            File oldVersionDir = findVersionDirectory(cleanVersion);
            if (oldVersionDir == null) {
                LOGGER.error("Failed to find directory for clean version: {}", cleanVersion);
                return;
            }
    
            // Log and check the path for the mc version
            File newVersionDir = findVersionDirectory(mcVersion);
            if (newVersionDir == null) {
                LOGGER.error("Failed to find directory for mc version: {}", mcVersion);
                return;
            }
    
            // Both directories are found, proceed to find differences
            LOGGER.info("Proceeding with comparison between cleanVersion: {} and mcVersion: {}", cleanVersion, mcVersion);
            List<String> differences = findDifferences(oldVersionDir, newVersionDir);
            LOGGER.info("Differences found: {}", differences);
        } catch (Exception e) {
            LOGGER.error("Error while comparing versions: {}", e.getMessage(), e);
        }
    }
    

    /**
     * Finds the directory for a specific Minecraft version.
     *
     * @param version The version to find.
     * @return The directory file.
     */
    private File findVersionDirectory(String version) {
        try {
            Path versionPath = Paths.get(DECOMPILED_DIR, version);
            LOGGER.info("Checking directory for version: {} at path: {}", version, versionPath.toAbsolutePath());
    
            if (Files.exists(versionPath)) {
                LOGGER.info("Found directory for version: {}", version);
                return versionPath.toFile();
            } else {
                LOGGER.error("Directory not found for version: {} at path: {}", version, versionPath.toAbsolutePath());
            }
        } catch (Exception e) {
            LOGGER.error("Error finding version directory for version {}: {}", version, e.getMessage());
        }
        return null;
    }
    

    /**
     * Finds differences between two decompiled Minecraft versions.
     *
     * @param oldVersionDir The older version directory.
     * @param newVersionDir The newer version directory.
     * @return A list of differences.
     */
    private List<String> findDifferences(File oldVersionDir, File newVersionDir) {
        List<String> differences = new ArrayList<>();

        try {
            Map<String, Path> oldFiles = mapFiles(oldVersionDir);
            Map<String, Path> newFiles = mapFiles(newVersionDir);

            // Compare file structures
            Set<String> allKeys = new HashSet<>();
            allKeys.addAll(oldFiles.keySet());
            allKeys.addAll(newFiles.keySet());

            for (String key : allKeys) {
                if (!newFiles.containsKey(key)) {
                    differences.add("Removed: " + key);
                } else if (!oldFiles.containsKey(key)) {
                    differences.add("Added: " + key);
                } else {
                    // If the file exists in both, compare content for methods, fields, etc.
                    compareFileContents(oldFiles.get(key), newFiles.get(key), differences);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Error comparing versions: {}", e.getMessage());
        }

        return differences;
    }

    /**
     * Maps all files in a directory to their paths relative to the directory root.
     *
     * @param rootDir The root directory.
     * @return A map of relative paths to file paths.
     * @throws IOException If an I/O error occurs.
     */
    private Map<String, Path> mapFiles(File rootDir) throws IOException {
        return Files.walk(rootDir.toPath())
                .filter(Files::isRegularFile)
                .collect(Collectors.toMap(
                        path -> rootDir.toPath().relativize(path).toString(),
                        path -> path
                ));
    }

    /**
     * Compares the contents of two files for differences in class definitions, methods, and fields.
     *
     * @param oldFile The old version of the file.
     * @param newFile The new version of the file.
     * @param differences The list to store differences.
     */
    private void compareFileContents(Path oldFile, Path newFile, List<String> differences) {
        try {
            if (isBinaryFile(oldFile) || isBinaryFile(newFile)) {
                // Compare binary files by their byte content
                if (Files.mismatch(oldFile, newFile) != -1) {
                    differences.add("Modified (binary): " + oldFile.toString());
                }
            } else {
                // Compare text files line by line
                List<String> oldLines = Files.readAllLines(oldFile);
                List<String> newLines = Files.readAllLines(newFile);
    
                if (!oldLines.equals(newLines)) {
                    differences.add("Modified: " + oldFile.toString());
                }
            }
        } catch (IOException e) {
            LOGGER.error("Error reading files: {}", e.getMessage());
        }
    }
    
    private boolean isBinaryFile(Path file) throws IOException {
        String mimeType = Files.probeContentType(file);
        return mimeType != null && !mimeType.startsWith("text");
    }
    

    /**
     * Saves the list of differences to a file.
     *
     * @param differences The list of differences.
     */
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
}
