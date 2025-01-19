package core.Decompiler;

import org.benf.cfr.reader.api.CfrDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import core.Extracter.ExtractJson;
import core.Event.DecompilationCompleteEvent;
import org.springframework.web.multipart.MultipartFile;
import core.Config.DirectoryConfig;

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
import java.util.Optional;
import java.util.Comparator;

@Service
public class ModDecompilerService {
    private static final Logger logger = LoggerFactory.getLogger(ModDecompilerService.class);
    private static final String BASE_DIR = "mods_storage";
    private static final String UPLOAD_DIR = BASE_DIR + "/uploaded_mods";
    private static final String DECOMPILED_DIR = BASE_DIR + "/decompiled_mods";
    private static final Pattern VERSION_PATTERN = Pattern.compile("_(\\d+)\\.");
    private static final int BUFFER_SIZE = 8192;
    private static final int THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static final String MINECRAFT_DEPS_DIR = "minecraft_deps";

    private String currentModName;
    private final ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private ExtractJson extractJson;

    public ModDecompilerService() {
        createRequiredDirectories();
    }

    private void createRequiredDirectories() {
        try {
            Files.createDirectories(Paths.get(DirectoryConfig.BASE_DIR));
            Files.createDirectories(Paths.get(DirectoryConfig.UPLOAD_DIR));
            Files.createDirectories(Paths.get(DirectoryConfig.DECOMPILED_DIR));
            logger.info("Created required directories: {}, {}", 
                DirectoryConfig.UPLOAD_DIR, DirectoryConfig.DECOMPILED_DIR);
        } catch (IOException e) {
            logger.error("Failed to create directories: {}", e.getMessage());
            throw new RuntimeException("Failed to create required directories", e);
        }
    }

    public void decompileLatestMod() {
        try {
            Path uploadDir = Paths.get(DirectoryConfig.UPLOAD_DIR);
            Optional<Path> latestMod = Files.list(uploadDir)
                .filter(path -> path.toString().endsWith(".jar"))
                .max(Comparator.comparingLong(path -> {
                    try {
                        return Files.getLastModifiedTime(path).toMillis();
                    } catch (IOException e) {
                        return 0L;
                    }
                }));

            if (latestMod.isPresent()) {
                Path modPath = latestMod.get();
                decompileMod(modPath);
                // Create Path for decompiled directory
                Path decompiledPath = Paths.get(DirectoryConfig.DECOMPILED_DIR, 
                    modPath.getFileName().toString().replace(".jar", ""));
                eventPublisher.publishEvent(new DecompilationCompleteEvent(this, decompiledPath));
            } else {
                logger.error("No mod file found to decompile");
            }
        } catch (IOException e) {
            logger.error("Error decompiling latest mod: {}", e.getMessage());
            throw new RuntimeException("Failed to decompile latest mod", e);
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
        options.put("sugarenums", "true");
        options.put("decodelambdas", "true");
        options.put("outputdir", outputDir.getAbsolutePath());
        options.put("silent", "true");
        options.put("recover", "true");
        options.put("forcetopsort", "true");
        options.put("forcetopsortaggress", "true");

        // Add required dependencies to classpath
        List<String> classpath = new ArrayList<>();
        classpath.add(jarFile.getAbsolutePath());
        
        // Add Minecraft dependencies
        File minecraftDeps = new File(MINECRAFT_DEPS_DIR, currentModName);
        if (minecraftDeps.exists()) {
            try (Stream<Path> paths = Files.walk(minecraftDeps.toPath())) {
                paths.filter(path -> path.toString().endsWith(".jar"))
                     .forEach(path -> classpath.add(path.toString()));
            } catch (IOException e) {
                logger.warn("Failed to add Minecraft dependencies: {}", e.getMessage());
            }
        }

        // Add common dependencies
        addCommonDependencies(classpath);
        
        // Set classpath option
        options.put("extraclasspath", String.join(File.pathSeparator, classpath));

        try {
            CfrDriver driver = new CfrDriver.Builder()
                .withOptions(options)
                .build();

            logger.info("Starting decompilation with {} dependencies", classpath.size());
            driver.analyse(Collections.singletonList(jarFile.getAbsolutePath()));
            logger.info("Decompilation completed successfully");
        } catch (Exception e) {
            logger.error("Decompilation failed: {}", e.getMessage());
        }
    }

    private void addCommonDependencies(List<String> classpath) {
        // Add common dependencies like ASM, JetBrains annotations, etc.
        String[] commonDeps = {
            "org.spongepowered:mixin:0.8.5",
            "org.jetbrains:annotations:23.0.0"
        };
        
        for (String dep : commonDeps) {
            try {
                String path = resolveArtifact(dep);
                if (path != null) {
                    classpath.add(path);
                }
            } catch (Exception e) {
                logger.warn("Failed to resolve dependency {}: {}", dep, e.getMessage());
            }
        }
    }

    private String resolveArtifact(String coordinates) {
        // Use Maven Resolver to get the path to the dependency
        try {
            String[] parts = coordinates.split(":");
            String groupId = parts[0];
            String artifactId = parts[1];
            String version = parts[2];
            
            // This is a simplified version - you might want to use proper Maven Resolver API
            String m2Home = System.getProperty("user.home") + "/.m2/repository";
            String path = String.format("%s/%s/%s/%s/%s-%s.jar",
                m2Home,
                groupId.replace('.', '/'),
                artifactId,
                version,
                artifactId,
                version);
                
            return new File(path).exists() ? path : null;
        } catch (Exception e) {
            logger.warn("Failed to resolve artifact {}: {}", coordinates, e.getMessage());
            return null;
        }
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

    private void decompileMod(Path modPath) {
        try {
            String fileName = modPath.getFileName().toString();
            Path outputDir = Paths.get(DirectoryConfig.DECOMPILED_DIR, fileName.replace(".jar", ""));
            
            if (!Files.exists(outputDir)) {
                Files.createDirectories(outputDir);
            }

            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(modPath))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    Path entryPath = outputDir.resolve(entry.getName());
                    
                    if (entry.isDirectory()) {
                        Files.createDirectories(entryPath);
                    } else {
                        Files.createDirectories(entryPath.getParent());
                        Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            logger.info("Decompiled mod to: {}", outputDir);
        } catch (IOException e) {
            logger.error("Error decompiling mod: {}", e.getMessage());
            throw new RuntimeException("Failed to decompile mod", e);
        }
    }

    public void handleFileUpload(MultipartFile file) {
        try {
            this.currentModName = file.getOriginalFilename();
            Path filePath = Paths.get(DirectoryConfig.UPLOAD_DIR, this.currentModName);
            
            // Create the file if it doesn't exist
            if (!Files.exists(filePath)) {
                Files.createFile(filePath);
            }

            // Save the uploaded file
            file.transferTo(filePath.toFile());
            logger.info("Successfully uploaded mod: {} to {}", this.currentModName, filePath);
        } catch (IOException e) {
            logger.error("Error handling file upload: {}", e.getMessage());
            throw new RuntimeException("Failed to handle file upload", e);
        }
    }
}