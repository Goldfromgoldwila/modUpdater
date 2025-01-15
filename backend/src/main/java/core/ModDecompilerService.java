package core;

import org.benf.cfr.reader.api.CfrDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.stream.Stream;
import jakarta.annotation.PreDestroy;

@Service
public class ModDecompilerService {
    private static final Logger logger = LoggerFactory.getLogger(ModDecompilerService.class);
    private static final String UPLOAD_DIR = "uploaded_mods";
    private static final String DECOMPILED_DIR = "decompiled_mods";
    private static final Pattern VERSION_PATTERN = Pattern.compile("_(\\d+)\\.");
    private static final int BUFFER_SIZE = 8192;
    private static final int THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();

    private String currentModName;
    private final ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

    @Autowired
    private ReadModFile readModFile;

    public void decompileLatestMod() {
        try {
            File latestMod = findLatestMod();
            if (latestMod == null) {
                logger.info("No mods found in upload directory");
                return;
            }

            logger.info("Found latest mod: {}", latestMod.getName());
            currentModName = latestMod.getName().replaceFirst("\\.jar$", "");
            File modDecompiledDir = new File(DECOMPILED_DIR, currentModName);
            modDecompiledDir.mkdirs();

            CompletableFuture<Void> extractionFuture = CompletableFuture.runAsync(() -> {
                try {
                    extractNonClassFiles(latestMod, modDecompiledDir);
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            }, executorService);

            CompletableFuture<Void> decompilationFuture = CompletableFuture.runAsync(() -> {
                decompileClassFiles(latestMod, modDecompiledDir);
            }, executorService);

            CompletableFuture.allOf(extractionFuture, decompilationFuture).join();
            logger.info("Decompilation completed for mod: {}", currentModName);

            processDecompiledFiles(currentModName);

        } catch (Exception e) {
            logger.error("Error during decompilation process", e);
            throw new RuntimeException("Decompilation failed", e);
        }
    }

    private void extractNonClassFiles(File jarFile, File outputDir) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(
                new FileInputStream(jarFile), BUFFER_SIZE))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                
                if (name.endsWith(".class")) {
                    continue;
                }

                File outFile = new File(outputDir, name);
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                    continue;
                }

                outFile.getParentFile().mkdirs();

                try (BufferedOutputStream bos = new BufferedOutputStream(
                        new FileOutputStream(outFile), BUFFER_SIZE)) {
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        bos.write(buffer, 0, len);
                    }
                }
            }
        }
        logger.info("Non-class files extracted successfully");
    }

    private void decompileClassFiles(File jarFile, File outputDir) {
        Map<String, String> options = new HashMap<>();
        options.put("renameillegalidents", "true");
        options.put("decodestringswitch", "true");
        options.put("sugarenums", "true");
        options.put("decodelambdas", "true");
        options.put("outputdir", outputDir.getAbsolutePath());
        options.put("silent", "true");
        options.put("recover", "true");
        options.put("forcetopsort", "true");
        options.put("forcetopsortaggress", "true");

        CfrDriver driver = new CfrDriver.Builder()
            .withOptions(options)
            .build();

        logger.info("Starting class files decompilation");
        driver.analyse(Collections.singletonList(jarFile.getAbsolutePath()));
        logger.info("Class files decompiled successfully");
    }

    private File findLatestMod() throws IOException {
        Path uploadPath = Paths.get(UPLOAD_DIR);
        if (!Files.exists(uploadPath)) {
            logger.info("Upload directory does not exist");
            Files.createDirectories(uploadPath);
            return null;
        }

        try (Stream<Path> paths = Files.list(uploadPath)) {
            return paths.filter(path -> path.toString().toLowerCase().endsWith(".jar"))
                       .map(Path::toFile)
                       .max(Comparator.comparingLong(File::lastModified))
                       .orElse(null);
        }
    }

    @PreDestroy
    public void cleanup() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public String getDecompiledPath() {
        return DECOMPILED_DIR;
    }

    public String getCurrentModName() {
        return currentModName;
    }

    public List<ModFile> processDecompiledFiles(String version) {
        try {
            if (currentModName == null) {
                logger.warn("No mod has been decompiled yet");
                return Collections.emptyList();
            }
            
            logger.info("Processing decompiled files for mod: {}", currentModName);
            List<ModFile> modFiles = readModFile.scanModFiles(version);
            logger.info("Successfully processed {} files", modFiles.size());
            return modFiles;
            
        } catch (Exception e) {
            logger.error("Error processing decompiled files", e);
            throw new RuntimeException("Failed to process decompiled files", e);
        }
    }
}