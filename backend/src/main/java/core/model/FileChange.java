package core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import core.model.FileInfo;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileChange {
    private String sourceVersion;
    private String targetVersion;
    private LocalDateTime timestamp;
    private List<FileInfo> addedFiles = new ArrayList<>();
    private List<FileInfo> modifiedFiles = new ArrayList<>();
    private List<FileInfo> deletedFiles = new ArrayList<>();
    private Map<String, String> statistics = new HashMap<>();
    private String status;
    private String diffReportPath;
    private String errorMessage;

    public FileChange(String sourceVersion, String targetVersion) {
        this.sourceVersion = sourceVersion;
        this.targetVersion = targetVersion;
        this.timestamp = LocalDateTime.now();
    }

    // Getters and setters
    public String getSourceVersion() { return sourceVersion; }
    public String getTargetVersion() { return targetVersion; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public List<FileInfo> getAddedFiles() { return addedFiles; }
    public List<FileInfo> getModifiedFiles() { return modifiedFiles; }
    public List<FileInfo> getDeletedFiles() { return deletedFiles; }
    public Map<String, String> getStatistics() { return statistics; }
    public String getStatus() { return status; }
    public String getDiffReportPath() { return diffReportPath; }
    public String getErrorMessage() { return errorMessage; }

    public void setStatistics(Map<String, String> statistics) { this.statistics = statistics; }
    public void setStatus(String status) { this.status = status; }
    public void setDiffReportPath(String path) { this.diffReportPath = path; }
    public void setErrorMessage(String error) { this.errorMessage = error; }

    public void markAsComplete() {
        this.status = "SUCCESS";
    }

    public void markAsFailed(String error) {
        this.status = "FAILED";
        this.errorMessage = error;
    }

    public void setAddedFiles(List<FileInfo> addedFiles) {
        this.addedFiles = addedFiles;
    }

    public void setModifiedFiles(List<FileInfo> modifiedFiles) {
        this.modifiedFiles = modifiedFiles;
    }

    public void setDeletedFiles(List<FileInfo> deletedFiles) {
        this.deletedFiles = deletedFiles;
    }
}