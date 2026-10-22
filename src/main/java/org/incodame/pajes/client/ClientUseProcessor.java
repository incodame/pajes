package org.incodame.pajes.client;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import spoon.processing.AbstractProcessor;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;

public class ClientUseProcessor extends AbstractProcessor<CtElement> {

    private final String targetPackage;

    // Initialisation du modèle Jena et des préfixes graphiques
    private final Model model = ModelFactory.createDefaultModel();
    private final String ICODM = "https://github.com/Incodam/";// Préfixe de votre refmodule
    private final String WOC = "http://webofcode.org";        // Préfixe de l'ontologie WebOfCode
    
    // Définition des propriétés de liaison
    private final Property wocCalls = model.createProperty(WOC + "calls");
    private final Property wocExtends = model.createProperty(WOC + "extends");
    private final Property wocImplements = model.createProperty(WOC + "implements");

    public ClientUseProcessor(String targetPackage) {
        if (targetPackage == null || targetPackage.trim().isEmpty()) {
            throw new IllegalArgumentException("Target package must not be empty");
        }
        this.targetPackage = targetPackage;

        // Configuration des préfixes pour un affichage propre en Turtle (.ttl)
        model.setNsPrefix("icodm", ICODM);
        model.setNsPrefix("woc", WOC);
    }

    public Model getModel() {
        return model;
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
                    String apiMethodUri = ICODM + declaringType + "_" + executableRef.getSimpleName() + "_stable";
                    
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
                    String apiConstructorUri = ICODM + declaringType + "_constructor_stable";
                    
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
            Resource clientClassResource = model.createResource(ICODM + ctClass.getQualifiedName());
            
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

    private boolean isInTargetPackage(String qualifiedName) {
        return qualifiedName.startsWith(targetPackage + ".");
    }
}
