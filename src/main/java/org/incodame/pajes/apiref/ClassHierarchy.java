package org.incodame.pajes.apiref;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

final class ClassHierarchy {

    static final String ANALYSIS_CONTEXT_KEY = "pajes.class.hierarchy";

    private final Map<String, List<String>> directSupertypes;

    private ClassHierarchy(Map<String, List<String>> directSupertypes) {
        this.directSupertypes = Collections.unmodifiableMap(directSupertypes);
    }

    static ClassHierarchy read(File jarFile) throws IOException {
        Map<String, List<String>> hierarchy = new HashMap<>();
        try (JarFile jar = new JarFile(jarFile)) {
            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")
                        || entry.getName().startsWith("META-INF/versions/")) {
                    continue;
                }

                try (InputStream input = jar.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(input);
                    List<String> supertypes = new ArrayList<>();
                    reader.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(int version, int access, String name, String signature,
                                String superName, String[] interfaces) {
                            if ((access & Opcodes.ACC_INTERFACE) == 0 && superName != null) {
                                supertypes.add(superName.replace('/', '.'));
                            }
                            for (String interfaceName : interfaces) {
                                supertypes.add(interfaceName.replace('/', '.'));
                            }
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    hierarchy.put(reader.getClassName().replace('/', '.'), supertypes);
                }
            }
        }
        return new ClassHierarchy(hierarchy);
    }

    boolean contains(String className) {
        return directSupertypes.containsKey(className);
    }

    List<String> getDirectSupertypes(String className) {
        return directSupertypes.get(className);
    }
}
