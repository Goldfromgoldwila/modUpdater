package core.event;

import org.springframework.context.ApplicationEvent;

public class AnalysisReadyEvent extends ApplicationEvent {
    private final String modVersion;
    private final String mcVersion;
    private final String modReportPath;
    private final String mcReportPath;

    public AnalysisReadyEvent(Object source, String modVersion, String mcVersion, 
                            String modReportPath, String mcReportPath) {
        super(source);
        this.modVersion = modVersion;
        this.mcVersion = mcVersion;
        this.modReportPath = modReportPath;
        this.mcReportPath = mcReportPath;
    }

    // Add getters
    public String getModVersion() { return modVersion; }
    public String getMcVersion() { return mcVersion; }
    public String getModReportPath() { return modReportPath; }
    public String getMcReportPath() { return mcReportPath; }
} 