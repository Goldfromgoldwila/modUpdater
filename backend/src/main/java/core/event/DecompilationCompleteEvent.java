package core.event;

import org.springframework.context.ApplicationEvent;

public class DecompilationCompleteEvent extends ApplicationEvent {
    private final String modName;

    public DecompilationCompleteEvent(Object source, String modName) {
        super(source);
        this.modName = modName;
    }

    public String getModName() {
        return modName;
    }
} 