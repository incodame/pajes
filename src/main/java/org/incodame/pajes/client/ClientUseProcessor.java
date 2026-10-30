package org.incodame.pajes.client;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import spoon.processing.AbstractProcessor;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtAnnotationMethod;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtInterface;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;

public class ClientUseProcessor extends AbstractProcessor<CtElement> {

    private final String targetPackage;
    private final String branch;
    private final Map<String, List<String>> clientAnnotations;
    private final Set<String> clientPackages = new LinkedHashSet<>();

    // Initialisation du modèle Jena et des préfixes graphiques
    private final Model model = ModelFactory.createDefaultModel();
    private final String ICODM = "https://github.com/Incodam/";// Préfixe de votre refmodule
    private final String WOC = "http://webofcode.org";        // Préfixe de l'ontologie WebOfCode
    
    // Définition des propriétés de liaison
    private final Property wocCalls = model.createProperty(WOC + "calls");
    private final Property wocExtends = model.createProperty(WOC + "extends");
    private final Property wocImplements = model.createProperty(WOC + "implements");
    private final Property applBranch = model.createProperty(ICODM + "applBranch");
    private final Resource wocClass = model.createResource(WOC + "Class");
    private final Resource wocInterface = model.createResource(WOC + "Interface");
    private final Resource owlClass = model.createResource("http://www.w3.org/2002/07/owl#Class");
    private final Model constraintModel = ModelFactory.createDefaultModel();
    private final Property onMethodCall = constraintModel.createProperty(ICODM + "onMethodCall");
    private final Property onMethodReturn = constraintModel.createProperty(ICODM + "onMethodReturn");
    private final Property hasContraints = constraintModel.createProperty(ICODM + "hasContraints");
    private final Property annotations = constraintModel.createProperty(ICODM + "annotations");
    private final Property annotationProperties = constraintModel.createProperty(ICODM + "annotationProperties");
    private final Property rdfValue = constraintModel.createProperty(RDF.value.getURI());
    private final Property rdfsLabel = constraintModel.createProperty(
            "http://www.w3.org/2000/01/rdf-schema#label");

    public ClientUseProcessor(String targetPackage, String branch) {
        this(targetPackage, branch, Map.of());
    }

