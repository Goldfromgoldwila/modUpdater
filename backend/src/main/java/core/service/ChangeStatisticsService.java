package core.service;

import com.google.gson.Gson;
import core.model.FileChange;
import core.model.FileInfo;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ChangeStatisticsService {
    private final Gson gson = new Gson();
    
    public Map<String, String> calculateStatistics(FileChange change) {
        Map<String, String> stats = new HashMap<>();
        
        // Basic counts
        stats.put("totalFiles", String.valueOf(getTotalFiles(change)));
        stats.put("totalAdded", String.valueOf(change.getAddedFiles().size()));
        stats.put("totalModified", String.valueOf(change.getModifiedFiles().size()));
        stats.put("totalDeleted", String.valueOf(change.getDeletedFiles().size()));
        
        // File type statistics
        Map<String, Integer> extensionStats = getFileExtensionStats(change);
        stats.put("fileTypes", gson.toJson(extensionStats));
        
        // Size statistics
        long totalSize = calculateTotalSize(change);
        stats.put("totalSize", String.valueOf(totalSize));
        
        // Line changes
        int totalLinesAdded = change.getModifiedFiles().stream()
            .mapToInt(FileInfo::getLinesAdded)
            .sum();
        int totalLinesRemoved = change.getModifiedFiles().stream()
            .mapToInt(FileInfo::getLinesRemoved)
            .sum();
        
        stats.put("totalLinesAdded", String.valueOf(totalLinesAdded));
        stats.put("totalLinesRemoved", String.valueOf(totalLinesRemoved));
        
        return stats;
    }

    private int getTotalFiles(FileChange change) {
        return change.getAddedFiles().size() + 
               change.getModifiedFiles().size() + 
               change.getDeletedFiles().size();
    }

    private Map<String, Integer> getFileExtensionStats(FileChange change) {
        Map<String, Integer> stats = new HashMap<>();
        countExtensions(change.getAddedFiles(), stats);
        countExtensions(change.getModifiedFiles(), stats);
        return stats;
    }

    private void countExtensions(List<FileInfo> files, Map<String, Integer> stats) {
        files.stream()
            .map(FileInfo::getFileExtension)
            .forEach(ext -> stats.merge(ext, 1, Integer::sum));
    }

    private long calculateTotalSize(FileChange change) {
        long addedSize = change.getAddedFiles().stream()
            .mapToLong(FileInfo::getFileSize)
            .sum();
        long modifiedSize = change.getModifiedFiles().stream()
            .mapToLong(FileInfo::getFileSize)
            .sum();
        return addedSize + modifiedSize;
    }
} 