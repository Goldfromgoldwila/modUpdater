package core;

public class ModFile {
    private String name;
    private String path;
    private String type;
    private long size;

    public ModFile(String name, String path, String type, long size) {
        this.name = name;
        this.path = path;
        this.type = type;
        this.size = size;
    }

    // Add getters and setters
    public String getName() { return name; }
    public String getPath() { return path; }
    public String getType() { return type; }
    public long getSize() { return size; }
} 