package core.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.annotation.Backoff;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import core.event.AnalysisReadyEvent;
import core.MinecraftVersionHandler;
import core.ReadModFile;
import core.analysis.model.*;
import core.analysis.exception.AnalysisException;
import java.io.PrintWriter;
import java.io.IOException;
import java.time.LocalDateTime;

@Service
public class ModChangeAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(ModChangeAnalyzer.class);
    
    @Autowired
    private MinecraftVersionHandler minecraftVersionHandler;
    
    @Autowired
    private ReadModFile readModFile;
    
    private final CodeAnalyzer codeAnalyzer;
    private final DependencyAnalyzer dependencyAnalyzer;
    private final ImpactAnalyzer impactAnalyzer;
    
    public ModChangeAnalyzer(CodeAnalyzer codeAnalyzer, 
                           DependencyAnalyzer dependencyAnalyzer,
                           ImpactAnalyzer impactAnalyzer) {
        this.codeAnalyzer = codeAnalyzer;
        this.dependencyAnalyzer = dependencyAnalyzer;
        this.impactAnalyzer = impactAnalyzer;
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000))
    public CompletableFuture<AnalysisResult> analyzeModChanges(Path modReportPath, Path mcVersionReportPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ModAnalysis modAnalysis = analyzeModStructure(modReportPath);
                McVersionChanges mcChanges = analyzeMcChanges(mcVersionReportPath);
                Set<ImpactedComponent> impacts = impactAnalyzer.analyzeImpacts(modAnalysis, mcChanges);
                
                AnalysisResult result = new AnalysisResult(modAnalysis, mcChanges, impacts);
                generateDetailedReport(result);
                
                return result;
            } catch (Exception e) {
                logger.error("Analysis failed: {}", e.getMessage());
                throw new AnalysisException("Failed to complete analysis", e);
            }
        });
    }

    private void generateDetailedReport(AnalysisResult result) throws IOException {
        Path reportPath = Paths.get("analysis_results", 
            "detailed_impact_report_" + System.currentTimeMillis() + ".md");
        Files.createDirectories(reportPath.getParent());
        
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(reportPath))) {
            writer.println("# Detailed Mod Impact Analysis Report");
            writer.println("Generated: " + LocalDateTime.now());
            
            // Write mod analysis
            writer.println("\n## Mod Analysis");
            writer.println("Dependencies found: " + result.getModAnalysis().getDependencies().size());
            
            // Write MC changes
            writer.println("\n## Minecraft Changes");
            writer.println("Total changes: " + result.getMcChanges().getChanges().size());
            
            // Write impacts
            writer.println("\n## Impact Analysis");
            result.getImpacts().forEach(impact -> {
                writer.println("\n### " + impact.getName());
                writer.println("Type: " + impact.getType());
                writer.println("Impact Score: " + impact.getImpactScore());
                writer.println("Affected Dependencies:");
                impact.getAffectedDependencies().forEach(dep -> 
                    writer.println("- " + dep));
            });
        }
    }

    private ModAnalysis analyzeModStructure(Path modReportPath) {
        try {
            return ModAnalysis.builder()
                .codeStructure(codeAnalyzer.analyze(modReportPath))
                .dependencies(dependencyAnalyzer.analyzeDependencies(modReportPath))
                .build();
        } catch (Exception e) {
            logger.error("Mod analysis failed: {}", e.getMessage());
            throw new AnalysisException("Failed to analyze mod structure", e);
        }
    }

    private McVersionChanges analyzeMcChanges(Path mcVersionReportPath) {
        try {
            List<String> changes = Files.readAllLines(mcVersionReportPath);
            return new McVersionChanges(changes);
        } catch (Exception e) {
            logger.error("MC version analysis failed: {}", e.getMessage());
            throw new AnalysisException("Failed to analyze MC changes", e);
        }
    }

    @EventListener
    public void onAnalysisReady(AnalysisReadyEvent event) {
        logger.info("Starting comprehensive mod change analysis");
        analyzeModChanges(
            Paths.get(event.getModReportPath()), 
            Paths.get(event.getMcReportPath())
        )
        .thenAccept(result -> logger.info("Analysis completed successfully"))
        .exceptionally(ex -> {
            logger.error("Analysis failed: {}", ex.getMessage());
            return null;
        });
    }
} 