package core.analysis.model;

import lombok.Data;
import lombok.Builder;
import java.util.Set;
import java.util.HashSet;

@Data
@Builder
public class ImpactedComponent {
    private String name;
    private String type;
    private double impactScore;
    private Set<String> affectedDependencies = new HashSet<>();
} 