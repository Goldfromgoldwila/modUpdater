package core.service;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.security.MessageDigest;
import java.util.zip.ZipOutputStream;
import java.lang.reflect.Type;
import com.google.gson.reflect.TypeToken;
import core.model.*;

@Service
@EnableScheduling
public class FileChangeService {
    private static final Logger logger = LoggerFactory.getLogger(FileChangeService.class);
    private static final String CHANGES_DIR = "changes";
    private static final String BACKUP_DIR = "changes_backup";
    private final Map<String, FileChange> changeHistory = new ConcurrentHashMap<>();
    private final ExecutorService executorService;
    private final Gson gson;

    public FileChangeService() {
        this.executorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors()
        );
        this.gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
            .setPrettyPrinting()
            .create();
        initializeDirectories();
        loadChangeHistory();
    }

    private void initializeDirectories() {
        try {
            Files.createDirectories(Paths.get(CHANGES_DIR));
            Files.createDirectories(Paths.get(BACKUP_DIR));
        } catch (IOException e) {
            logger.error("Failed to create directories: {}", e.getMessage());
            throw new RuntimeException("Failed to initialize directories", e);
        }
    }

    public CompletableFuture<FileChange> processChangeAsync(FileChange change) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                processFileMetadata(change);
                calculateDiffs(change);
                updateStatistics(change);
                change.markAsComplete();
                saveChange(change);
                return change;
            } catch (Exception e) {
                change.markAsFailed(e.getMessage());
                logger.error("Error processing change: {}", e.getMessage());
                throw new CompletionException(e);
            }
        }, executorService);
    }

    private void processFileMetadata(FileChange change) {
        for (FileInfo file : change.getAddedFiles()) {
            enrichFileMetadata(file);
        }
        for (FileInfo file : change.getModifiedFiles()) {
            enrichFileMetadata(file);
        }
    }

    private void enrichFileMetadata(FileInfo file) {
        try {
            Path filePath = Paths.get(file.getFilePath());
            file.setFileSize(Files.size(filePath));
            file.setMimeType(Files.probeContentType(filePath));
            file.setBinary(!isTextFile(filePath));
            file.setFileHash(calculateFileHash(filePath));
        } catch (IOException e) {
            logger.error("Error processing file metadata: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 * * * *") // Every hour
    public void autoBackup() {
        try {
            String backupFileName = String.format("changes_backup_%s.zip", 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
            Path backupPath = Paths.get(BACKUP_DIR, backupFileName);
            
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(backupPath))) {
                // Implement zip backup logic
            }
            
            // Clean up old backups
            cleanupOldBackups();
        } catch (IOException e) {
            logger.error("Backup failed: {}", e.getMessage());
        }
    }

    private void cleanupOldBackups() {
        try {
            Path backupDir = Paths.get(BACKUP_DIR);
            List<Path> backups = Files.list(backupDir)
                .sorted((p1, p2) -> -p1.getFileName().toString().compareTo(p2.getFileName().toString()))
                .collect(Collectors.toList());

            // Keep only last 5 backups
            if (backups.size() > 5) {
                backups.subList(5, backups.size())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            logger.error("Failed to delete old backup: {}", e.getMessage());
                        }
                    });
            }
        } catch (IOException e) {
            logger.error("Cleanup failed: {}", e.getMessage());
        }
    }

    private boolean isTextFile(Path filePath) {
        try {
            String mimeType = Files.probeContentType(filePath);
            return mimeType != null && mimeType.startsWith("text/");
        } catch (IOException e) {
            logger.error("Error detecting file type: {}", e.getMessage());
            return false;
        }
    }

    private String calculateFileHash(Path filePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fileBytes = Files.readAllBytes(filePath);
            byte[] hashBytes = digest.digest(fileBytes);
            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (Exception e) {
            logger.error("Error calculating file hash: {}", e.getMessage());
            return null;
        }
    }

    private void calculateDiffs(FileChange change) {
        // Implement diff calculation logic here
        // This is a placeholder for the actual implementation
        logger.info("Calculating diffs for change between {} and {}", 
            change.getSourceVersion(), change.getTargetVersion());
    }

    private void loadChangeHistory() {
        Path changesFile = Paths.get(CHANGES_DIR, "changes_history.json");
        if (Files.exists(changesFile)) {
            try {
                String json = Files.readString(changesFile);
                Type listType = new TypeToken<List<FileChange>>(){}.getType();
                List<FileChange> changes = gson.fromJson(json, listType);
                changes.forEach(change -> 
                    changeHistory.put(change.getSourceVersion() + "_to_" + change.getTargetVersion(), change)
                );
            } catch (IOException e) {
                logger.error("Error loading change history: {}", e.getMessage());
            }
        }
    }

    public List<FileChange> getAllChanges() {
        return new ArrayList<>(changeHistory.values());
    }

    public FileChange getChange(String sourceVersion, String targetVersion) {
        return changeHistory.get(sourceVersion + "_to_" + targetVersion);
    }

    public void saveChange(FileChange change) {
        String changeId = change.getSourceVersion() + "_to_" + change.getTargetVersion();
        changeHistory.put(changeId, change);
        persistChanges();
    }

    private void persistChanges() {
        try {
            String json = gson.toJson(new ArrayList<>(changeHistory.values()));
            Files.write(Paths.get(CHANGES_DIR, "changes_history.json"), json.getBytes());
        } catch (IOException e) {
            logger.error("Error persisting changes: {}", e.getMessage());
        }
    }

    public void backupChanges() {
        try {
            String backupFileName = String.format("changes_backup_%s.zip", 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
            Path backupPath = Paths.get(BACKUP_DIR, backupFileName);
            
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(backupPath))) {
                // Implement zip backup logic
                String json = gson.toJson(new ArrayList<>(changeHistory.values()));
                zos.putNextEntry(new java.util.zip.ZipEntry("changes.json"));
                zos.write(json.getBytes());
            }
        } catch (IOException e) {
            logger.error("Backup failed: {}", e.getMessage());
        }
    }

    public boolean deleteChange(String sourceVersion, String targetVersion) {
        String key = sourceVersion + "_to_" + targetVersion;
        FileChange removed = changeHistory.remove(key);
        if (removed != null) {
            persistChanges();
            return true;
        }
        return false;
    }

    private void updateStatistics(FileChange change) {
        Map<String, String> stats = new HashMap<>();
        stats.put("totalFiles", String.valueOf(
            change.getAddedFiles().size() + 
            change.getModifiedFiles().size() + 
            change.getDeletedFiles().size()
        ));
        change.setStatistics(stats);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            executorService.shutdown();
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            super.finalize();
        }
    }
}

class LocalDateTimeAdapter implements JsonSerializer<LocalDateTime>, JsonDeserializer<LocalDateTime> {
    @Override
    public JsonElement serialize(LocalDateTime date, Type typeOfSrc, JsonSerializationContext context) {
        return new JsonPrimitive(date.toString());
    }

    @Override
    public LocalDateTime deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) {
        return LocalDateTime.parse(json.getAsString());
    }
} 