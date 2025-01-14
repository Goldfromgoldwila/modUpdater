package core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VersionParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(VersionParser.class);
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");

    public static String cleanVersion(String versionString) {
        try {
            // Remove any operators and trim
            String cleanString = versionString.replaceAll("[>=<]", "").trim();
            
            // Find all version numbers in the string
            Matcher matcher = VERSION_PATTERN.matcher(cleanString);
            String detailedVersion = null;
            
            // Find the most detailed version (with more dots)
            while (matcher.find()) {
                String version = matcher.group(1);
                if (detailedVersion == null || 
                    countDots(version) > countDots(detailedVersion)) {
                    detailedVersion = version;
                }
            }

            LOGGER.info("Original version: '{}' -> Clean version: '{}'", 
                versionString, detailedVersion);
            return detailedVersion;

        } catch (Exception e) {
            LOGGER.error("Error cleaning version string '{}': {}", 
                versionString, e.getMessage());
            return versionString;
        }
    }

    private static int countDots(String version) {
        return version.length() - version.replace(".", "").length();
    }
} 