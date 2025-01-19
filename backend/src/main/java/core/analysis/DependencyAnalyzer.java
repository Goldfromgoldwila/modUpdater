package core.analysis;

import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.util.Set;

@Service
public class DependencyAnalyzer {
    public Set<String> analyzeDependencies(Path path) {
        return Set.of();
    }
} 