package core.controller;

import core.model.FileChange;
import core.service.FileChangeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/changes")
@CrossOrigin(origins = "*")
public class FileChangeController {
    private static final Logger logger = LoggerFactory.getLogger(FileChangeController.class);
    private final FileChangeService fileChangeService;

    @Autowired
    public FileChangeController(FileChangeService fileChangeService) {
        this.fileChangeService = fileChangeService;
    }

    @GetMapping
    public ResponseEntity<List<FileChange>> getAllChanges(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String version) {
        try {
            List<FileChange> changes = fileChangeService.getAllChanges();
            if (status != null) {
                changes = changes.stream()
                    .filter(c -> c.getStatus().equals(status))
                    .toList();
            }
            if (version != null) {
                changes = changes.stream()
                    .filter(c -> c.getSourceVersion().equals(version) || 
                               c.getTargetVersion().equals(version))
                    .toList();
            }
            return ResponseEntity.ok(changes);
        } catch (Exception e) {
            logger.error("Error retrieving changes: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{sourceVersion}/{targetVersion}")
    public ResponseEntity<FileChange> getChange(
            @PathVariable String sourceVersion,
            @PathVariable String targetVersion) {
        try {
            FileChange change = fileChangeService.getChange(sourceVersion, targetVersion);
            return change != null ? 
                   ResponseEntity.ok(change) : 
                   ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error retrieving change: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/{sourceVersion}/{targetVersion}")
    public ResponseEntity<Void> deleteChange(
            @PathVariable String sourceVersion,
            @PathVariable String targetVersion) {
        boolean deleted = fileChangeService.deleteChange(sourceVersion, targetVersion);
        return deleted ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/backup")
    public ResponseEntity<Void> createBackup() {
        fileChangeService.backupChanges();
        return ResponseEntity.ok().build();
    }
} 