    public ClientUseProcessor(String targetPackage, String branch, Map<String, List<String>> clientAnnotations) {
        if (targetPackage == null || targetPackage.trim().isEmpty()
                || branch == null || branch.trim().isEmpty() || clientAnnotations == null) {
            throw new IllegalArgumentException("Target package, branch, and client annotations must be valid");
        }
        this.targetPackage = targetPackage;
        this.branch = branch;
        Map<String, List<String>> annotationCopy = new LinkedHashMap<>();
        clientAnnotations.forEach((name, properties) -> {
            if (name == null || name.trim().isEmpty() || properties == null
                    || properties.stream().anyMatch(property -> property == null || property.trim().isEmpty())) {
                throw new IllegalArgumentException("Client annotation names and properties must not be empty");
            }
            annotationCopy.put(name, List.copyOf(properties));
        });
        this.clientAnnotations = Collections.unmodifiableMap(annotationCopy);

        // Configuration des préfixes pour un affichage propre en Turtle (.ttl)
        model.setNsPrefix("icodm", ICODM);
        model.setNsPrefix("woc", WOC);
        model.setNsPrefix("owl", "http://www.w3.org/2002/07/owl#");
        constraintModel.setNsPrefix("icodm", ICODM);
        constraintModel.setNsPrefix("woc", WOC);
        constraintModel.setNsPrefix("rdf", RDF.getURI());
        constraintModel.setNsPrefix("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
    }

    public Model getModel() {
        return model;
    }

    public Model getConstraintModel() {
        return constraintModel;
    }

    public Set<String> getClientPackages() {
        return java.util.Collections.unmodifiableSet(clientPackages);
    }

    @Override
    public void process(CtElement element) {
        if (element instanceof CtInvocation<?>) {
            CtInvocation<?> invocation = (CtInvocation<?>) element;
            CtExecutableReference<?> executableRef = invocation.getExecutable();
            CtType<?> clientType = element.getParent(CtType.class);
            if (executableRef != null && clientType != null) {
                CtMethod<?> calledMethod = getMethodDeclaration(executableRef);
                if (isApiWrapper(calledMethod)) {
                    addApiWrapperCall(invocation, calledMethod, clientType);
                } else if (isInTargetPackage(executableRef)) {
                    CtMethod<?> enclosingMethod = invocation.getParent(CtMethod.class);
                    if (!isApiWrapper(enclosingMethod)) {
                        addApiMethodCall(invocation, invocation, executableRef, clientType, null);
                    }
                }
            }
        }
        else if (element instanceof CtConstructorCall<?>) {
            CtConstructorCall<?> consCall = (CtConstructorCall<?>) element;
            CtExecutableReference<?> executableRef = consCall.getExecutable();
            CtType<?> clientType = element.getParent(CtType.class);
            if (executableRef != null && isInTargetPackage(executableRef) && clientType != null) {
                String apiConstructorUri = buildMemberUri(executableRef);
                Resource clientResource = getClientTypeResource(clientType);
                clientResource.addProperty(wocCalls, model.createResource(apiConstructorUri));
            }
        }
        else if (element instanceof CtClass<?> || element instanceof CtInterface<?>) {
            CtType<?> clientType = (CtType<?>) element;
            recordClientPackage(clientType);

            if (clientType instanceof CtClass<?>) {
                CtTypeReference<?> superclass = ((CtClass<?>) clientType).getSuperclass();
                if (superclass != null && isInTargetPackage(superclass.getQualifiedName())) {
                    Resource apiClassResource = model.createResource(ICODM + superclass.getQualifiedName());
                    getClientTypeResource(clientType).addProperty(wocExtends, apiClassResource);
                }
            }

            for (CtTypeReference<?> iface : clientType.getSuperInterfaces()) {
                if (isInTargetPackage(iface.getQualifiedName())) {
                    Resource apiInterfaceResource = model.createResource(ICODM + iface.getQualifiedName());
                    getClientTypeResource(clientType).addProperty(clientType instanceof CtInterface<?> ? wocExtends : wocImplements,
                            apiInterfaceResource);
                }
            }
        }
    }

    private void addApiMethodCall(CtInvocation<?> clientCall, CtInvocation<?> apiInvocation,
            CtExecutableReference<?> apiMethodReference, CtType<?> clientType, CtMethod<?> wrapperMethod) {
        String apiMethodUri = buildMemberUri(apiMethodReference);
        Resource clientResource = getClientTypeResource(clientType);
        clientResource.addProperty(wocCalls, model.createResource(apiMethodUri));
        addInvocationConstraints(clientCall, apiInvocation, apiMethodReference, apiMethodUri,
                clientResource, wrapperMethod);
    }

    private void addApiWrapperCall(CtInvocation<?> clientCall, CtMethod<?> wrapperMethod, CtType<?> clientType) {
        List<CtInvocation> apiInvocations = wrapperMethod.getElements(
                new spoon.reflect.visitor.filter.TypeFilter<>(CtInvocation.class)).stream()
                .filter(invocation -> isInTargetPackage(invocation.getExecutable()))
                .toList();
        if (apiInvocations.size() != 1) {
            throw new IllegalStateException("@ApiWrapper method must contain exactly one direct call to target package "
                    + targetPackage + ": " + wrapperMethod.getDeclaringType().getQualifiedName()
                    + "#" + wrapperMethod.getSimpleName());
        }

        CtInvocation<?> apiInvocation = apiInvocations.get(0);
        addApiMethodCall(clientCall, apiInvocation, apiInvocation.getExecutable(), clientType, wrapperMethod);
    }

    private CtMethod<?> getMethodDeclaration(CtExecutableReference<?> executableReference) {
        CtExecutable<?> declaration = executableReference.getExecutableDeclaration();
        return declaration instanceof CtMethod<?> ? (CtMethod<?>) declaration : null;
    }

    private boolean isApiWrapper(CtMethod<?> method) {
        return method != null && method.getAnnotations().stream()
                .anyMatch(annotation -> "ApiWrapper".equals(annotation.getAnnotationType().getSimpleName()));
    }

    private boolean isInTargetPackage(CtExecutableReference<?> executableReference) {
        return executableReference != null && executableReference.getDeclaringType() != null
                && isInTargetPackage(executableReference.getDeclaringType().getQualifiedName());
    }

    private void recordClientPackage(CtType<?> clientType) {
        CtPackage ctPackage = clientType.getPackage();
        if (ctPackage == null) {
            ctPackage = clientType.getParent(CtPackage.class);
        }
        if (ctPackage != null && !ctPackage.getQualifiedName().isEmpty()) {
            String packageName = ctPackage.getQualifiedName();
            clientPackages.add(packageName);
        }
    }

    private Resource getClientTypeResource(CtType<?> clientType) {
        recordClientPackage(clientType);
        Resource clientResource = model.createResource(ICODM + clientType.getQualifiedName())
                .addProperty(RDF.type, owlClass)
                .addProperty(RDF.type, wocClass)
                .addProperty(applBranch, branch);
        if (clientType instanceof CtInterface<?>) {
            clientResource.addProperty(RDF.type, wocInterface);
        }
        return clientResource;
    }

    private boolean isInTargetPackage(String qualifiedName) {
        return qualifiedName.startsWith(targetPackage + ".");
    }

    private void addInvocationConstraints(CtInvocation<?> clientCall, CtInvocation<?> apiInvocation,
            CtExecutableReference<?> executableReference, String apiMethodUri, Resource clientResource,
            CtMethod<?> wrapperMethod) {
        if (clientAnnotations.isEmpty()) {
            return;
        }
        Resource constraintClientResource = constraintModel.createResource(clientResource.getURI())
                .addProperty(constraintModel.createProperty(ICODM + "applBranch"), branch);
        CtExecutable<?> declaration = executableReference.getExecutableDeclaration();
        if (!(declaration instanceof CtMethod<?>)) {
            return;
        }

        int sourceLine = clientCall.getPosition().isValidPosition() ? clientCall.getPosition().getLine() : -1;
        List<Resource> parameterConstraints = new ArrayList<>();
        List<CtParameter<?>> parameters = declaration.getParameters();
        for (int index = 0; index < parameters.size(); index++) {
            CtParameter<?> parameter = parameters.get(index);
            List<CtAnnotation<?>> matchedAnnotations = new ArrayList<>(
                    matchingAnnotations(parameter.getAnnotations()));
            if (wrapperMethod != null) {
                matchedAnnotations.addAll(matchingWrapperParameterAnnotations(wrapperMethod, apiInvocation, index));
            }
            for (int annotationIndex = 0; annotationIndex < matchedAnnotations.size(); annotationIndex++) {
                parameterConstraints.add(createConstraint(apiMethodUri, sourceLine,
                        "_" + (index + 1), matchedAnnotations.get(annotationIndex), annotationIndex));
            }
        }

        if (!parameterConstraints.isEmpty()) {
            String constraintSetUri = apiMethodUri + "_constraint_set_" + sourceLine;
            Resource constraintSet = constraintModel.createResource(constraintSetUri)
                    .addProperty(RDF.type, constraintModel.createResource(ICODM + "ApiClientConstraintSet"))
                    .addProperty(hasContraints, constraintModel.createList(parameterConstraints.iterator()));
            constraintClientResource.addProperty(onMethodCall, constraintSet);
        }

        CtMethod<?> method = (CtMethod<?>) declaration;
        List<CtAnnotation<?>> returnAnnotations = matchingReturnAnnotations(method);
        if (wrapperMethod != null && isDirectlyReturnedByWrapper(wrapperMethod, apiInvocation)) {
            appendDistinctAnnotations(returnAnnotations, matchingWrapperReturnAnnotations(wrapperMethod));
        }
        for (int annotationIndex = 0; annotationIndex < returnAnnotations.size(); annotationIndex++) {
            Resource constraint = createConstraint(apiMethodUri, sourceLine, "",
                    returnAnnotations.get(annotationIndex), annotationIndex);
            constraintClientResource.addProperty(onMethodReturn, constraint);
        }
    }

    private boolean isDirectlyReturnedByWrapper(CtMethod<?> wrapperMethod, CtInvocation<?> apiInvocation) {
        return wrapperMethod.getElements(new spoon.reflect.visitor.filter.TypeFilter<>(CtReturn.class)).stream()
                .anyMatch(returnStatement -> returnStatement.getReturnedExpression() == apiInvocation);
    }

    private List<CtAnnotation<?>> matchingWrapperReturnAnnotations(CtMethod<?> wrapperMethod) {
        List<CtAnnotation<?>> returnAnnotations = new ArrayList<>(
                matchingAnnotations(wrapperMethod.getAnnotations()));
        appendDistinctAnnotations(returnAnnotations, matchingAnnotations(wrapperMethod.getType().getAnnotations()));
        return returnAnnotations;
    }

    private List<CtAnnotation<?>> matchingReturnAnnotations(CtMethod<?> method) {
        List<CtAnnotation<?>> returnAnnotations = new ArrayList<>(matchingAnnotations(method.getAnnotations()));
        appendDistinctAnnotations(returnAnnotations, matchingAnnotations(method.getType().getAnnotations()));
        return returnAnnotations;
    }

    private void appendDistinctAnnotations(List<CtAnnotation<?>> annotationsToExtend,
            List<CtAnnotation<?>> additionalAnnotations) {
        Set<String> existingTypes = new LinkedHashSet<>();
        for (CtAnnotation<?> annotation : annotationsToExtend) {
            existingTypes.add(annotation.getAnnotationType().getQualifiedName());
        }
        for (CtAnnotation<?> annotation : additionalAnnotations) {
            if (existingTypes.add(annotation.getAnnotationType().getQualifiedName())) {
                annotationsToExtend.add(annotation);
            }
        }
    }

    private List<CtAnnotation<?>> matchingWrapperParameterAnnotations(CtMethod<?> wrapperMethod,
            CtInvocation<?> apiInvocation, int apiParameterIndex) {
        if (apiParameterIndex >= apiInvocation.getArguments().size()) {
            return List.of();
        }
        CtExpression<?> argument = apiInvocation.getArguments().get(apiParameterIndex);
        if (!(argument instanceof CtVariableRead<?>)) {
            return List.of();
        }

        String passedParameterName = ((CtVariableRead<?>) argument).getVariable().getSimpleName();
        for (CtParameter<?> wrapperParameter : wrapperMethod.getParameters()) {
            if (wrapperParameter.getSimpleName().equals(passedParameterName)) {
                return matchingAnnotations(wrapperParameter.getAnnotations());
            }
        }
        return List.of();
    }

    private List<CtAnnotation<?>> matchingAnnotations(List<CtAnnotation<?>> candidateAnnotations) {
        List<CtAnnotation<?>> matches = new ArrayList<>();
        for (CtAnnotation<?> annotation : candidateAnnotations) {
            String qualifiedName = annotation.getAnnotationType().getQualifiedName();
            String simpleName = annotation.getAnnotationType().getSimpleName();
            if (clientAnnotations.containsKey(qualifiedName) || clientAnnotations.containsKey(simpleName)) {
                matches.add(annotation);
            }
        }
        return matches;
    }

    private Resource createConstraint(String apiMethodUri, int sourceLine, String parameterSuffix,
            CtAnnotation<?> annotation, int annotationIndex) {
        if (sourceLine < 1) {
            throw new IllegalStateException("Cannot create API constraint without a valid client source line");
        }
        String parameterId = parameterSuffix + (annotationIndex == 0 ? "" : "_a" + annotationIndex);
        String constraintUri = apiMethodUri + "_constraint_" + sourceLine + parameterId;
        Resource constraint = constraintModel.createResource(constraintUri)
                .addProperty(RDF.type, constraintModel.createResource(ICODM + "ApiClientConstraint"));
        String annotationUri = constraintUri + "_a";
        if (annotationIndex > 0) {
            annotationUri += annotationIndex;
        }
        Resource annotationResource = constraintModel.createResource(annotationUri)
                .addProperty(RDF.type, constraintModel.createResource(ICODM + "JavaConstraintAnnotation"))
                .addProperty(RDF.type, constraintModel.createResource(WOC + "Annotation"))
                .addProperty(rdfsLabel, annotation.getAnnotationType().getSimpleName());
        constraint.addProperty(annotations, annotationResource);

        List<String> properties = clientAnnotations.get(annotation.getAnnotationType().getQualifiedName());
        if (properties == null) {
            properties = clientAnnotations.get(annotation.getAnnotationType().getSimpleName());
        }
        if (properties == null) {
            throw new IllegalStateException("Configured properties were not found for annotation "
                    + annotation.getAnnotationType().getQualifiedName());
        }
        List<Resource> propertyResources = new ArrayList<>();
        for (String propertyName : properties) {
            CtExpression<?> value = annotationPropertyValue(annotation, propertyName);
            String propertyUri = annotationUri + "_" + annotationCode(annotation.getAnnotationType().getSimpleName());
            if (properties.size() > 1) {
                propertyUri += "_" + propertyName;
            }
            Resource propertyResource = constraintModel.createResource(propertyUri)
                    .addProperty(RDF.type, constraintModel.createResource(ICODM + "CoAProperty"))
                    .addProperty(rdfsLabel, propertyName)
                    .addProperty(rdfValue, toRdfValue(value));
            propertyResources.add(propertyResource);
        }
        if (!propertyResources.isEmpty()) {
            annotationResource.addProperty(annotationProperties,
                    constraintModel.createList(propertyResources.iterator()));
        }
        return constraint;
    }

    private CtExpression<?> annotationPropertyValue(CtAnnotation<?> annotation, String propertyName) {
        CtExpression<?> value = annotation.getValues().get(propertyName);
        if (value == null) {
            CtType<?> annotationType = annotation.getAnnotationType().getTypeDeclaration();
            if (annotationType != null) {
                for (CtMethod<?> method : annotationType.getMethodsByName(propertyName)) {
                    if (method instanceof CtAnnotationMethod) {
                        value = ((CtAnnotationMethod<?>) method).getDefaultExpression();
                        break;
                    }
                }
            }
        }
        if (value == null) {
            throw new IllegalStateException("Configured property " + propertyName + " has no value or default on "
                    + annotation.getAnnotationType().getQualifiedName());
        }
        return value;
    }

    private RDFNode toRdfValue(CtExpression<?> expression) {
        if (expression instanceof CtLiteral<?>) {
            return constraintModel.createTypedLiteral(((CtLiteral<?>) expression).getValue());
        }
        if (expression instanceof CtNewArray<?>) {
            List<RDFNode> values = new ArrayList<>();
            for (CtExpression<?> element : ((CtNewArray<?>) expression).getElements()) {
                values.add(toRdfValue(element));
            }
            return constraintModel.createList(values.iterator());
        }
        if (expression instanceof CtFieldAccess<?>) {
            return constraintModel.createLiteral(((CtFieldAccess<?>) expression).getVariable().getSimpleName());
        }
        return constraintModel.createLiteral(expression.toString());
    }

    private String annotationCode(String annotationName) {
        StringBuilder code = new StringBuilder();
        for (int index = 0; index < annotationName.length(); index++) {
            char character = annotationName.charAt(index);
            if (Character.isUpperCase(character)) {
                code.append(Character.toLowerCase(character));
            }
        }
        return code.length() == 0 ? annotationName.toLowerCase() : code.toString();
    }

    private String buildMemberUri(CtExecutableReference<?> reference) {
        CtExecutable<?> declaration = reference.getExecutableDeclaration();
        List<String> parameterTypes = declaration == null
                ? reference.getParameters().stream()
                    .map(CtTypeReference::getQualifiedName)
                    .toList()
                : declaration.getParameters().stream()
                    .map(param -> param.getType().getQualifiedName())
                    .toList();
        String declaringType = reference.getDeclaringType().getQualifiedName();
        String memberName = reference.isConstructor() ? "constructor" : reference.getSimpleName();
        String signature = declaringType + "#" + memberName + "(" + String.join(",", parameterTypes) + ")";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(signature.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (int index = 0; index < 6; index++) {
                hash.append(String.format("%02x", hashBytes[index]));
            }
            return ICODM + declaringType + "_" + memberName + "_" + hash + "_stable";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
