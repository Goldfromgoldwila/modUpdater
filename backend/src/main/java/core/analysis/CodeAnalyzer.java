package core.analysis;

import org.springframework.stereotype.Service;
import java.nio.file.Path;
import core.analysis.model.CodeStructure;

@Service
public class CodeAnalyzer {
    public CodeStructure analyze(Path path) {
        return new CodeStructure();
    }
} 