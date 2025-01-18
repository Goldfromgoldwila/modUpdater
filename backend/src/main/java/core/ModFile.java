package core;

public class ModFile {
    private final String name;
    private final String path;
    private final String type;
    private final long size;
    private final String content;

    public ModFile(String name, String path, String type, long size, String content) {
        this.name = name;
        this.path = path;
        this.type = type;
        this.size = size;
        this.content = content;
    }

    // Add all required getters
    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public String getType() {
        return type;
    }

    public long getSize() {
        return size;
    }

    public String getContent() {
        return content;
    }
} 