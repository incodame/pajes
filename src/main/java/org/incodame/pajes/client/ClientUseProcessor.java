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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

import org.incodame.pajes.annotations.ApiTerm;
import org.incodame.pajes.annotations.ApiTypeConsumer;

public class ClientUseProcessor extends AbstractProcessor<CtElement> {

    private final String targetPackage;
    private final String branch;
    private final Map<String, List<String>> clientAnnotations;
    private final Map<String, Set<String>> semanticProperties;
    private final Map<String, String> semanticPrefixes;
    private final Map<String, String> semanticTerms;
    private final Map<String, String> controlledTermAliases;
    private final Set<String> clientPackages = new LinkedHashSet<>();
    private final Set<String> targetClientPackages = new LinkedHashSet<>();
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*");

    // Initialisation du modèle Jena et des préfixes graphiques
    private final Model model = ModelFactory.createDefaultModel();
    private final String ICODM = "https://github.com/Incodam/";// Préfixe de votre refmodule
    private final String WOC = "http://rdf.webofcode.org/woc/";
    
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
    private final Property onCallback = constraintModel.createProperty(ICODM + "onCallback");
    private final Property onCallbackReturn = constraintModel.createProperty(ICODM + "onCallbackReturn");
    private final Property onTypeUse = constraintModel.createProperty(ICODM + "onTypeUse");
    private final Property hasContraints = constraintModel.createProperty(ICODM + "hasContraints");
    private final Property annotations = constraintModel.createProperty(ICODM + "annotations");
    private final Property annotationProperties = constraintModel.createProperty(ICODM + "annotationProperties");
    private final Property rdfValue = constraintModel.createProperty(RDF.value.getURI());
    private final Property refersToTerm = constraintModel.createProperty(ICODM + "refersToTerm");
    private final Property rdfsLabel = constraintModel.createProperty(
            "http://www.w3.org/2000/01/rdf-schema#label");

    public ClientUseProcessor(String targetPackage, String branch) {
        this(targetPackage, branch, Map.of());
    }

    public ClientUseProcessor(String targetPackage, String branch, Map<String, List<String>> clientAnnotations) {
        this(targetPackage, branch, clientAnnotations, Map.of(), Map.of(), Map.of());
    }

    public ClientUseProcessor(String targetPackage, String branch, Map<String, List<String>> clientAnnotations,
            Map<String, Set<String>> semanticProperties, Map<String, String> semanticPrefixes,
            Map<String, String> semanticTerms) {
        this(targetPackage, branch, clientAnnotations, semanticProperties, semanticPrefixes, semanticTerms, Map.of());
    }

    public ClientUseProcessor(String targetPackage, String branch, Map<String, List<String>> clientAnnotations,
            Map<String, Set<String>> semanticProperties, Map<String, String> semanticPrefixes,
            Map<String, String> semanticTerms, Map<String, String> controlledTermAliases) {
        if (targetPackage == null || targetPackage.trim().isEmpty()
                || branch == null || branch.trim().isEmpty() || clientAnnotations == null
                || semanticProperties == null || semanticPrefixes == null || semanticTerms == null
                || controlledTermAliases == null) {
            throw new IllegalArgumentException("Target package, branch, and annotation configuration must be valid");
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
        Map<String, Set<String>> semanticPropertyCopy = new LinkedHashMap<>();
        semanticProperties.forEach((name, properties) -> semanticPropertyCopy.put(name,
                Collections.unmodifiableSet(new LinkedHashSet<>(properties))));
        this.semanticProperties = Collections.unmodifiableMap(semanticPropertyCopy);
        this.semanticPrefixes = Collections.unmodifiableMap(new LinkedHashMap<>(semanticPrefixes));
        this.semanticTerms = Collections.unmodifiableMap(new LinkedHashMap<>(semanticTerms));
        this.controlledTermAliases = Collections.unmodifiableMap(new LinkedHashMap<>(controlledTermAliases));

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

    public Set<String> getTargetClientPackages() {
        return java.util.Collections.unmodifiableSet(targetClientPackages);
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
                recordTargetClientPackage(clientType);
            }
        }
        else if (element instanceof CtMethod<?> && isApiCallbackWrapper((CtMethod<?>) element)) {
            addApiCallbackWrapper((CtMethod<?>) element);
        }
        else if (element instanceof CtMethod<?> && isApiImplementation((CtMethod<?>) element)) {
            addApiImplementation((CtMethod<?>) element);
        }
        else if (element instanceof CtMethod<?> && isApiTypeConsumer((CtMethod<?>) element)) {
            addApiTypeConsumer((CtMethod<?>) element);
        }
        else if (element instanceof CtClass<?> || element instanceof CtInterface<?>) {
            CtType<?> clientType = (CtType<?>) element;
            recordClientPackage(clientType);

            if (clientType instanceof CtClass<?>) {
                CtTypeReference<?> superclass = ((CtClass<?>) clientType).getSuperclass();
                if (superclass != null && isInTargetPackage(superclass.getQualifiedName())) {
                    Resource apiClassResource = model.createResource(ICODM + superclass.getQualifiedName());
                    getClientTypeResource(clientType).addProperty(wocExtends, apiClassResource);
                    recordTargetClientPackage(clientType);
                }
            }

            for (CtTypeReference<?> iface : clientType.getSuperInterfaces()) {
                if (isInTargetPackage(iface.getQualifiedName())) {
                    Resource apiInterfaceResource = model.createResource(ICODM + iface.getQualifiedName());
                    getClientTypeResource(clientType).addProperty(clientType instanceof CtInterface<?> ? wocExtends : wocImplements,
                            apiInterfaceResource);
                    recordTargetClientPackage(clientType);
                }
            }
        }
    }

