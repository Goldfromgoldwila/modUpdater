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
                // Parallel analysis of mod and MC changes
                CompletableFuture<ModAnalysis> modAnalysisFuture = 
                    CompletableFuture.supplyAsync(() -> analyzeModStructure(modReportPath));
                    
                CompletableFuture<McVersionChanges> mcChangesFuture = 
                    CompletableFuture.supplyAsync(() -> analyzeMcChanges(mcVersionReportPath));

                // Wait for both analyses to complete
                ModAnalysis modAnalysis = modAnalysisFuture.get();
                McVersionChanges mcChanges = mcChangesFuture.get();

                // Analyze impacts
                Set<ImpactedComponent> impacts = impactAnalyzer.analyzeImpacts(modAnalysis, mcChanges);
                
                // Generate comprehensive report
                AnalysisResult result = new AnalysisResult(modAnalysis, mcChanges, impacts);
                generateDetailedReport(result);
                
                return result;
            } catch (Exception e) {
                logger.error("Analysis failed: {}", e.getMessage());
                throw new AnalysisException("Failed to complete analysis", e);
            }
        });
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