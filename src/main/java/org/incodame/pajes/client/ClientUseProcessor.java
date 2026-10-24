package org.incodame.pajes.client;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

import spoon.processing.AbstractProcessor;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;

public class ClientUseProcessor extends AbstractProcessor<CtElement> {

    private final String targetPackage;
    private final String branch;
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
    private final Resource owlClass = model.createResource("http://www.w3.org/2002/07/owl#Class");

    public ClientUseProcessor(String targetPackage, String branch) {
        if (targetPackage == null || targetPackage.trim().isEmpty()
                || branch == null || branch.trim().isEmpty()) {
            throw new IllegalArgumentException("Target package and branch must not be empty");
        }
        this.targetPackage = targetPackage;
        this.branch = branch;

        // Configuration des préfixes pour un affichage propre en Turtle (.ttl)
        model.setNsPrefix("icodm", ICODM);
        model.setNsPrefix("woc", WOC);
        model.setNsPrefix("owl", "http://www.w3.org/2002/07/owl#");
    }

    public Model getModel() {
        return model;
    }

    public Set<String> getClientPackages() {
        return java.util.Collections.unmodifiableSet(clientPackages);
    }

    @Override
    public void process(CtElement element) {
        
        // 1. Détection des appels de méthodes
        if (element instanceof CtInvocation<?>) {
            CtInvocation<?> invocation = (CtInvocation<?>) element;
            CtExecutableReference<?> executableRef = invocation.getExecutable();
            
            if (executableRef != null && executableRef.getDeclaringType() != null) {
                String declaringType = executableRef.getDeclaringType().getQualifiedName();
                
                if (isInTargetPackage(declaringType)) {
                    // Construction de l'identifiant de la méthode (ex: icodm:org.incodame...Parameter_getName..._stable)
                    String apiMethodUri = buildMemberUri(executableRef); //ICODM + declaringType + "_" + executableRef.getSimpleName() + "_stable";
                    
                    // Récupération de la classe cliente qui effectue l'appel
                    CtClass<?> parentClass = element.getParent(CtClass.class);
                    if (parentClass != null) {
                        Resource clientResource = model.createResource(ICODM + parentClass.getQualifiedName());
                        Resource apiMethodResource = model.createResource(apiMethodUri);
                        
                        // Ajout du triplet : [Classe Cliente] -> woc:calls -> [Méthode de l'API]
                        clientResource.addProperty(wocCalls, apiMethodResource);
                    }
                }
            }
        }
        
        // 2. Détection des appels de Constructeurs (<init>)
        else if (element instanceof CtConstructorCall<?>) {
            CtConstructorCall<?> consCall = (CtConstructorCall<?>) element;
            CtExecutableReference<?> executableRef = consCall.getExecutable();
            
            if (executableRef != null && executableRef.getDeclaringType() != null) {
                String declaringType = executableRef.getDeclaringType().getQualifiedName();
                
                if (isInTargetPackage(declaringType)) {
                    // Correspondance avec l'identifiant de votre constructeur (ex: ...Parameter_constructor_..._stable)
                    String apiConstructorUri = buildMemberUri(executableRef); //ICODM + declaringType + "_constructor_stable";
                    
                    CtClass<?> parentClass = element.getParent(CtClass.class);
                    if (parentClass != null) {
                        Resource clientResource = model.createResource(ICODM + parentClass.getQualifiedName());
                        Resource apiConstructorResource = model.createResource(apiConstructorUri);
                        
                        clientResource.addProperty(wocCalls, apiConstructorResource);
                    }
                }
            }
        }

        // 3. Détection de l'Héritage (extends) et des Implémentations (implements)
        else if (element instanceof CtClass<?>) {
            CtClass<?> ctClass = (CtClass<?>) element;
            Resource clientClassResource = getClientClassResource(ctClass);
            
            // Vérification de la Superclasse
            CtTypeReference<?> superclass = ctClass.getSuperclass();
            if (superclass != null && isInTargetPackage(superclass.getQualifiedName())) {
                Resource apiClassResource = model.createResource(ICODM + superclass.getQualifiedName());
                clientClassResource.addProperty(wocExtends, apiClassResource);
            }
            
            // Vérification des Interfaces
            for (CtTypeReference<?> iface : ctClass.getSuperInterfaces()) {
                if (isInTargetPackage(iface.getQualifiedName())) {
                    Resource apiInterfaceResource = model.createResource(ICODM + iface.getQualifiedName());
                    clientClassResource.addProperty(wocImplements, apiInterfaceResource);
                }
            }

        }
    }

    private Resource getClientClassResource(CtClass<?> ctClass) {
        CtPackage ctPackage = ctClass.getPackage();
        if (ctPackage == null) {
            ctPackage = ctClass.getParent(CtPackage.class);
        }
        if (ctPackage != null && !ctPackage.getQualifiedName().isEmpty()) {
            String packageName = ctPackage.getQualifiedName();
            clientPackages.add(packageName);
        }
        return model.createResource(ICODM + ctClass.getQualifiedName())
                .addProperty(RDF.type, owlClass)
                .addProperty(RDF.type, wocClass)
                .addProperty(applBranch, branch);
    }

    private boolean isInTargetPackage(String qualifiedName) {
        return qualifiedName.startsWith(targetPackage + ".");
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
