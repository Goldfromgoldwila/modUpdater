package core.ModFile;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ModFile {
    private String name;
    private String path;
    private String type;
    private String content;
    private long size;
    private String hash;
} 