package org.incodame.pajes.apiref;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class JavaPatchToRdf {

    private static final Set<String> JAVA_LANG_TYPE_NAMES = Set.of(
            "Appendable", "AutoCloseable", "Boolean", "Byte", "CharSequence", "Character",
            "Class", "ClassLoader", "Cloneable", "Comparable", "Double", "Enum", "Error",
            "Exception", "Float", "Integer", "Iterable", "Long", "Math", "Number", "Object",
            "Process", "ProcessBuilder", "Record", "Runnable", "RuntimeException", "Short",
            "StackTraceElement", "String", "StringBuffer", "StringBuilder", "System",
            "Thread", "Throwable", "Void");

    // Espace de noms officiel de CodeOntology
    private static final String WOC_NS = "http://webofcode.org";
    // Espace de noms personnalisé pour votre projet / votre pont logique
    // The trailing slash keeps Turtle serializer output compatible with the icodm: prefix.
    private static final String EX_NS = "https://github.com/Incodam/";
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "\\b(class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");

    private static final class PatchFile {
        private final String oldPath;
        private final String newPath;
        private final List<String> lines;

        private PatchFile(String oldPath, String newPath, List<String> lines) {
            this.oldPath = oldPath;
            this.newPath = newPath;
            this.lines = lines;
        }
    }

    private static String buildMemberURI(String declaringType, String memberName, List<String> parameterTypes,
            String state) {
        String canonicalSignature = declaringType + "#" + memberName + "(" + String.join(",", parameterTypes) + ")";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalSignature.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (int index = 0; index < 6; index++) {
                hash.append(String.format("%02x", digest[index]));
            }
            return EX_NS + declaringType + "_" + memberName + "_" + hash + "_" + state;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static List<String> getParameterTypeNames(CtMethod<?> method) {
        List<String> parameterTypes = new ArrayList<>();
        for (CtParameter<?> parameter : method.getParameters()) {
            parameterTypes.add(parameter.getType().getQualifiedName());
        }
        return parameterTypes;
    }

    private static List<String> getParameterTypeNames(CtConstructor<?> constructor) {
        List<String> parameterTypes = new ArrayList<>();
        for (CtParameter<?> parameter : constructor.getParameters()) {
            parameterTypes.add(parameter.getType().getQualifiedName());
        }
        return parameterTypes;
    }

    private static Resource createParameterList(Model model, List<String> parameterTypes,
            Resource parameterClass, Property parameterIndex, Property parameterType) {
        List<RDFNode> parameterNodes = new ArrayList<>();
        for (int index = 0; index < parameterTypes.size(); index++) {
            Resource parameter = model.createResource()
                    .addProperty(RDF.type, parameterClass)
                    .addProperty(parameterIndex, model.createTypedLiteral(index))
                    .addProperty(parameterType, parameterTypes.get(index));
            parameterNodes.add(parameter);
        }
        return model.createList(parameterNodes.iterator());
    }

    private static List<String> resolvePatchParameterTypes(CtType<?> declaringType, List<String> patchTypes) {
        List<String> resolvedTypes = new ArrayList<>();
        for (String patchType : patchTypes) {
            String simpleTypeName = normalizeTypeName(patchType);
            String resolvedType = patchType;
            for (CtTypeReference<?> typeReference : declaringType.getElements(new TypeFilter<>(CtTypeReference.class))) {
                if (typeReference.getSimpleName().equals(simpleTypeName)) {
                    resolvedType = typeReference.getQualifiedName();
                    break;
                }
            }
            resolvedTypes.add(resolvedType);
        }
        return resolvedTypes;
    }

    private static String normalizeTypeName(String typeName) {
        if (typeName == null) {
            return "";
        }
        String trimmed = typeName.trim();
        // Patch declarations keep generics/varargs while Spoon erases them, so align both forms
        int genericStart = trimmed.indexOf('<');
        if (genericStart >= 0) {
            int genericEnd = trimmed.lastIndexOf('>');
            String suffix = genericEnd > genericStart ? trimmed.substring(genericEnd + 1) : "";
            trimmed = (trimmed.substring(0, genericStart) + suffix).trim();
        }
        trimmed = trimmed.replace("...", "[]");
        int lastDot = trimmed.lastIndexOf('.');
        return lastDot >= 0 ? trimmed.substring(lastDot + 1) : trimmed;
    }

    private static String qualifyJavaLangReturnType(String typeName) {
        int arrayStart = typeName.indexOf('[');
        String baseType = arrayStart >= 0 ? typeName.substring(0, arrayStart) : typeName;
        if (!JAVA_LANG_TYPE_NAMES.contains(baseType)) {
            return typeName;
        }
        String arraySuffix = arrayStart >= 0 ? typeName.substring(arrayStart) : "";
        return "java.lang." + baseType + arraySuffix;
    }

    private static String normalizeMethodSignature(String declaration) {
        if (declaration == null) {
            return "";
        }

        String trimmed = declaration.trim();
        int openParen = trimmed.indexOf('(');
        int closeParen = trimmed.lastIndexOf(')');
        if (openParen < 0 || closeParen < 0 || closeParen < openParen) {
            return "";
        }

        String prefix = trimmed.substring(0, openParen).trim();
        String paramsText = trimmed.substring(openParen + 1, closeParen).trim();
        String methodName = prefix.substring(prefix.lastIndexOf(' ') + 1).trim();

        List<String> paramTypes = new ArrayList<>();
        if (!paramsText.isEmpty()) {
            String[] params = paramsText.split(",");
            for (String param : params) {
                String cleaned = param.trim();
                if (cleaned.isEmpty()) {
                    continue;
                }
                int lastSpace = cleaned.lastIndexOf(' ');
                String type = lastSpace > -1 ? cleaned.substring(0, lastSpace).trim() : cleaned;
                paramTypes.add(normalizeTypeName(type));
            }
        }

        return methodName + "(" + String.join(",", paramTypes) + ")";
    }

    private static List<String> extractParameterTypesFromMethodSignature(String signature) {
        if (signature == null) {
            return List.of();
        }

        String trimmed = signature.trim();
        int openParen = trimmed.indexOf('(');
        int closeParen = trimmed.lastIndexOf(')');
        if (openParen < 0 || closeParen < 0 || closeParen < openParen) {
            return List.of();
        }

        String paramsText = trimmed.substring(openParen + 1, closeParen).trim();
        if (paramsText.isEmpty()) {
            return List.of();
        }

        List<String> paramTypes = new ArrayList<>();
        for (String param : paramsText.split(",")) {
            String cleaned = param.trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            int lastSpace = cleaned.lastIndexOf(' ');
            String type = lastSpace > -1 ? cleaned.substring(0, lastSpace).trim() : cleaned;
            paramTypes.add(type);
        }
        return paramTypes;
    }

        private static boolean isPatchedMethod(CtMethod<?> method, Set<String> addedSignatures,
            Set<String> removedSignatures) {
        List<String> parameterTypes = method.getParameters().stream()
                .map(p -> normalizeTypeName(p.getType().getQualifiedName()))
                .collect(java.util.stream.Collectors.toList());
        return hasExactPatchSignature(addedSignatures, method.getSimpleName(), parameterTypes)
            || hasExactPatchSignature(removedSignatures, method.getSimpleName(), parameterTypes);
    }

        private static boolean isPatchedConstructor(CtConstructor<?> constructor, Set<String> addedSignatures,
            Set<String> removedSignatures) {
        List<String> parameterTypes = constructor.getParameters().stream()
                .map(parameter -> normalizeTypeName(parameter.getType().getQualifiedName()))
                .collect(java.util.stream.Collectors.toList());
        String constructorName = constructor.getDeclaringType().getSimpleName();
        return hasExactPatchSignature(addedSignatures, constructorName, parameterTypes)
            || hasExactPatchSignature(removedSignatures, constructorName, parameterTypes);
    }

        private static List<String> findExactPatchParameterTypes(Set<String> signatures, String memberName,
            List<String> existingParameterTypes) {
        String prefix = memberName + "(";
        for (String signature : signatures) {
            if (!signature.startsWith(prefix) || !signature.endsWith(")")) {
                continue;
            }
            List<String> candidateTypes = extractParameterTypesFromMethodSignature(signature);
            if (candidateTypes.size() != existingParameterTypes.size()) {
                continue;
            }
            boolean matches = true;
            for (int index = 0; index < existingParameterTypes.size(); index++) {
                if (!normalizeTypeName(candidateTypes.get(index)).equals(existingParameterTypes.get(index))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return candidateTypes;
            }
        }
        return null;
    }

    private static List<String> findExtendedPatchParameterTypes(Set<String> signatures, String memberName,
            List<String> existingParameterTypes) {
        String prefix = memberName + "(";
        for (String signature : signatures) {
            if (!signature.startsWith(prefix) || !signature.endsWith(")")) {
                continue;
            }
            List<String> candidateTypes = extractParameterTypesFromMethodSignature(signature);
            if (candidateTypes.size() <= existingParameterTypes.size()) {
                continue;
            }
            boolean existingTypesMatch = true;
            for (int index = 0; index < existingParameterTypes.size(); index++) {
                if (!normalizeTypeName(candidateTypes.get(index)).equals(existingParameterTypes.get(index))) {
                    existingTypesMatch = false;
                    break;
                }
            }
            if (existingTypesMatch) {
                return candidateTypes;
            }
        }
        return null;
    }

    private static List<String> findClosestPatchParameterTypes(Set<String> signatures, String memberName,
            List<String> currentParameterTypes) {
        String prefix = memberName + "(";
        List<String> bestMatch = null;
        int bestSharedPrefixLength = -1;
        for (String signature : signatures) {
            if (!signature.startsWith(prefix) || !signature.endsWith(")")) {
                continue;
            }
            List<String> candidateTypes = extractParameterTypesFromMethodSignature(signature);
            int sharedPrefixLength = 0;
            while (sharedPrefixLength < candidateTypes.size() && sharedPrefixLength < currentParameterTypes.size()
                    && normalizeTypeName(candidateTypes.get(sharedPrefixLength))
                            .equals(normalizeTypeName(currentParameterTypes.get(sharedPrefixLength)))) {
                sharedPrefixLength++;
            }
            if (sharedPrefixLength > bestSharedPrefixLength) {
                bestSharedPrefixLength = sharedPrefixLength;
                bestMatch = candidateTypes;
            }
        }
        return bestMatch;
    }

    private static boolean hasExactPatchSignature(Set<String> signatures, String memberName,
            List<String> parameterTypes) {
        return findExactPatchParameterTypes(signatures, memberName, parameterTypes) != null;
    }

    private static Set<String> readPatchSignatures(List<String> lines, char marker) {
        Set<String> signatures = new HashSet<>();
        StringBuilder currentDeclaration = null;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("+") || trimmed.startsWith("-")) {
                if (trimmed.charAt(0) != marker) {
                    currentDeclaration = null;
                    continue;
                }
                String content = trimmed.substring(1).trim();
                if (content.isEmpty()) {
                    continue;
                }

                if (currentDeclaration == null && content.contains("(")
                        && (content.contains("public") || content.contains("protected") || content.contains("private"))) {
                    currentDeclaration = new StringBuilder(content);
                } else if (currentDeclaration != null) {
                    currentDeclaration.append(" ").append(content);
                }

                if (currentDeclaration != null && currentDeclaration.indexOf("(") >= 0
                        && currentDeclaration.indexOf(")") >= 0) {
                    String normalized = normalizeMethodSignature(currentDeclaration.toString());
                    if (!normalized.isEmpty()) {
                        signatures.add(normalized);
                    }
                    currentDeclaration = null;
                }
                continue;
            }

            if (currentDeclaration != null && (trimmed.startsWith("{") || trimmed.startsWith("}"))) {
                String normalized = normalizeMethodSignature(currentDeclaration.toString());
                if (!normalized.isEmpty()) {
                    signatures.add(normalized);
                }
                currentDeclaration = null;
            }
        }
        return signatures;
    }

    private static Map<String, String> readAddedConstructorDeclarations(List<String> lines) {
        Map<String, String> constructors = new HashMap<>();

        StringBuilder currentConstructor = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("+++") || !trimmed.startsWith("+")) {
                currentConstructor = null;
                continue;
            }

            String content = trimmed.substring(1).trim();
            if (currentConstructor == null && content.contains("(")
                    && (content.contains("public") || content.contains("protected") || content.contains("private"))) {
                currentConstructor = new StringBuilder(content);
            } else if (currentConstructor != null) {
                currentConstructor.append(" ").append(content);
            }

            if (currentConstructor != null && currentConstructor.indexOf("(") >= 0
                    && currentConstructor.indexOf(")") >= 0) {
                String normalized = normalizeMethodSignature(currentConstructor.toString());
                if (!normalized.isEmpty()) {
                    String declaration = currentConstructor.toString();
                    String visibility = declaration.contains("protected") ? "protected"
                            : declaration.contains("private") ? "private" : "public";
                    constructors.put(normalized, visibility);
                }
                currentConstructor = null;
            }
        }
        return constructors;
    }

    public static Model buildModelForFile(String sourcePath) {
        return buildModelForFile(sourcePath, (String) null);
    }

    public static Model buildModelForFile(String sourcePath, String patchPath) {
        List<String> patchLines;
        try {
            patchLines = patchPath == null || patchPath.trim().isEmpty()
                    ? List.of()
                    : Files.readAllLines(Paths.get(patchPath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read patch file: " + patchPath, e);
        }
        return buildModelForFile(sourcePath, patchLines);
    }

    private static Model buildModelForFile(String sourcePath, List<String> patchLines) {
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("woc", WOC_NS);
        model.setNsPrefix("icodm", EX_NS);
        model.setNsPrefix("rdf", RDF.getURI());
        model.setNsPrefix("owl", "http://www.w3.org/2002/07/owl#");
        model.setNsPrefix("rdfs", "http://www.w3.org/2000/01/rdf-schema#");

        Set<String> addedSignatures;
        Set<String> removedSignatures;
        Map<String, String> addedConstructorDeclarations;
        addedSignatures = readPatchSignatures(patchLines, '+');
        removedSignatures = readPatchSignatures(patchLines, '-');
        addedConstructorDeclarations = readAddedConstructorDeclarations(patchLines);

        Resource wocMethodClass = model.createResource(WOC_NS + "Method");
        Resource wocConstructorClass = model.createResource(WOC_NS + "Constructor");
        Resource wocParameterClass = model.createResource(WOC_NS + "Parameter");
        Resource wocClassClass = model.createResource(WOC_NS + "Class");
        Resource wocInterfaceClass = model.createResource(WOC_NS + "Interface");
        Resource owlClass = model.createResource("http://www.w3.org/2002/07/owl#Class");
        Resource wocPublic = model.createResource(WOC_NS + "Public");
        Resource wocProtected = model.createResource(WOC_NS + "Protected");
        Resource wocPrivate = model.createResource(WOC_NS + "Private");

        Property wocName = model.createProperty(WOC_NS + "name");
        Property wocHasModifier = model.createProperty(WOC_NS + "hasModifier");
        Property wocHasReturnType = model.createProperty(WOC_NS + "hasReturnType");
        Property wocParameters = model.createProperty(WOC_NS + "parameters");
        Property wocHasConstructor = model.createProperty(WOC_NS + "hasConstructor");
        Property wocHasMethod = model.createProperty(WOC_NS + "hasMethod");
        Property wocHasSimpleName = model.createProperty(WOC_NS + "hasSimpleName");
        Property wocParamIndex = model.createProperty(WOC_NS + "parameterIndex");
        Property wocParamType = model.createProperty(WOC_NS + "parameterType");
        Property wocThrowsException = model.createProperty(WOC_NS + "throwsException");
        Property rdfsSubClassOf = model.createProperty("http://www.w3.org/2000/01/rdf-schema#subClassOf");

        Property exIsPatchOfMethod = model.createProperty(EX_NS + "isPatchOfMethod");
        Property exIsPatchOfConstructor = model.createProperty(EX_NS + "isPatchOfConstructor");
        Property exIsNewMethod = model.createProperty(EX_NS + "isNewMethod");
        Property exIsNewConstructor = model.createProperty(EX_NS + "isNewConstructor");

        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.addInputResource(sourcePath);
        launcher.buildModel();
        CtModel spoonModel = launcher.getModel();

        for (CtType<?> type : spoonModel.getAllTypes()) {
            String fqcn = type.getQualifiedName();
            Resource classResource = model.createResource(EX_NS + fqcn)
                    .addProperty(RDF.type, wocClassClass)
                    .addProperty(RDF.type, owlClass)
                    .addProperty(wocHasSimpleName, type.getSimpleName());
            if (type.isInterface()) {
                classResource.addProperty(RDF.type, wocInterfaceClass);
            }
            if (type.isPublic()) {
                classResource.addProperty(wocHasModifier, wocPublic);
            } else if (type.isProtected()) {
                classResource.addProperty(wocHasModifier, wocProtected);
            } else if (type.isPrivate()) {
                classResource.addProperty(wocHasModifier, wocPrivate);
            }

            CtTypeReference<?> superclass = type.getSuperclass();
            if (superclass != null) {
                addDirectSupertype(model, classResource, superclass.getQualifiedName(),
                        owlClass, rdfsSubClassOf);
            } else if (type.isClass() && !type.isEnum()) {
                addDirectSupertype(model, classResource, "java.lang.Object",
                        owlClass, rdfsSubClassOf);
            }
            for (CtTypeReference<?> implementedInterface : type.getSuperInterfaces()) {
                addDirectSupertype(model, classResource, implementedInterface.getQualifiedName(),
                        owlClass, rdfsSubClassOf);
            }

            for (CtMethod<?> method : type.getMethods()) {
                boolean isPublic = method.isPublic() || (type.isInterface() && !method.isPrivate());
                if (isPublic || method.isProtected()) {
                    Resource visibility = isPublic ? wocPublic : wocProtected;
                    List<String> parameterTypes = getParameterTypeNames(method);
                        List<String> normalizedParameterTypes = parameterTypes.stream()
                            .map(JavaPatchToRdf::normalizeTypeName)
                            .collect(java.util.stream.Collectors.toList());
                            boolean sourceMatchesRemovedSignature = hasExactPatchSignature(removedSignatures,
                                method.getSimpleName(), normalizedParameterTypes);
                            List<String> addedTypes = findExactPatchParameterTypes(addedSignatures,
                                method.getSimpleName(), normalizedParameterTypes);
                            if (addedTypes == null && sourceMatchesRemovedSignature) {
                            addedTypes = findExtendedPatchParameterTypes(addedSignatures,
                                method.getSimpleName(), normalizedParameterTypes);
                            }
                            if (addedTypes != null) {
                        parameterTypes = resolvePatchParameterTypes(type, addedTypes);
                    }
                    String patchedMethodURI = buildMemberURI(fqcn, method.getSimpleName(), parameterTypes, "patched");
                    Resource returnTypeResource = model.createResource(
                            EX_NS + qualifyJavaLangReturnType(
                                    normalizeTypeName(method.getType().getQualifiedName())))
                            .addProperty(RDF.type, owlClass);

                    Resource patchedMethodRes = model.createResource(patchedMethodURI)
                            .addProperty(RDF.type, wocMethodClass)
                            .addProperty(wocName, method.getSimpleName())
                            .addProperty(wocHasModifier, visibility)
                            .addProperty(wocHasReturnType, returnTypeResource);
                    classResource.addProperty(wocHasMethod, patchedMethodRes);

                    if (isPatchedMethod(method, addedSignatures, removedSignatures)) {
                        List<String> oldSignatureTypes = sourceMatchesRemovedSignature ? normalizedParameterTypes
                                    : findClosestPatchParameterTypes(removedSignatures,
                                            method.getSimpleName(), normalizedParameterTypes);
                        List<String> oldTypes = oldSignatureTypes == null ? parameterTypes
                                    : resolvePatchParameterTypes(type, oldSignatureTypes);
                        // An unchanged signature means no stable counterpart exists: the method is new
                        if (oldTypes.equals(parameterTypes)) {
                            patchedMethodRes.addProperty(exIsNewMethod, classResource);
                        } else {
                            patchedMethodRes.addProperty(exIsPatchOfMethod,
                                model.createResource(buildMemberURI(fqcn, method.getSimpleName(), oldTypes, "stable")));
                        }
                    }

                    patchedMethodRes.addProperty(wocParameters,
                            createParameterList(model, parameterTypes, wocParameterClass, wocParamIndex, wocParamType));

                    for (CtTypeReference<? extends Throwable> excRef : method.getThrownTypes()) {
                        patchedMethodRes.addProperty(wocThrowsException, excRef.getQualifiedName());
                    }
                }
            }

            for (CtConstructor<?> constructor : type.getElements(new TypeFilter<>(CtConstructor.class))) {
                if (type.isInterface()) {
                    continue;
                }
                Resource visibility = constructor.isPublic() ? wocPublic
                        : constructor.isProtected() ? wocProtected
                        : constructor.isPrivate() ? wocPrivate : null;
                List<String> parameterTypes = getParameterTypeNames(constructor);
                List<String> normalizedParameterTypes = parameterTypes.stream()
                    .map(JavaPatchToRdf::normalizeTypeName)
                    .collect(java.util.stream.Collectors.toList());
                boolean sourceMatchesRemovedSignature = hasExactPatchSignature(removedSignatures,
                        type.getSimpleName(), normalizedParameterTypes);
                List<String> addedTypes = findExactPatchParameterTypes(addedSignatures,
                        type.getSimpleName(), normalizedParameterTypes);
                if (addedTypes == null && sourceMatchesRemovedSignature) {
                    addedTypes = findExtendedPatchParameterTypes(addedSignatures,
                            type.getSimpleName(), normalizedParameterTypes);
                }
                if (addedTypes != null) {
                    parameterTypes = resolvePatchParameterTypes(type, addedTypes);
                }
                String constructorURI = buildMemberURI(fqcn, "constructor", parameterTypes, "current");

                Resource constructorRes = model.createResource(constructorURI)
                        .addProperty(RDF.type, wocConstructorClass)
                        .addProperty(wocName, constructor.getSimpleName());
                if (visibility != null) {
                    constructorRes.addProperty(wocHasModifier, visibility);
                }

                if (isPatchedConstructor(constructor, addedSignatures, removedSignatures)) {
                    List<String> oldSignatureTypes = sourceMatchesRemovedSignature ? normalizedParameterTypes
                            : findClosestPatchParameterTypes(removedSignatures,
                                    type.getSimpleName(), normalizedParameterTypes);
                    List<String> oldTypes = oldSignatureTypes == null ? parameterTypes
                            : resolvePatchParameterTypes(type, oldSignatureTypes);
                    if (oldTypes.equals(parameterTypes)) {
                        constructorRes.addProperty(exIsNewConstructor, classResource);
                    } else {
                        constructorRes.addProperty(exIsPatchOfConstructor, 
                            model.createResource(buildMemberURI(fqcn, "constructor", oldTypes, "stable")));
                    }
                }

                classResource.addProperty(wocHasConstructor, constructorRes);
                constructorRes.addProperty(wocParameters,
                    createParameterList(model, parameterTypes, wocParameterClass, wocParamIndex, wocParamType));

                for (CtTypeReference<? extends Throwable> excRef : constructor.getThrownTypes()) {
                    constructorRes.addProperty(wocThrowsException, excRef.getQualifiedName());
                }
            }

            Set<String> existingConstructorURIs = new HashSet<>();
            for (CtConstructor<?> constructor : type.getElements(new TypeFilter<>(CtConstructor.class))) {
                existingConstructorURIs.add(buildMemberURI(fqcn, "constructor", getParameterTypeNames(constructor), "current"));
            }

            for (Map.Entry<String, String> addedConstructor : addedConstructorDeclarations.entrySet()) {
                if (type.isInterface()) {
                    continue;
                }
                String signature = addedConstructor.getKey();
                if (!signature.startsWith(type.getSimpleName() + "(")) {
                    continue;
                }

                List<String> parameterTypes = extractParameterTypesFromMethodSignature(signature);
                List<String> qualifiedParameterTypes = new ArrayList<>();
                for (String parameterType : parameterTypes) {
                    String resolvedType = parameterType;
                    String simpleTypeName = normalizeTypeName(parameterType);
                    for (CtTypeReference<?> typeReference : type.getElements(new TypeFilter<>(CtTypeReference.class))) {
                        if (typeReference.getSimpleName().equals(simpleTypeName)) {
                            resolvedType = typeReference.getQualifiedName();
                            break;
                        }
                    }
                    qualifiedParameterTypes.add(resolvedType);
                }

                String constructorURI = buildMemberURI(fqcn, "constructor", qualifiedParameterTypes, "current");
                if (!existingConstructorURIs.add(constructorURI)) {
                    continue;
                }

                Resource constructorRes = model.createResource(constructorURI)
                        .addProperty(RDF.type, wocConstructorClass)
                        .addProperty(wocName, "<init>")
                        .addProperty(exIsNewConstructor, classResource)
                        .addProperty(wocParameters,
                            createParameterList(model, qualifiedParameterTypes, wocParameterClass, wocParamIndex, wocParamType));
                Resource visibility = modifierResource(model, addedConstructor.getValue());
                if (visibility != null) {
                    constructorRes.addProperty(wocHasModifier, visibility);
                }
                classResource.addProperty(wocHasConstructor, constructorRes);
            }
        }

        return model;
    }

    public static Model buildModelForPatch(String sourceBaseDirectory, String patchPath) throws IOException {
        Path baseDirectory = Paths.get(sourceBaseDirectory).toAbsolutePath().normalize();
        if (!Files.isDirectory(baseDirectory)) {
            throw new IOException("Source base directory does not exist: " + baseDirectory);
        }

        List<String> patchLines = Files.readAllLines(Paths.get(patchPath), StandardCharsets.UTF_8);
        List<PatchFile> patchFiles = splitPatchFiles(patchLines);
        if (patchFiles.isEmpty()) {
            throw new IOException("Patch file contains no Java file diffs: " + patchPath);
        }

        Model combinedModel = ModelFactory.createDefaultModel();
        for (PatchFile patchFile : patchFiles) {
            String changedPath = patchFile.newPath != null ? patchFile.newPath : patchFile.oldPath;
            if (changedPath == null || !changedPath.endsWith(".java")) {
                continue;
            }

            if (patchFile.newPath == null) {
                addDeletedType(combinedModel, patchFile);
                continue;
            }

            Path sourceFile = resolvePatchSource(baseDirectory, patchFile.newPath);
            if (sourceFile == null) {
                throw new IOException("Java source from patch was not found under "
                        + baseDirectory + ": " + patchFile.newPath);
            }
            combinedModel.add(buildModelForFile(sourceFile.toString(), patchFile.lines));
        }

        combinedModel.setNsPrefix("woc", WOC_NS);
        combinedModel.setNsPrefix("icodm", EX_NS);
        combinedModel.setNsPrefix("rdf", RDF.getURI());
        combinedModel.setNsPrefix("owl", "http://www.w3.org/2002/07/owl#");
        combinedModel.setNsPrefix("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
        return combinedModel;
    }

    private static List<PatchFile> splitPatchFiles(List<String> lines) {
        List<PatchFile> files = new ArrayList<>();
        List<String> section = null;
        for (String line : lines) {
            if (line.startsWith("diff --git ")) {
                if (section != null) {
                    addPatchFile(files, section);
                }
                section = new ArrayList<>();
            }
            if (section != null) {
                section.add(line);
            }
        }
        if (section != null) {
            addPatchFile(files, section);
        } else if (lines.stream().anyMatch(line -> line.startsWith("--- "))) {
            addPatchFile(files, lines);
        }
        return files;
    }

    private static void addPatchFile(List<PatchFile> files, List<String> section) {
        String oldPath = null;
        String newPath = null;
        for (String line : section) {
            if (line.startsWith("--- ")) {
                oldPath = parsePatchPath(line.substring(4));
            } else if (line.startsWith("+++ ")) {
                newPath = parsePatchPath(line.substring(4));
            }
        }
        if ((oldPath != null && oldPath.endsWith(".java"))
                || (newPath != null && newPath.endsWith(".java"))) {
            files.add(new PatchFile(oldPath, newPath, new ArrayList<>(section)));
        }
    }

    private static String parsePatchPath(String headerPath) {
        String path = headerPath.split("\\t", 2)[0];
        if (path.equals("/dev/null")) {
            return null;
        }
        if (path.startsWith("a/") || path.startsWith("b/")) {
            return path.substring(2);
        }
        return path;
    }

    private static Path resolvePatchSource(Path baseDirectory, String patchPath) throws IOException {
        Path relativePath = Paths.get(patchPath).normalize();
        if (relativePath.isAbsolute() || relativePath.startsWith("..")) {
            throw new IOException("Patch path is outside the source base directory: " + patchPath);
        }
        Path directPath = baseDirectory.resolve(relativePath).normalize();
        if (directPath.startsWith(baseDirectory) && Files.isRegularFile(directPath)) {
            return directPath;
        }

        List<Path> sourceFiles;
        try (java.util.stream.Stream<Path> paths = Files.walk(baseDirectory)) {
            sourceFiles = paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
        for (int start = 0; start < relativePath.getNameCount(); start++) {
            Path suffix = relativePath.subpath(start, relativePath.getNameCount());
            List<Path> matches = sourceFiles.stream()
                    .filter(path -> path.endsWith(suffix))
                    .collect(Collectors.toList());
            if (matches.size() > 1) {
                throw new IOException("Patch path matches multiple Java sources under "
                        + baseDirectory + ": " + patchPath);
            }
            if (!matches.isEmpty()) {
                return matches.get(0);
            }
        }
        return null;
    }

    private static void addDeletedType(Model model, PatchFile patchFile) throws IOException {
        String packageName = "";
        String typeKind = null;
        String typeName = null;
        for (String line : patchFile.lines) {
            if (!line.startsWith("-") || line.startsWith("---")) {
                continue;
            }
            String content = line.substring(1).trim();
            Matcher packageMatcher = PACKAGE_DECLARATION.matcher(content);
            if (packageMatcher.find()) {
                packageName = packageMatcher.group(1);
            }
            Matcher typeMatcher = TYPE_DECLARATION.matcher(content);
            if (typeName == null && typeMatcher.find()) {
                typeKind = typeMatcher.group(1);
                typeName = typeMatcher.group(2);
            }
        }
        if (typeName == null) {
            throw new IOException("Deleted Java source has no top-level type declaration: " + patchFile.oldPath);
        }

        String qualifiedName = packageName.isEmpty() ? typeName : packageName + "." + typeName;
        Resource deletedType = model.createResource(EX_NS + qualifiedName)
                .addProperty(RDF.type, model.createResource(WOC_NS
                        + ("interface".equals(typeKind) ? "Interface" : "Class")))
                .addProperty(RDF.type, model.createResource("http://www.w3.org/2002/07/owl#Class"));
        deletedType.addProperty(model.createProperty(EX_NS + "isDeleted"), deletedType);
    }

    private static Resource modifierResource(Model model, String modifier) {
        if (modifier == null) {
            return null;
        }
        switch (modifier.toLowerCase()) {
            case "public":
                return model.createResource(WOC_NS + "Public");
            case "protected":
                return model.createResource(WOC_NS + "Protected");
            case "private":
                return model.createResource(WOC_NS + "Private");
            default:
                return null;
        }
    }

    private static void addDirectSupertype(Model model, Resource classResource, String supertypeName,
            Resource owlClass, Property rdfsSubClassOf) {
        Resource supertypeResource = model.createResource(EX_NS + supertypeName)
                .addProperty(RDF.type, owlClass);
        classResource.addProperty(rdfsSubClassOf, supertypeResource);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: java -jar pajes-0.0.1-SNAPSHOT.jar <source-base-directory> <patch-file>");
        }

        Model model = buildModelForPatch(args[0], args[1]);

        try (FileOutputStream out = new FileOutputStream("refmodule-patch.ttl")) {
            model.write(out, "TURTLE");
            System.out.println("Fichier RDF 'refmodule-patch.ttl' généré avec succès !");
        }
    }
}
