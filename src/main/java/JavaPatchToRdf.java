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

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JavaPatchToRdf {

    // Espace de noms officiel de CodeOntology
    private static final String WOC_NS = "http://webofcode.org";
    // Espace de noms personnalisé pour votre projet / votre pont logique
    // The trailing slash keeps Turtle serializer output compatible with the icodm: prefix.
    private static final String EX_NS = "https://github.com/Incodam/";

    private static String buildConstructorSignature(CtConstructor<?> constructor) {
        StringBuilder signature = new StringBuilder();
        boolean first = true;
        for (CtParameter<?> parameter : constructor.getParameters()) {
            if (!first) {
                signature.append("_");
            }
            signature.append(parameter.getType().getQualifiedName().replace('.', '_'));
            first = false;
        }
        return signature.length() == 0 ? "default" : signature.toString();
    }

    private static String normalizeTypeName(String typeName) {
        if (typeName == null) {
            return "";
        }
        String trimmed = typeName.trim();
        int lastDot = trimmed.lastIndexOf('.');
        return lastDot >= 0 ? trimmed.substring(lastDot + 1) : trimmed;
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

    private static String resolveParameterTypeFromSpoon(CtType<?> declaringType, CtMethod<?> method,
            String patchedTypeName) {
        String simplePatchedTypeName = normalizeTypeName(patchedTypeName);

        for (CtParameter<?> parameter : method.getParameters()) {
            CtTypeReference<?> parameterType = parameter.getType();
            if (parameterType.getSimpleName().equals(simplePatchedTypeName)) {
                return parameterType.getQualifiedName();
            }
        }

        for (CtTypeReference<?> typeReference : declaringType.getElements(new TypeFilter<>(CtTypeReference.class))) {
            if (typeReference.getSimpleName().equals(simplePatchedTypeName)) {
                return typeReference.getQualifiedName();
            }
        }

        return patchedTypeName;
    }

    private static boolean isPatchedMethod(CtMethod<?> method, Set<String> patchedMethodSignatures) {
        String methodName = method.getSimpleName();
        String signature = method.getSimpleName() + "(" + method.getParameters().stream()
                .map(p -> normalizeTypeName(p.getType().getQualifiedName()))
                .reduce((a, b) -> a + "," + b)
                .orElse("") + ")";

        return patchedMethodSignatures.contains(methodName)
                || patchedMethodSignatures.contains(signature)
                || patchedMethodSignatures.contains(normalizeMethodSignature(method.toString()));
    }

    private static boolean isPatchedConstructor(CtConstructor<?> constructor, Set<String> patchedMethodSignatures) {
        String signature = constructor.getDeclaringType().getSimpleName() + "(" + constructor.getParameters().stream()
                .map(parameter -> normalizeTypeName(parameter.getType().getQualifiedName()))
                .reduce((first, second) -> first + "," + second)
                .orElse("") + ")";
        return patchedMethodSignatures.contains(signature);
    }

    private static Set<String> readPatchedMethodSignatures(String patchPath) throws IOException {
        Set<String> patchedMethods = new HashSet<>();
        if (patchPath == null || patchPath.trim().isEmpty()) {
            return patchedMethods;
        }

        StringBuilder currentMethod = null;

        try (BufferedReader reader = Files.newBufferedReader(Paths.get(patchPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();

                if (trimmed.startsWith("-") || trimmed.startsWith("+")) {
                    String content = trimmed.substring(1).trim();
                    if (content.isEmpty()) {
                        continue;
                    }

                    if (currentMethod == null && content.contains("(") &&
                            (content.contains("public") || content.contains("protected") || content.contains("private"))) {
                        currentMethod = new StringBuilder(content);
                    } else if (currentMethod != null) {
                        currentMethod.append(" ").append(content);
                    }

                    if (currentMethod != null && currentMethod.indexOf("(") >= 0 && currentMethod.indexOf(")") >= 0) {
                        String normalized = normalizeMethodSignature(currentMethod.toString());
                        if (!normalized.isEmpty()) {
                            patchedMethods.add(normalized);
                            String methodName = normalized.substring(0, normalized.indexOf('('));
                            patchedMethods.add(methodName);
                        }
                        currentMethod = null;
                    }
                    continue;
                }

                if (currentMethod != null && (trimmed.startsWith("{") || trimmed.startsWith("}"))) {
                    String normalized = normalizeMethodSignature(currentMethod.toString());
                    if (!normalized.isEmpty()) {
                        patchedMethods.add(normalized);
                        String methodName = normalized.substring(0, normalized.indexOf('('));
                        patchedMethods.add(methodName);
                    }
                    currentMethod = null;
                }
            }
        }
        return patchedMethods;
    }

    private static Map<String, String> readAddedConstructorDeclarations(String patchPath) throws IOException {
        Map<String, String> constructors = new HashMap<>();
        if (patchPath == null || patchPath.trim().isEmpty()) {
            return constructors;
        }

        StringBuilder currentConstructor = null;
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(patchPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
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
        }
        return constructors;
    }

    public static Model buildModelForFile(String sourcePath) {
        return buildModelForFile(sourcePath, null);
    }

    public static Model buildModelForFile(String sourcePath, String patchPath) {
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("woc", WOC_NS);
        model.setNsPrefix("icodm", EX_NS);
        model.setNsPrefix("rdf", RDF.getURI());

        Set<String> patchedMethodSignatures = new HashSet<>();
        Map<String, String> addedConstructorDeclarations;
        try {
            patchedMethodSignatures.addAll(readPatchedMethodSignatures(patchPath));
            addedConstructorDeclarations = readAddedConstructorDeclarations(patchPath);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read patch file: " + patchPath, e);
        }

        Resource wocMethodClass = model.createResource(WOC_NS + "Method");
        Resource wocConstructorClass = model.createResource(WOC_NS + "Constructor");
        Resource wocParameterClass = model.createResource(WOC_NS + "Parameter");

        Property wocName = model.createProperty(WOC_NS + "name");
        Property wocHasModifier = model.createProperty(WOC_NS + "hasModifier");
        Property wocHasReturnType = model.createProperty(WOC_NS + "hasReturnType");
        Property wocHasParameter = model.createProperty(WOC_NS + "hasParameter");
        Property wocHasConstructor = model.createProperty(WOC_NS + "hasConstructor");
        Property wocParamIndex = model.createProperty(WOC_NS + "parameterIndex");
        Property wocParamType = model.createProperty(WOC_NS + "parameterType");
        Property wocThrowsException = model.createProperty(WOC_NS + "throwsException");

        Property exIsPatchOfMethod = model.createProperty(EX_NS + "isPatchOfMethod");
        Property exIsPatchOfConstructor = model.createProperty(EX_NS + "isPatchOfConstructor");

        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.addInputResource(sourcePath);
        launcher.buildModel();
        CtModel spoonModel = launcher.getModel();

        for (CtType<?> type : spoonModel.getAllTypes()) {
            String fqcn = type.getQualifiedName();
            Resource classResource = model.createResource(EX_NS + fqcn);

            for (CtMethod<?> method : type.getMethods()) {
                if (method.isPublic() || method.isProtected()) {
                    String visibility = method.isPublic() ? "public" : "protected";
                    String patchedMethodURI = EX_NS + fqcn + "_" + method.getSimpleName() + "_patched";
                    String stableMethodURI = EX_NS + fqcn + "_" + method.getSimpleName() + "_stable";

                    Resource patchedMethodRes = model.createResource(patchedMethodURI)
                            .addProperty(RDF.type, wocMethodClass)
                            .addProperty(wocName, method.getSimpleName())
                            .addProperty(wocHasModifier, visibility)
                            .addProperty(wocHasReturnType, method.getType().getQualifiedName());

                    if (isPatchedMethod(method, patchedMethodSignatures)) {
                        patchedMethodRes.addProperty(exIsPatchOfMethod, model.createResource(stableMethodURI));
                    }

                    List<String> patchSignatureParamTypes = patchedMethodSignatures.stream()
                            .filter(signature -> signature.startsWith(method.getSimpleName() + "("))
                            .map(JavaPatchToRdf::extractParameterTypesFromMethodSignature)
                            .filter(types -> !types.isEmpty() && types.size() > method.getParameters().size())
                            .findFirst()
                            .orElse(List.of());

                    int index = 0;
                    for (CtParameter<?> param : method.getParameters()) {
                        String paramURI = patchedMethodURI + "_param_" + index;
                        Resource paramRes = model.createResource(paramURI)
                                .addProperty(RDF.type, wocParameterClass)
                                .addProperty(wocParamIndex, model.createTypedLiteral(index))
                                .addProperty(wocParamType, param.getType().getQualifiedName());

                        patchedMethodRes.addProperty(wocHasParameter, paramRes);
                        index++;
                    }

                    for (int patchIndex = method.getParameters().size(); patchIndex < patchSignatureParamTypes.size(); patchIndex++) {
                        String paramType = resolveParameterTypeFromSpoon(type, method,
                                patchSignatureParamTypes.get(patchIndex));
                        String paramURI = patchedMethodURI + "_param_" + patchIndex;
                        Resource paramRes = model.createResource(paramURI)
                                .addProperty(RDF.type, wocParameterClass)
                                .addProperty(wocParamIndex, model.createTypedLiteral(patchIndex))
                                .addProperty(wocParamType, paramType);

                        patchedMethodRes.addProperty(wocHasParameter, paramRes);
                    }

                    for (CtTypeReference<? extends Throwable> excRef : method.getThrownTypes()) {
                        patchedMethodRes.addProperty(wocThrowsException, excRef.getQualifiedName());
                    }
                }
            }

            for (CtConstructor<?> constructor : type.getElements(new TypeFilter<>(CtConstructor.class))) {
                String visibility = constructor.isPublic() ? "public" : constructor.isProtected() ? "protected" : constructor.isPrivate() ? "private" : "package-private";
                String constructorSignature = buildConstructorSignature(constructor);
                String constructorURI = EX_NS + fqcn + "_" + constructorSignature + "_constructor";

                Resource constructorRes = model.createResource(constructorURI)
                        .addProperty(RDF.type, wocConstructorClass)
                        .addProperty(wocName, constructor.getSimpleName())
                        .addProperty(wocHasModifier, visibility);

                if (isPatchedConstructor(constructor, patchedMethodSignatures)) {
                    constructorRes.addProperty(exIsPatchOfConstructor,
                        model.createResource(constructorURI + "_stable"));
                }

                classResource.addProperty(wocHasConstructor, constructorRes);

                int index = 0;
                for (CtParameter<?> param : constructor.getParameters()) {
                    String paramURI = constructorURI + "_param_" + index;
                    Resource paramRes = model.createResource(paramURI)
                            .addProperty(RDF.type, wocParameterClass)
                            .addProperty(wocParamIndex, model.createTypedLiteral(index))
                            .addProperty(wocParamType, param.getType().getQualifiedName());

                    constructorRes.addProperty(wocHasParameter, paramRes);
                    index++;
                }

                for (CtTypeReference<? extends Throwable> excRef : constructor.getThrownTypes()) {
                    constructorRes.addProperty(wocThrowsException, excRef.getQualifiedName());
                }
            }

            Set<String> existingConstructorURIs = new HashSet<>();
            for (CtConstructor<?> constructor : type.getElements(new TypeFilter<>(CtConstructor.class))) {
                existingConstructorURIs.add(EX_NS + fqcn + "_" + buildConstructorSignature(constructor) + "_constructor");
            }

            for (Map.Entry<String, String> addedConstructor : addedConstructorDeclarations.entrySet()) {
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

                String constructorSignature = qualifiedParameterTypes.isEmpty() ? "default"
                        : String.join("_", qualifiedParameterTypes).replace('.', '_');
                String constructorURI = EX_NS + fqcn + "_" + constructorSignature + "_constructor";
                if (!existingConstructorURIs.add(constructorURI)) {
                    continue;
                }

                Resource constructorRes = model.createResource(constructorURI)
                        .addProperty(RDF.type, wocConstructorClass)
                        .addProperty(wocName, "<init>")
                        .addProperty(wocHasModifier, addedConstructor.getValue())
                        .addProperty(exIsPatchOfConstructor, model.createResource(constructorURI + "_stable"));
                classResource.addProperty(wocHasConstructor, constructorRes);

                for (int index = 0; index < qualifiedParameterTypes.size(); index++) {
                    String paramURI = constructorURI + "_param_" + index;
                    Resource paramRes = model.createResource(paramURI)
                            .addProperty(RDF.type, wocParameterClass)
                            .addProperty(wocParamIndex, model.createTypedLiteral(index))
                            .addProperty(wocParamType, qualifiedParameterTypes.get(index));
                    constructorRes.addProperty(wocHasParameter, paramRes);
                }
            }
        }

        return model;
    }

    public static void main(String[] args) {
        String sourcePath = args.length > 0 ? args[0] : "/Users/tanguyro/Incodame/paragraph/ParagraphUI/src/main/java/org/incodame/paragraph/sample/webapp/demo/Parameter.java";
        String patchPath = args.length > 1 ? args[1] : null;

        Model model = buildModelForFile(sourcePath, patchPath);

        try (FileOutputStream out = new FileOutputStream("refmodule-patch.ttl")) {
            model.write(out, "TURTLE");
            System.out.println("Fichier RDF 'refmodule-patch.ttl' généré avec succès !");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
