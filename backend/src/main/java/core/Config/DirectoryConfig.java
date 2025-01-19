package core.Config;

import org.springframework.stereotype.Component;
import java.nio.file.Paths;

@Component
public class DirectoryConfig {
    private static final String USER_HOME = System.getProperty("user.home");
    public static final String BASE_DIR = Paths.get(USER_HOME, "modupdater").toString();
    public static final String UPLOAD_DIR = Paths.get(BASE_DIR, "uploaded_mods").toString();
    public static final String DECOMPILED_DIR = Paths.get(BASE_DIR, "decompiled_mods").toString();
    public static final String DIFF_DIR = Paths.get(BASE_DIR, "diff_results").toString();
}