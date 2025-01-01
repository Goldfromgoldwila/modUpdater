package core;

import org.benf.cfr.reader.api.CfrDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ModDecompilerService {
    private static final Logger logger = LoggerFactory.getLogger(ModDecompilerService.class);
    private static final String UPLOAD_DIR = "uploaded_mods";
    private static final String DECOMPILED_DIR = "decompiled_mods";
    private static final Pattern VERSION_PATTERN = Pattern.compile("_(\\d+)\\.");

    public void decompileLatestMod() {
        try {
            // Find the latest uploaded mod
            File latestMod = findLatestMod();
            if (latestMod == null) {
                logger.info("No mods found in upload directory");
                return;
            }

            // Log the mod name
            logger.info("Found latest mod: {}", latestMod.getName());

            // Create mod-specific decompiled directory
            String modName = latestMod.getName().replaceFirst("\\.jar$", "");
            File modDecompiledDir = new File(DECOMPILED_DIR, modName);
            if (!modDecompiledDir.exists()) {
                modDecompiledDir.mkdirs();
            }

            // First, extract all non-class files
            extractNonClassFiles(latestMod, modDecompiledDir);

            // Then decompile class files
            decompileClassFiles(latestMod, modDecompiledDir);

            logger.info("Decompilation and resource extraction completed successfully");

        } catch (Exception e) {
            logger.info("Error during decompilation process", e);
            throw new RuntimeException("Decompilation failed", e);
        }
    }

    private void extractNonClassFiles(File jarFile, File outputDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(jarFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                
                // Skip class files as they will be decompiled separately
                if (name.endsWith(".class")) {
                    continue;
                }

                // Create directories if needed
                File outFile = new File(outputDir, name);
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                    continue;
                }

                // Create parent directories if they don't exist
                outFile.getParentFile().mkdirs();

                // Extract the file
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }
            }
        }
        logger.info("Non-class files extracted successfully");
    }

    private void decompileClassFiles(File jarFile, File outputDir) {
        // Configure CFR options
        Map<String, String> options = new HashMap<>();
        options.put("renameillegalidents", "true");
        options.put("decodestringswitch", "true");
        options.put("sugarenums", "true");
        options.put("decodelambdas", "true");
        options.put("outputdir", outputDir.getAbsolutePath());

        // Create CFR driver
        CfrDriver driver = new CfrDriver.Builder()
            .withOptions(options)
            .build();

        // Run decompilation
        logger.info("Starting class files decompilation");
        driver.analyse(Collections.singletonList(jarFile.getAbsolutePath()));
        logger.info("Class files decompiled successfully");
    }

    private File findLatestMod() throws IOException {
        Path uploadPath = Paths.get(UPLOAD_DIR);
        if (!Files.exists(uploadPath)) {
            logger.info("Upload directory does not exist");
            Files.createDirectories(uploadPath);
        }
    
        Optional<File> latestMod = Files.list(uploadPath)
            .filter(path -> path.toString().toLowerCase().endsWith(".jar"))
            .map(Path::toFile)
            .max(Comparator.comparingLong(File::lastModified)); // Changed to use last modified time
    
        if (latestMod.isPresent()) {
            logger.info("Found latest mod file: " + latestMod.get().getName());
            return latestMod.get();
        } else {
            logger.info("No mod files found in upload directory");
            return null;
        }
    }

    public String getDecompiledPath() {
        return DECOMPILED_DIR;
    }
}