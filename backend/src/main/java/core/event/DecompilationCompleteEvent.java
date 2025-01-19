package core.Event;

import org.springframework.context.ApplicationEvent;
import java.nio.file.Path;

public class DecompilationCompleteEvent extends ApplicationEvent {
    private final Path modPath;
    private final String modName;

    public DecompilationCompleteEvent(Object source, Path modPath) {
        super(source);
        this.modPath = modPath;
        this.modName = modPath.getFileName().toString().replace(".jar", "");
    }

    public Path getModPath() {
        return modPath;
    }

    public String getModName() {
        return modName;
    }
} 