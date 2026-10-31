package org.incodame.pajes.apiref;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import spoon.Launcher;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.reference.CtTypeReference;
import spoon.support.compiler.VirtualFile;

final class SourceJavadocIndex {

    static final class ExecutableDocumentation {
        private final String comment;
        private final List<ParameterDocumentation> parameters;

        private ExecutableDocumentation(String comment, List<ParameterDocumentation> parameters) {
            this.comment = comment;
            this.parameters = List.copyOf(parameters);
        }

        String getComment() {
            return comment;
        }

        List<ParameterDocumentation> getParameters() {
            return parameters;
        }
    }

    static final class ParameterDocumentation {
        private final String name;
        private final String comment;

        private ParameterDocumentation(String name, String comment) {
            this.name = name;
            this.comment = comment;
        }

        String getName() {
            return name;
        }

        String getComment() {
            return comment;
        }
    }

    private static final class ExecutableKey {
        private final String typeName;
        private final String memberName;
        private final List<String> parameterTypes;

        private ExecutableKey(String typeName, String memberName, List<String> parameterTypes) {
            this.typeName = typeName;
            this.memberName = memberName;
            this.parameterTypes = List.copyOf(parameterTypes);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ExecutableKey)) {
                return false;
            }
            ExecutableKey that = (ExecutableKey) other;
            return typeName.equals(that.typeName) && memberName.equals(that.memberName)
                    && parameterTypes.equals(that.parameterTypes);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(typeName, memberName, parameterTypes);
        }
    }

    private final Map<ExecutableKey, ExecutableDocumentation> documentation;

    private SourceJavadocIndex(Map<ExecutableKey, ExecutableDocumentation> documentation) {
        this.documentation = Collections.unmodifiableMap(new LinkedHashMap<>(documentation));
    }

    static SourceJavadocIndex empty() {
        return new SourceJavadocIndex(Map.of());
    }

    static SourceJavadocIndex read(File sourcesJar) throws IOException {
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(true);
        int sourceCount = 0;
        try (ZipFile archive = new ZipFile(sourcesJar)) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".java")) {
                    try (InputStream input = archive.getInputStream(entry)) {
                        String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                        launcher.addInputResource(new VirtualFile(source, entry.getName()));
                        sourceCount++;
                    }
                }
            }
        }
        if (sourceCount == 0) {
            throw new IOException("Sources JAR contains no Java source files: " + sourcesJar);
        }

        try {
            Map<ExecutableKey, ExecutableDocumentation> index = new LinkedHashMap<>();
            for (CtType<?> type : launcher.buildModel().getAllTypes()) {
                for (CtTypeMember member : type.getTypeMembers()) {
                    if (member instanceof CtMethod<?> || member instanceof CtConstructor<?>) {
                        CtExecutable<?> executable = (CtExecutable<?>) member;
                        String name = member instanceof CtConstructor<?>
                                ? type.getSimpleName() : executable.getSimpleName();
                        List<String> parameterTypes = new ArrayList<>();
                        for (CtParameter<?> parameter : executable.getParameters()) {
                            parameterTypes.add(normalizeTypeName(parameter.getType()));
                        }
                        index.put(new ExecutableKey(type.getQualifiedName(), name, parameterTypes),
                                parseDocumentation(executable));
                    }
                }
            }
            return new SourceJavadocIndex(index);
        } catch (RuntimeException e) {
            throw new IOException("Cannot parse Java sources in " + sourcesJar, e);
        }
    }

    ExecutableDocumentation find(String qualifiedTypeName, String memberName, List<String> parameterTypes) {
        ExecutableDocumentation exact = documentation.get(
                new ExecutableKey(qualifiedTypeName, memberName, normalizeTypeNames(parameterTypes)));
        if (exact != null) {
            return exact;
        }

        List<ExecutableDocumentation> candidates = documentation.entrySet().stream()
                .filter(entry -> entry.getKey().typeName.equals(qualifiedTypeName)
                        && entry.getKey().memberName.equals(memberName)
                        && entry.getKey().parameterTypes.size() == parameterTypes.size())
                .map(Map.Entry::getValue)
                .toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static List<String> normalizeTypeNames(List<String> typeNames) {
        return typeNames.stream().map(SourceJavadocIndex::normalizeTypeName).toList();
    }

    private static String normalizeTypeName(CtTypeReference<?> type) {
        return normalizeTypeName(type.getQualifiedName());
    }

    private static String normalizeTypeName(String typeName) {
        return typeName.replace('$', '.').replace("...", "[]").replaceAll("\\s+", "");
    }

    private static ExecutableDocumentation parseDocumentation(CtExecutable<?> executable) {
        String docComment = executable.getDocComment();
        String description = null;
        Map<String, StringBuilder> parameterComments = new LinkedHashMap<>();
        if (docComment != null && !docComment.isBlank()) {
            List<String> descriptionLines = new ArrayList<>();
            String currentParameter = null;
            for (String line : cleanCommentLines(docComment.replaceAll(
                    "\\s+@(param|return|throws|exception|since|deprecated|see|author|version|serial|apiNote|implSpec|implNote)\\b",
                    "\n@$1"))) {
                String trimmed = line.trim();
                if (trimmed.startsWith("@")) {
                    currentParameter = null;
                    if (trimmed.startsWith("@param ")) {
                        String paramText = trimmed.substring("@param ".length()).trim();
                        String[] parts = paramText.split("\\s+", 2);
                        if (parts.length > 0 && !parts[0].startsWith("<")) {
                            currentParameter = parts[0];
                            parameterComments.putIfAbsent(currentParameter, new StringBuilder());
                            if (parts.length > 1) {
                                appendLine(parameterComments.get(currentParameter), parts[1]);
                            }
                        }
                    }
                } else if (currentParameter != null) {
                    appendLine(parameterComments.get(currentParameter), trimmed);
                } else {
                    descriptionLines.add(trimmed);
                }
            }
            description = nonEmpty(String.join(" ", descriptionLines));
        }

        List<ParameterDocumentation> parameters = new ArrayList<>();
        for (CtParameter<?> parameter : executable.getParameters()) {
            String name = parameter.getSimpleName();
            StringBuilder comment = parameterComments.get(name);
            parameters.add(new ParameterDocumentation(name, comment == null ? null : nonEmpty(comment.toString())));
        }
        return new ExecutableDocumentation(description, parameters);
    }

    private static List<String> cleanCommentLines(String comment) {
        String cleaned = comment.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (cleaned.startsWith("/**")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("*/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2);
        }
        List<String> lines = new ArrayList<>();
        for (String line : cleaned.split("\n")) {
            lines.add(line.replaceFirst("^\\s*\\* ?", ""));
        }
        return lines;
    }

    private static void appendLine(StringBuilder target, String text) {
        if (text != null && !text.isBlank()) {
            if (target.length() > 0) {
                target.append(' ');
            }
            target.append(text.trim());
        }
    }

    private static String nonEmpty(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
