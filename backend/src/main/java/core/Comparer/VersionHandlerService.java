package core.Comparer;

public interface VersionHandlerService {
    void setCleanVersion(String version);
    void setMcVersion(String version);
    void compareVersions(String cleanVersion, String targetVersion);
    void processMod(String mcVersion);
} 