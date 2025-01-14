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
    private static final String DIFF_OUTPUT_DIR = "diff_results";

    public void compareYarnMappings(String version1, String version2) {
        LOGGER.info("Starting online mapping comparison between {} and {}", version1, version2);
        try {
            Files.createDirectories(Paths.get(DIFF_OUTPUT_DIR));

            String url1 = String.format("https://maven.fabricmc.net/docs/yarn-%s/", version1);
            String url2 = String.format("https://maven.fabricmc.net/docs/yarn-%s/", version2);

            LOGGER.info("Fetching mappings from:\n{}\n{}", url1, url2);

            Document doc1 = Jsoup.connect(url1).get();
            Document doc2 = Jsoup.connect(url2).get();

            // Extract and compare package information directly
            compareDocumentation(doc1, doc2, version1, version2);

        } catch (IOException e) {
            LOGGER.error("Error comparing mappings: {}", e.getMessage());
        }
    }

    private void compareDocumentation(Document doc1, Document doc2, String version1, String version2) {
        // Extract all class links and their content
        Map<String, String> classes1 = extractClassContent(doc1);
        Map<String, String> classes2 = extractClassContent(doc2);

        generateComparisonReport(classes1, classes2, version1, version2);
    }

    private Map<String, String> extractClassContent(Document doc) {
        Map<String, String> classes = new HashMap<>();
        Elements classLinks = doc.select("div.contentContainer a[href*='.html']");
        
        for (Element link : classLinks) {
            try {
                String className = link.text();
                String classUrl = link.attr("abs:href");
                Document classDoc = Jsoup.connect(classUrl).get();
                
                // Extract relevant content (methods, fields, etc.)
                String content = extractRelevantContent(classDoc);
                classes.put(className, content);
                
                LOGGER.debug("Extracted content for class: {}", className);
            } catch (IOException e) {
                LOGGER.error("Error extracting class content: {}", e.getMessage());
            }
        }
        
        return classes;
    }

    private String extractRelevantContent(Document classDoc) {
        StringBuilder content = new StringBuilder();
        
        // Extract methods
        Elements methods = classDoc.select("div.methodDetails");
        methods.forEach(method -> {
            content.append("Method: ").append(method.select(".memberSignature").text()).append("\n");
        });
        
        // Extract fields
        Elements fields = classDoc.select("div.fieldDetails");
        fields.forEach(field -> {
            content.append("Field: ").append(field.select(".memberSignature").text()).append("\n");
        });
        
        return content.toString();
    }

    private void generateComparisonReport(
            Map<String, String> classes1, 
            Map<String, String> classes2,
            String version1,
            String version2) {
        
        try {
            String filename = String.format("online_mapping_comparison_%s_to_%s_%s.txt",
                version1.replace("+", "_"),
                version2.replace("+", "_"),
                System.currentTimeMillis());

            Path reportPath = Paths.get(DIFF_OUTPUT_DIR, filename);
            
            try (PrintWriter writer = new PrintWriter(new FileWriter(reportPath.toFile()))) {
                writer.println("Online Yarn Mapping Comparison Report");
                writer.println("===================================");
                writer.printf("Comparing %s to %s%n%n", version1, version2);

                // Compare classes
                Set<String> addedClasses = new HashSet<>(classes2.keySet());
                addedClasses.removeAll(classes1.keySet());

                Set<String> removedClasses = new HashSet<>(classes1.keySet());
                removedClasses.removeAll(classes2.keySet());

                Set<String> commonClasses = new HashSet<>(classes1.keySet());
                commonClasses.retainAll(classes2.keySet());

                // Write statistics
                writeStatistics(writer, addedClasses, removedClasses, commonClasses);
                
                // Write detailed changes
                writeDetailedChanges(writer, classes1, classes2, 
                    addedClasses, removedClasses, commonClasses);
            }

            LOGGER.info("Generated online comparison report: {}", reportPath);

        } catch (IOException e) {
            LOGGER.error("Error generating comparison report: {}", e.getMessage());
        }
    }

    private void writeStatistics(PrintWriter writer, 
            Set<String> added, Set<String> removed, Set<String> common) {
        writer.println("Statistics:");
        writer.printf("New classes: %d%n", added.size());
        writer.printf("Removed classes: %d%n", removed.size());
        writer.printf("Modified classes: %d%n%n", common.size());
    }

    private void writeDetailedChanges(PrintWriter writer,
            Map<String, String> classes1,
            Map<String, String> classes2,
            Set<String> added,
            Set<String> removed,
            Set<String> common) {
        
        writer.println("=== Added Classes ===");
        added.forEach(cls -> writer.println("+ " + cls));
        
        writer.println("\n=== Removed Classes ===");
        removed.forEach(cls -> writer.println("- " + cls));
        
        writer.println("\n=== Modified Classes ===");
        common.stream()
            .filter(cls -> !classes1.get(cls).equals(classes2.get(cls)))
            .forEach(cls -> {
                writer.printf("%nClass: %s%n", cls);
                writer.println("Changes detected in methods or fields");
            });
    }
} 