    private void addApiMethodCall(CtInvocation<?> clientCall, CtInvocation<?> apiInvocation,
            CtExecutableReference<?> apiMethodReference, CtType<?> clientType, CtMethod<?> wrapperMethod) {
        String apiMethodUri = buildMemberUri(apiMethodReference);
        Resource clientResource = getClientTypeResource(clientType);
        clientResource.addProperty(wocCalls, model.createResource(apiMethodUri));
        recordTargetClientPackage(clientType);
        addInvocationConstraints(clientCall, apiInvocation, apiMethodReference, apiMethodUri,
                clientResource, wrapperMethod);
    }

    private void addApiWrapperCall(CtInvocation<?> clientCall, CtMethod<?> wrapperMethod, CtType<?> clientType) {
        List<CtInvocation> apiInvocations = wrapperMethod.getElements(
                new spoon.reflect.visitor.filter.TypeFilter<>(CtInvocation.class)).stream()
                .filter(invocation -> isInTargetPackage(invocation.getExecutable()))
                .toList();
        if (apiInvocations.isEmpty()) {
            return;
        }
        if (apiInvocations.size() > 1) {
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

    private boolean isApiCallbackWrapper(CtMethod<?> method) {
        return method.getAnnotations().stream()
                .anyMatch(annotation -> "ApiCallbackWrapper".equals(annotation.getAnnotationType().getSimpleName()));
    }

    private boolean isApiImplementation(CtMethod<?> method) {
        return method.getAnnotations().stream()
                .anyMatch(annotation -> "ApiImplementation".equals(annotation.getAnnotationType().getSimpleName()));
    }

    private boolean isApiTypeConsumer(CtMethod<?> method) {
        return method.getAnnotations().stream()
                .anyMatch(annotation -> "ApiTypeConsumer".equals(annotation.getAnnotationType().getSimpleName()));
    }

    private void addApiTypeConsumer(CtMethod<?> consumerMethod) {
        CtType<?> clientType = consumerMethod.getDeclaringType();
        Resource constraintClientResource = constraintModel.createResource(ICODM + clientType.getQualifiedName())
                .addProperty(constraintModel.createProperty(ICODM + "applBranch"), branch);
        int sourceLine = consumerMethod.getPosition().isValidPosition()
                ? consumerMethod.getPosition().getLine() : -1;
        List<Resource> controlledTermConstraints = new ArrayList<>();
        String consumerMethodUri = buildMemberUri(consumerMethod.getReference());

        for (int parameterIndex = 0; parameterIndex < consumerMethod.getParameters().size(); parameterIndex++) {
            CtParameter<?> parameter = consumerMethod.getParameters().get(parameterIndex);
            CtTypeReference<?> parameterType = parameter.getType();
            if (parameterType == null || !isInTargetPackage(parameterType.getQualifiedName())) {
                continue;
            }
            recordTargetClientPackage(clientType);

            List<CtAnnotation<?>> matchedAnnotations = matchingAnnotations(parameter.getAnnotations());
            for (int annotationIndex = 0; annotationIndex < matchedAnnotations.size(); annotationIndex++) {
                CtAnnotation<?> annotation = matchedAnnotations.get(annotationIndex);
                if ("ApiTerm".equals(annotation.getAnnotationType().getSimpleName())) {
                    controlledTermConstraints.add(createConstraint(consumerMethodUri, sourceLine,
                            "_" + (parameterIndex + 1), annotation, annotationIndex));
                } else {
                    Resource constraint = createConstraint(
                            ICODM + parameterType.getQualifiedName(), sourceLine,
                            "_" + (parameterIndex + 1), annotation, annotationIndex);
                    constraintClientResource.addProperty(onTypeUse, constraint);
                }
            }
        }
        if (!controlledTermConstraints.isEmpty()) {
            String constraintSetUri = consumerMethodUri + "_constraint_set_" + sourceLine;
            Resource constraintSet = constraintModel.createResource(constraintSetUri)
                    .addProperty(RDF.type, constraintModel.createResource(ICODM + "ApiClientConstraintSet"))
                    .addProperty(hasContraints,
                            constraintModel.createList(controlledTermConstraints.iterator()));
            constraintClientResource.addProperty(onTypeUse, constraintSet);
        }
    }

    private void addApiCallbackWrapper(CtMethod<?> callbackMethod) {
        List<CtMethod<?>> apiMethods = callbackMethod.getTopDefinitions().stream()
                .filter(method -> isInTargetPackage(method.getDeclaringType().getQualifiedName()))
                .toList();
        if (apiMethods.size() != 1) {
            throw new IllegalStateException("@ApiCallbackWrapper method must override exactly one method from target "
                    + "package " + targetPackage + ": " + callbackMethod.getDeclaringType().getQualifiedName()
                    + "#" + callbackMethod.getSimpleName() + " (found " + apiMethods.size() + ")");
        }

        CtMethod<?> apiMethod = apiMethods.get(0);
        if (apiMethod.getParameters().size() != callbackMethod.getParameters().size()) {
            throw new IllegalStateException("@ApiCallbackWrapper method parameter count does not match API method: "
                    + callbackMethod.getDeclaringType().getQualifiedName() + "#" + callbackMethod.getSimpleName());
        }

        CtType<?> clientType = callbackMethod.getDeclaringType();
        Resource clientResource = getClientTypeResource(clientType);
        recordTargetClientPackage(clientType);
        String apiMethodUri = buildMemberUri(apiMethod.getReference());
        clientResource.addProperty(wocCalls, model.createResource(apiMethodUri));

        int sourceLine = callbackMethod.getPosition().isValidPosition()
                ? callbackMethod.getPosition().getLine() : -1;
        List<List<CtAnnotation<?>>> parameterAnnotations = new ArrayList<>();
        for (CtParameter<?> parameter : callbackMethod.getParameters()) {
            parameterAnnotations.add(matchingAnnotations(parameter.getAnnotations()));
        }
        List<CtAnnotation<?>> returnAnnotations = matchingReturnAnnotations(callbackMethod);
        addMethodConstraints(apiMethod, apiMethodUri, sourceLine, clientResource,
                parameterAnnotations, returnAnnotations, onCallback, onCallbackReturn);
    }

    private void addApiImplementation(CtMethod<?> implementationMethod) {
        List<CtMethod<?>> apiMethods = implementationMethod.getTopDefinitions().stream()
                .filter(method -> isInTargetPackage(method.getDeclaringType().getQualifiedName()))
                .toList();
        if (apiMethods.size() != 1
                || apiMethods.get(0).getParameters().size() != implementationMethod.getParameters().size()) {
            return;
        }
        CtMethod<?> apiMethod = apiMethods.get(0);
        CtType<?> clientType = implementationMethod.getDeclaringType();
        Resource clientResource = getClientTypeResource(clientType);
        recordTargetClientPackage(clientType);
        String apiMethodUri = buildMemberUri(apiMethod.getReference());
        int sourceLine = implementationMethod.getPosition().isValidPosition()
                ? implementationMethod.getPosition().getLine() : -1;

        List<List<CtAnnotation<?>>> implementationParameterAnnotations = new ArrayList<>();
        for (CtParameter<?> parameter : implementationMethod.getParameters()) {
            implementationParameterAnnotations.add(matchingAnnotations(parameter.getAnnotations()));
        }
        List<CtAnnotation<?>> implementationReturnAnnotations = matchingReturnAnnotations(implementationMethod);
        addMethodConstraints(apiMethod, apiMethodUri, sourceLine, clientResource,
                implementationParameterAnnotations, implementationReturnAnnotations,
                onCallback, onCallbackReturn);
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

    @ApiTypeConsumer
    private void recordTargetClientPackage(@ApiTerm(term = "class") CtType<?> clientType) {
        recordClientPackage(clientType);
        CtPackage ctPackage = clientType.getPackage();
        if (ctPackage == null) {
            ctPackage = clientType.getParent(CtPackage.class);
        }
        if (ctPackage != null && !ctPackage.getQualifiedName().isEmpty()) {
            targetClientPackages.add(ctPackage.getQualifiedName());
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
        CtExecutable<?> declaration = executableReference.getExecutableDeclaration();
        if (!(declaration instanceof CtMethod<?>)) {
            return;
        }

        int sourceLine = clientCall.getPosition().isValidPosition() ? clientCall.getPosition().getLine() : -1;
        CtMethod<?> apiMethod = (CtMethod<?>) declaration;
        List<List<CtAnnotation<?>>> extraParameterAnnotations = new ArrayList<>();
        for (int index = 0; index < apiMethod.getParameters().size(); index++) {
            List<CtAnnotation<?>> extraAnnotations = wrapperMethod == null
                    ? List.of()
                    : matchingWrapperParameterAnnotations(wrapperMethod, apiInvocation, index);
            extraParameterAnnotations.add(extraAnnotations);
        }
        List<CtAnnotation<?>> extraReturnAnnotations = wrapperMethod != null
                && isDirectlyReturnedByWrapper(wrapperMethod, apiInvocation)
                        ? matchingWrapperReturnAnnotations(wrapperMethod)
                        : List.of();
        addMethodConstraints(apiMethod, apiMethodUri, sourceLine, clientResource,
                extraParameterAnnotations, extraReturnAnnotations, onMethodCall, onMethodReturn);
    }

    private void addMethodConstraints(CtMethod<?> apiMethod, String apiMethodUri, int sourceLine,
            Resource clientResource, List<List<CtAnnotation<?>>> extraParameterAnnotations,
            List<CtAnnotation<?>> extraReturnAnnotations, Property parameterConstraintProperty,
            Property returnConstraintProperty) {
        Resource constraintClientResource = constraintModel.createResource(clientResource.getURI())
                .addProperty(constraintModel.createProperty(ICODM + "applBranch"), branch);
        List<Resource> parameterConstraints = new ArrayList<>();
        List<CtParameter<?>> parameters = apiMethod.getParameters();
        for (int index = 0; index < parameters.size(); index++) {
            CtParameter<?> parameter = parameters.get(index);
            List<CtAnnotation<?>> matchedAnnotations = new ArrayList<>(matchingAnnotations(parameter.getAnnotations()));
            if (index < extraParameterAnnotations.size()) {
                appendDistinctAnnotations(matchedAnnotations, extraParameterAnnotations.get(index));
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
            constraintClientResource.addProperty(parameterConstraintProperty, constraintSet);
        }

        List<CtAnnotation<?>> returnAnnotations = matchingReturnAnnotations(apiMethod);
        appendDistinctAnnotations(returnAnnotations, extraReturnAnnotations);
        for (int annotationIndex = 0; annotationIndex < returnAnnotations.size(); annotationIndex++) {
            Resource constraint = createConstraint(apiMethodUri, sourceLine, "",
                    returnAnnotations.get(annotationIndex), annotationIndex);
            constraintClientResource.addProperty(returnConstraintProperty, constraint);
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
            List<Resource> semanticReferences = semanticReferences(annotation, propertyName, value);
            if (!semanticReferences.isEmpty()) {
                propertyResource.addProperty(constraintModel.createProperty(ICODM + "semanticallyRefersTo"),
                        constraintModel.createList(semanticReferences.iterator()));
            }
            List<Resource> controlledTermReferences = controlledTermReferences(annotation, propertyName, value);
            if (!controlledTermReferences.isEmpty()) {
                propertyResource.addProperty(refersToTerm,
                        constraintModel.createList(controlledTermReferences.iterator()));
            }
            propertyResources.add(propertyResource);
        }
        if (!propertyResources.isEmpty()) {
            annotationResource.addProperty(annotationProperties,
                    constraintModel.createList(propertyResources.iterator()));
        }
        return constraint;
    }

    private List<Resource> semanticReferences(CtAnnotation<?> annotation, String propertyName,
            CtExpression<?> value) {
        Set<String> selectedProperties = semanticProperties.get(annotation.getAnnotationType().getQualifiedName());
        if (selectedProperties == null) {
            selectedProperties = semanticProperties.get(annotation.getAnnotationType().getSimpleName());
        }
        if (selectedProperties == null || !selectedProperties.contains(propertyName)
                || !(value instanceof CtLiteral<?>)) {
            return List.of();
        }
        Object literalValue = ((CtLiteral<?>) value).getValue();
        if (!(literalValue instanceof String)) {
            return List.of();
        }

        List<Resource> references = new ArrayList<>();
        Set<String> seenTerms = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher((String) literalValue);
        while (matcher.find()) {
            String term = matcher.group();
            String mappedCurie = semanticTerms.get(term);
            if (mappedCurie != null && seenTerms.add(term)) {
                int separator = mappedCurie.indexOf(':');
                String prefix = mappedCurie.substring(0, separator);
                String localName = mappedCurie.substring(separator + 1);
                String namespace = semanticPrefixes.get(prefix);
                if (namespace == null) {
                    throw new IllegalStateException("No namespace configured for semantic prefix " + prefix);
                }
                constraintModel.setNsPrefix(prefix, namespace);
                references.add(constraintModel.createResource(namespace + localName));
            }
        }
        return references;
    }

    private List<Resource> controlledTermReferences(CtAnnotation<?> annotation, String propertyName,
            CtExpression<?> value) {
        if (!"ApiTerm".equals(annotation.getAnnotationType().getSimpleName())
                || !"term".equals(propertyName) || !(value instanceof CtLiteral<?>)
                || !(((CtLiteral<?>) value).getValue() instanceof String)) {
            return List.of();
        }

        String annotationValue = normalizeControlledTerm((String) ((CtLiteral<?>) value).getValue());
        String matchedUri = null;
        int longestMatch = -1;
        for (Map.Entry<String, String> termAlias : controlledTermAliases.entrySet()) {
            if (!containsControlledTerm(annotationValue, termAlias.getKey())) {
                continue;
            }
            int matchLength = termAlias.getKey().length();
            if (matchLength > longestMatch) {
                matchedUri = termAlias.getValue();
                longestMatch = matchLength;
            } else if (matchLength == longestMatch && !termAlias.getValue().equals(matchedUri)) {
                throw new IllegalStateException("ApiTerm value '" + annotationValue
                        + "' ambiguously matches multiple controlled terms");
            }
        }
        return matchedUri == null ? List.of() : List.of(constraintModel.createResource(matchedUri));
    }

    private boolean containsControlledTerm(String value, String alias) {
        String phrase = java.util.Arrays.stream(alias.split(" "))
                .map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("\\s+"));
        Pattern pattern = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])" + phrase
                + "(?![\\p{L}\\p{N}_])");
        return pattern.matcher(value).find();
    }

    private String normalizeControlledTerm(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
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
        String codeName = annotationName.startsWith("Api") && annotationName.length() > 3
                ? annotationName.substring(3) : annotationName;
        for (int index = 0; index < codeName.length(); index++) {
            char character = codeName.charAt(index);
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
