package core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.nio.file.Path;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileInfo {
    private String fileName;
    private String filePath;
    private String changeType;
    private String originalPath;
    private String newPath;
    private LocalDateTime lastModified;
    private long fileSize;
    private String fileHash;
    private String fileExtension;
    private String mimeType;
    private boolean isBinary;
    private String diffSummary;
    private int linesAdded;
    private int linesRemoved;

    public FileInfo(String fileName, String filePath, String changeType) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.changeType = changeType;
        this.lastModified = LocalDateTime.now();
        this.fileExtension = getExtensionFromFileName(fileName);
    }

    private String getExtensionFromFileName(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        return lastDotIndex > 0 ? fileName.substring(lastDotIndex + 1) : "";
    }

    // Add validation method
    public boolean isValid() {
        return fileName != null && !fileName.isEmpty() &&
               filePath != null && !filePath.isEmpty() &&
               changeType != null && !changeType.isEmpty();
    }

    // Add builder pattern
    public FileInfo withSize(long size) {
        this.fileSize = size;
        return this;
    }

    public FileInfo withHash(String hash) {
        this.fileHash = hash;
        return this;
    }

    public FileInfo withDiffSummary(String summary, int added, int removed) {
        this.diffSummary = summary;
        this.linesAdded = added;
        this.linesRemoved = removed;
        return this;
    }

    // Getters
    public String getFileName() { return fileName; }
    public String getFilePath() { return filePath; }
    public String getChangeType() { return changeType; }
    public String getOriginalPath() { return originalPath; }
    public String getNewPath() { return newPath; }
    public LocalDateTime getLastModified() { return lastModified; }
    public long getFileSize() { return fileSize; }
    public String getFileHash() { return fileHash; }
    public String getFileExtension() { return fileExtension; }
    public String getMimeType() { return mimeType; }
    public boolean isBinary() { return isBinary; }
    public String getDiffSummary() { return diffSummary; }
    public int getLinesAdded() { return linesAdded; }
    public int getLinesRemoved() { return linesRemoved; }

    // Setters
    public void setLastModified(LocalDateTime lastModified) { this.lastModified = lastModified; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public void setBinary(boolean binary) { this.isBinary = binary; }
    public void setDiffSummary(String diffSummary) { this.diffSummary = diffSummary; }
    public void setLinesAdded(int linesAdded) { this.linesAdded = linesAdded; }
    public void setLinesRemoved(int linesRemoved) { this.linesRemoved = linesRemoved; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileInfo fileInfo = (FileInfo) o;
        return Objects.equals(filePath, fileInfo.filePath) &&
               Objects.equals(fileHash, fileInfo.fileHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filePath, fileHash);
    }
} 