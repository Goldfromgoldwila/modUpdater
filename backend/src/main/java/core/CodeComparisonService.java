package core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import core.CodeAnalyzer.MethodSignature;

@Service
public class CodeComparisonService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CodeComparisonService.class);
    private final CodeAnalyzer codeAnalyzer;

    public CodeComparisonService(CodeAnalyzer codeAnalyzer) {
        this.codeAnalyzer = codeAnalyzer;
    }

    public void compareVersions(Path oldPath, Path newPath) {
        LOGGER.info("Starting code comparison between:");
        LOGGER.info("Old path: {}", oldPath);
        LOGGER.info("New path: {}", newPath);
        
        try {
            Map<String, CodeAnalyzer.ClassInfo> oldClasses = codeAnalyzer.analyzeDecompiledCode(oldPath);
            Map<String, CodeAnalyzer.ClassInfo> newClasses = codeAnalyzer.analyzeDecompiledCode(newPath);
            
            LOGGER.info("Found {} classes in old version", oldClasses.size());
            LOGGER.info("Found {} classes in new version", newClasses.size());

            // Find removed classes
            Set<String> removedClasses = new HashSet<>(oldClasses.keySet());
            removedClasses.removeAll(newClasses.keySet());
            
            // Find added classes
            Set<String> addedClasses = new HashSet<>(newClasses.keySet());
            addedClasses.removeAll(oldClasses.keySet());
            
            // Find modified and unchanged classes
            Set<String> commonClasses = new HashSet<>(oldClasses.keySet());
            commonClasses.retainAll(newClasses.keySet());
            
            Map<String, List<String>> modifications = new HashMap<>();
            Set<String> unchangedClasses = new HashSet<>();
            
            for (String className : commonClasses) {
                CodeAnalyzer.ClassInfo oldClass = oldClasses.get(className);
                CodeAnalyzer.ClassInfo newClass = newClasses.get(className);
                
                List<String> changes = compareClasses(oldClass, newClass);
                if (!changes.isEmpty()) {
                    modifications.put(className, changes);
                } else {
                    unchangedClasses.add(className);
                }
            }

            // Log the results with unchanged files
            logChanges(removedClasses, addedClasses, modifications, unchangedClasses);
            
        } catch (Exception e) {
            LOGGER.error("Error during code comparison: {}", e.getMessage(), e);
        }
    }

    private List<String> compareClasses(CodeAnalyzer.ClassInfo oldClass, CodeAnalyzer.ClassInfo newClass) {
        List<String> changes = new ArrayList<>();
        
        // Compare interfaces
        Set<String> removedInterfaces = new HashSet<>(oldClass.interfaces);
        removedInterfaces.removeAll(newClass.interfaces);
        if (!removedInterfaces.isEmpty()) {
            changes.add("Removed interfaces: " + removedInterfaces);
        }
        
        // Compare methods using fully qualified reference
        Set<String> oldMethods = oldClass.methods.stream()
            .map(CodeAnalyzer.MethodSignature::toString)
            .collect(Collectors.toSet());
            
        Set<String> newMethods = newClass.methods.stream()
            .map(CodeAnalyzer.MethodSignature::toString)
            .collect(Collectors.toSet());
            
        Set<String> removedMethods = new HashSet<>(oldMethods);
        removedMethods.removeAll(newMethods);
        if (!removedMethods.isEmpty()) {
            changes.add("Removed methods: " + removedMethods);
        }
        
        Set<String> addedMethods = new HashSet<>(newMethods);
        addedMethods.removeAll(oldMethods);
        if (!addedMethods.isEmpty()) {
            changes.add("Added methods: " + addedMethods);
        }
        
        return changes;
    }

    private void logChanges(Set<String> removedClasses, Set<String> addedClasses, 
                          Map<String, List<String>> modifications,
                          Set<String> unchangedClasses) {
        LOGGER.info("\n=== Code Analysis Results ===\n");
        
        if (!removedClasses.isEmpty()) {
            LOGGER.info("Removed Classes:");
            removedClasses.forEach(cls -> LOGGER.info("  - {}", cls));
            LOGGER.info("");
        }
        
        if (!addedClasses.isEmpty()) {
            LOGGER.info("Added Classes:");
            addedClasses.forEach(cls -> LOGGER.info("  + {}", cls));
            LOGGER.info("");
        }
        
        if (!modifications.isEmpty()) {
            LOGGER.info("Modified Classes:");
            modifications.forEach((cls, changes) -> {
                LOGGER.info("  ~ {}", cls);
                changes.forEach(change -> LOGGER.info("    {}", change));
            });
            LOGGER.info("");
        }

        if (!unchangedClasses.isEmpty()) {
            LOGGER.info("Unchanged Classes:");
            unchangedClasses.forEach(cls -> LOGGER.info("  = {}", cls));
            LOGGER.info("");
        }

        // Summary
        LOGGER.info("Summary:");
        LOGGER.info("  Total Classes: {}", 
            removedClasses.size() + addedClasses.size() + modifications.size() + unchangedClasses.size());
        LOGGER.info("  Added: {}", addedClasses.size());
        LOGGER.info("  Removed: {}", removedClasses.size());
        LOGGER.info("  Modified: {}", modifications.size());
        LOGGER.info("  Unchanged: {}", unchangedClasses.size());
    }
} 