package core.analysis.model;

import lombok.Data;
import java.util.List;

@Data
public class McVersionChanges {
    private List<String> changes;

    public McVersionChanges(List<String> changes) {
        this.changes = changes;
    }
} 