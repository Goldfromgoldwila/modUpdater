package core;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import core.event.AnalysisReadyEvent;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AnalysisCoordinator {
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    
    private AtomicBoolean mcVersionComplete = new AtomicBoolean(false);
    private AtomicBoolean modAnalysisComplete = new AtomicBoolean(false);
    
    private String mcReportPath;
    private String modReportPath;
    private String mcVersion;
    private String modVersion;

    public synchronized void markMcVersionComplete(String version, String reportPath) {
        mcVersionComplete.set(true);
        this.mcVersion = version;
        this.mcReportPath = reportPath;
        checkAndTriggerAnalysis();
    }

    public synchronized void markModAnalysisComplete(String version, String reportPath) {
        modAnalysisComplete.set(true);
        this.modVersion = version;
        this.modReportPath = reportPath;
        checkAndTriggerAnalysis();
    }

    private void checkAndTriggerAnalysis() {
        if (mcVersionComplete.get() && modAnalysisComplete.get()) {
            eventPublisher.publishEvent(new AnalysisReadyEvent(
                this,
                modVersion,
                mcVersion,
                modReportPath,
                mcReportPath
            ));
            
            // Reset flags for next analysis
            mcVersionComplete.set(false);
            modAnalysisComplete.set(false);
        }
    }
} 