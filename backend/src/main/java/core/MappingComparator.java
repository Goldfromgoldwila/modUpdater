package core;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.*;
import java.util.*;
import java.nio.file.*;
import java.net.URL;
import java.net.HttpURLConnection;

@Component
public class MappingComparator {
    private static final Logger LOGGER = LoggerFactory.getLogger(MappingComparator.class);
    private static final String TEMP_DIR = "temp_mappings";
    private static final String DIFF_OUTPUT_DIR = "diff_results";

    public void compareYarnMappings(String version1, String version2) {
        LOGGER.info("Starting mapping comparison between {} and {}", version1, version2);
        try {
            // Create directories if they don't exist
            Files.createDirectories(Paths.get(TEMP_DIR));
            Files.createDirectories(Paths.get(DIFF_OUTPUT_DIR));

            // URLs for both versions (using direct + character)
            String url1 = String.format("https://maven.fabricmc.net/docs/yarn-%s/", version1);
            String url2 = String.format("https://maven.fabricmc.net/docs/yarn-%s/", version2);

            LOGGER.info("Fetching mappings from:\n{}\n{}", url1, url2);

            // Add validation for URLs
            if (!validateUrl(url1) || !validateUrl(url2)) {
                throw new IllegalArgumentException("Invalid URL format");
            }

            // Fetch and parse both documentation pages
            Document doc1 = Jsoup.connect(url1).get();
            Document doc2 = Jsoup.connect(url2).get();

            // Extract package information
            Map<String, Set<String>> packages1 = extractPackages(doc1);
            Map<String, Set<String>> packages2 = extractPackages(doc2);

            // Save mappings to temporary files for Meld comparison
            Path file1 = saveToTempFile(packages1, version1);
            Path file2 = saveToTempFile(packages2, version2);

            // Compare using Meld and generate reports
            generateMeldDiff(file1, file2);
            generateComparisonReport(packages1, packages2, version1, version2);

            LOGGER.info("Mapping comparison completed successfully");

        } catch (IOException e) {
            LOGGER.error("Error comparing mappings: {}", e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean validateUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            int responseCode = connection.getResponseCode();
            LOGGER.info("URL validation response code for {}: {}", urlString, responseCode);
            return responseCode == 200;
        } catch (Exception e) {
            LOGGER.error("Error validating URL {}: {}", urlString, e.getMessage());
            return false;
        }
    }

    private Path saveToTempFile(Map<String, Set<String>> packages, String version) throws IOException {
        Path tempFile = Paths.get(TEMP_DIR, "mapping_" + version.replace("+", "_") + ".txt");
        try (PrintWriter writer = new PrintWriter(new FileWriter(tempFile.toFile()))) {
            packages.forEach((pkg, classes) -> {
                writer.println("Package: " + pkg);
                classes.forEach(cls -> writer.println("  - " + cls));
                writer.println();
            });
        }
        return tempFile;
    }

    private void generateMeldDiff(Path file1, Path file2) {
        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String outputFile = Paths.get(DIFF_OUTPUT_DIR, 
                String.format("meld_diff_%s.html", timestamp)).toString();

            ProcessBuilder pb = new ProcessBuilder(
                "meld",
                file1.toString(),
                file2.toString(),
                "--output", outputFile
            );

            LOGGER.info("Executing Meld comparison...");
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                LOGGER.info("Meld comparison completed successfully: {}", outputFile);
            } else {
                LOGGER.error("Meld comparison failed with exit code: {}", exitCode);
            }

        } catch (Exception e) {
            LOGGER.error("Error generating Meld diff: {}", e.getMessage());
        }
    }

    private Map<String, Set<String>> extractPackages(Document doc) {
        Map<String, Set<String>> packages = new HashMap<>();
        Elements packageElements = doc.select("ul.packageList li");
        
        LOGGER.info("Extracting packages from document...");
        for (Element pkg : packageElements) {
            String packageName = pkg.text();
            Set<String> classes = new HashSet<>();
            
            // Extract classes for each package
            Elements classElements = pkg.select("ul.classList li");
            for (Element cls : classElements) {
                classes.add(cls.text());
            }
            
            packages.put(packageName, classes);
        }
        
        LOGGER.info("Extracted {} packages", packages.size());
        return packages;
    }

    private void generateComparisonReport(
            Map<String, Set<String>> packages1, 
            Map<String, Set<String>> packages2,
            String version1,
            String version2) {
        
        try {
            String filename = String.format("mapping_comparison_%s_to_%s_%s.txt", 
                version1.replace("+", "_"), 
                version2.replace("+", "_"),
                System.currentTimeMillis());

            Path reportPath = Paths.get(DIFF_OUTPUT_DIR, filename);
            LOGGER.info("Generating comparison report: {}", reportPath);

            try (PrintWriter writer = new PrintWriter(new FileWriter(reportPath.toFile()))) {
                writer.println("Yarn Mapping Comparison Report");
                writer.println("=============================");
                writer.printf("Comparing %s to %s%n%n", version1, version2);

                // Calculate differences
                Set<String> newPackages = new HashSet<>(packages2.keySet());
                newPackages.removeAll(packages1.keySet());

                Set<String> removedPackages = new HashSet<>(packages1.keySet());
                removedPackages.removeAll(packages2.keySet());

                Set<String> commonPackages = new HashSet<>(packages1.keySet());
                commonPackages.retainAll(packages2.keySet());

                // Write statistics
                writer.println("Statistics:");
                writer.printf("New packages: %d%n", newPackages.size());
                writer.printf("Removed packages: %d%n", removedPackages.size());
                writer.printf("Modified packages: %d%n%n", commonPackages.size());

                // Write detailed changes
                writeChanges(writer, "New Packages", newPackages, "+");
                writeChanges(writer, "Removed Packages", removedPackages, "-");
                writeModifiedPackages(writer, commonPackages, packages1, packages2);
            }

            LOGGER.info("Comparison report generated successfully");

        } catch (IOException e) {
            LOGGER.error("Error generating comparison report: {}", e.getMessage());
        }
    }

    private void writeChanges(PrintWriter writer, String title, Set<String> items, String prefix) {
        writer.println(title + ":");
        items.forEach(item -> writer.println(prefix + " " + item));
        writer.println();
    }

    private void writeModifiedPackages(
            PrintWriter writer, 
            Set<String> commonPackages,
            Map<String, Set<String>> packages1,
            Map<String, Set<String>> packages2) {
        
        writer.println("Modified Packages:");
        for (String pkg : commonPackages) {
            Set<String> classes1 = packages1.get(pkg);
            Set<String> classes2 = packages2.get(pkg);

            Set<String> newClasses = new HashSet<>(classes2);
            newClasses.removeAll(classes1);

            Set<String> removedClasses = new HashSet<>(classes1);
            removedClasses.removeAll(classes2);

            if (!newClasses.isEmpty() || !removedClasses.isEmpty()) {
                writer.printf("%nPackage: %s%n", pkg);
                newClasses.forEach(cls -> writer.println("  + " + cls));
                removedClasses.forEach(cls -> writer.println("  - " + cls));
            }
        }
    }
} 