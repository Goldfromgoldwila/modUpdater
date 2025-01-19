package core.analysis.model;

import lombok.Data;
import java.util.Set;
import java.util.Map;

@Data
public class CodeStructure {
    private Set<String> classes;
    private Map<String, Set<String>> dependencies;
    private Map<String, Set<String>> methods;
} 