package core;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.*;
import java.util.*;

@Component
public class CodeAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(CodeAnalyzer.class);
    
    public static class MethodSignature {
        String name;
        String returnType;
        List<String> parameters;
        String visibility;
        
        public MethodSignature(String name, String returnType, List<String> parameters, String visibility) {
            this.name = name;
            this.returnType = returnType;
            this.parameters = parameters;
            this.visibility = visibility;
        }
        
        @Override
        public String toString() {
            return String.format("%s %s %s(%s)", visibility, returnType, name, String.join(", ", parameters));
        }
    }
    
    public static class ClassInfo {
        String name;
        String packageName;
        List<MethodSignature> methods;
        List<String> interfaces;
        String superClass;
        
        public ClassInfo(String name, String packageName) {
            this.name = name;
            this.packageName = packageName;
            this.methods = new ArrayList<>();
            this.interfaces = new ArrayList<>();
        }
    }

    public Map<String, ClassInfo> analyzeDecompiledCode(Path decompPath) {
        Map<String, ClassInfo> classMap = new HashMap<>();
        
        try {
            Files.walk(decompPath)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        analyzeJavaFile(path, classMap);
                    } catch (Exception e) {
                        LOGGER.error("Error analyzing file {}: {}", path, e.getMessage());
                    }
                });
        } catch (Exception e) {
            LOGGER.error("Error walking through decompiled files: {}", e.getMessage());
        }
        
        return classMap;
    }

    private void analyzeJavaFile(Path path, Map<String, ClassInfo> classMap) {
        try (FileInputStream in = new FileInputStream(path.toFile())) {
            CompilationUnit cu = new JavaParser().parse(in).getResult().orElse(null);
            if (cu == null) return;

            String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getName().asString())
                .orElse("");

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
                ClassInfo classInfo = new ClassInfo(classDecl.getNameAsString(), packageName);
                
                // Get interfaces and superclass
                classDecl.getImplementedTypes().forEach(impl -> 
                    classInfo.interfaces.add(impl.getNameAsString()));
                classDecl.getExtendedTypes().forEach(ext -> 
                    classInfo.superClass = ext.getNameAsString());
                
                // Get methods
                classDecl.getMethods().forEach(method -> {
                    MethodSignature signature = new MethodSignature(
                        method.getNameAsString(),
                        method.getType().asString(),
                        method.getParameters().stream()
                            .map(p -> p.getType().asString())
                            .toList(),
                        method.getAccessSpecifier().asString()
                    );
                    classInfo.methods.add(signature);
                });
                
                String fullClassName = packageName + "." + classInfo.name;
                classMap.put(fullClassName, classInfo);
            });
        } catch (Exception e) {
            LOGGER.error("Error parsing Java file {}: {}", path, e.getMessage());
        }
    }
} 