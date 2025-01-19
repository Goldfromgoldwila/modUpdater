package core.analysis.model;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import java.util.Set;

@Data
@Builder
@AllArgsConstructor
public class AnalysisResult {
    private ModAnalysis modAnalysis;
    private McVersionChanges mcChanges;
    private Set<ImpactedComponent> impacts;
} 