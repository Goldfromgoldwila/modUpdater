package core.Event;

import java.nio.file.Path;

public class DecompilationCompleteEvent {
    private final Object source;
    private final Path modPath;

    public DecompilationCompleteEvent(Object source, Path modPath) {
        this.source = source;
        this.modPath = modPath;
    }

    public Path getModPath() {
        return modPath;
    }

    public Object getSource() {
        return source;
    }
} 