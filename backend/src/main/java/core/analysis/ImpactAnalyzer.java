package core.analysis;

import org.springframework.stereotype.Service;
import java.util.Set;
import core.analysis.model.ModAnalysis;
import core.analysis.model.McVersionChanges;
import core.analysis.model.ImpactedComponent;

@Service
public class ImpactAnalyzer {
    public Set<ImpactedComponent> analyzeImpacts(ModAnalysis modAnalysis, McVersionChanges mcChanges) {
        return Set.of();
    }
} 