package core.analysis.model;

import lombok.Data;
import lombok.Builder;
import java.util.Set;
import java.util.HashSet;

@Data
@Builder
public class ModAnalysis {
    private CodeStructure codeStructure;
    private Set<String> dependencies = new HashSet<>();
} 