package core.Config;

import org.springframework.stereotype.Component;

@Component
public class DirectoryConfig {
    public static final String BASE_DIR = "mods_storage";
    public static final String UPLOAD_DIR = BASE_DIR + "/uploaded_mods";
    public static final String DECOMPILED_DIR = BASE_DIR + "/decompiled_mods";
    public static final String DIFF_DIR = BASE_DIR + "/diff_results";
} 