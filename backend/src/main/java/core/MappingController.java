package core;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/mappings")
public class MappingController {
    
    @Autowired
    private MappingComparator mappingComparator;

    @PostMapping("/compare")
    public ResponseEntity<String> compareMappings(@RequestBody VersionCompareRequest request) {
        try {
            // Convert clean version to Yarn mapping version format
            String version1 = convertToYarnVersion(request.getCleanVersion());
            String version2 = convertToYarnVersion(request.getMcVersion());
            
            mappingComparator.compareYarnMappings(version1, version2);
            return ResponseEntity.ok("Comparison completed successfully");
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body("Error comparing mappings: " + e.getMessage());
        }
    }

    private String convertToYarnVersion(String version) {
        // Map of known versions to their Yarn build numbers
        Map<String, String> yarnBuilds = new HashMap<>();
        yarnBuilds.put("1.21.4", "1.21.4+build.8");
        yarnBuilds.put("1.21.3", "1.21.3+build.2");
        yarnBuilds.put("1.21.2", "1.21.2+build.1");
        yarnBuilds.put("1.21.1", "1.21.1+build.3");
        yarnBuilds.put("1.21", "1.21+build.9");

        String yarnVersion = yarnBuilds.get(version);
        if (yarnVersion == null) {
            throw new IllegalArgumentException("Unsupported Minecraft version: " + version);
        }
        return yarnVersion;
    }
}

// Request DTO
class VersionCompareRequest {
    private String cleanVersion;
    private String mcVersion;

    // Getters and setters
    public String getCleanVersion() { return cleanVersion; }
    public void setCleanVersion(String cleanVersion) { this.cleanVersion = cleanVersion; }
    public String getMcVersion() { return mcVersion; }
    public void setMcVersion(String mcVersion) { this.mcVersion = mcVersion; }
} 