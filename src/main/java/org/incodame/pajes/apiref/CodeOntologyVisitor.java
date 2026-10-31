package org.incodame.pajes.apiref;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.SimpleElementVisitor14;

import org.revapi.java.spi.*;

public class CodeOntologyVisitor extends SimpleElementVisitor14<Resource, Void> {
    
    private final Model model;
    private final String wocNs;
    private final String appNs;
    private final ClassHierarchy classHierarchy;
    private final String apiVersion;
    private final SourceJavadocIndex javadocIndex;

    // Core Classes & Structural Properties
    private final Resource wocClass;
    private final Resource wocInterface;
    private final Resource wocMethod;
    private final Resource wocConstructor;
    private final Resource wocField;
    private final Property hasMethod;
    private final Property hasConstructor;
    private final Property hasField;
    private final Property hasSimpleName;
    private final Property hasReturnType;
    private final Resource owlClass;
    private final Property subClassOf;
    private final Property parameters;
    private final Resource parameterClass;
    private final Property parameterIndex;
    private final Property parameterType;
    private final Property parameterName;
    private final Property methodJavadoc;
    private final Property parameterJavadoc;

    // CodeOntology Visibility Properties & Individuals
    private final Property hasModifier;
    private final Resource modifierPublic;
    private final Resource modifierPrivate;
    private final Resource modifierProtected;

    public CodeOntologyVisitor(Model model, String wocNs, String appNs) {
        this(model, wocNs, appNs, null, null, null);
    }

    public CodeOntologyVisitor(Model model, String wocNs, String appNs, ClassHierarchy classHierarchy, String apiVersion) {
        this(model, wocNs, appNs, classHierarchy, apiVersion, null);
    }

    public CodeOntologyVisitor(Model model, String wocNs, String appNs, ClassHierarchy classHierarchy, String apiVersion,
            SourceJavadocIndex javadocIndex) {
        this.model = model;
        this.wocNs = wocNs;
        this.appNs = appNs;
        this.classHierarchy = classHierarchy;
        this.apiVersion = apiVersion;
        this.javadocIndex = javadocIndex;

        // Initialize existing elements
        this.wocClass = model.createResource(wocNs + "Class");
        this.wocInterface = model.createResource(wocNs + "Interface");
        this.wocMethod = model.createResource(wocNs + "Method");
        this.wocConstructor = model.createResource(wocNs + "Constructor");
        this.wocField = model.createResource(wocNs + "Field");
        this.hasMethod = model.createProperty(wocNs + "hasMethod");
        this.hasConstructor = model.createProperty(wocNs + "hasConstructor");
        this.hasField = model.createProperty(wocNs + "hasField");
        this.hasSimpleName = model.createProperty(wocNs + "hasSimpleName");
        this.hasReturnType = model.createProperty(wocNs + "hasReturnType");
        this.owlClass = model.createResource("http://www.w3.org/2002/07/owl#Class");
        this.subClassOf = model.createProperty("http://www.w3.org/2000/01/rdf-schema#subClassOf");
        this.parameters = model.createProperty(wocNs + "parameters");
        this.parameterClass = model.createResource(wocNs + "Parameter");
        this.parameterIndex = model.createProperty(wocNs + "parameterIndex");
        this.parameterType = model.createProperty(wocNs + "parameterType");
        this.parameterName = model.createProperty("http://www.w3.org/2000/01/rdf-schema#label");
        this.methodJavadoc = model.createProperty(appNs + "methodJavadoc");
        this.parameterJavadoc = model.createProperty(appNs + "paramJavadoc");

        // Initialize modifier elements
        this.hasModifier = model.createProperty(wocNs + "hasModifier");
        this.modifierPublic = model.createResource(wocNs + "Public");
        this.modifierPrivate = model.createResource(wocNs + "Private");
        this.modifierProtected = model.createResource(wocNs + "Protected");
    }

    private void applyVersion(Resource resource) {
        if (apiVersion != null && !apiVersion.isBlank()) {
            Property applicableFor = model.createProperty(appNs + "applFor");
            resource.addProperty(applicableFor, apiVersion);
        }
    }

    /**
     * Helper method to map javax.lang.model Modifiers to CodeOntology URIs
     */
    private void applyVisibilityModifiers(javax.lang.model.element.Element e, Resource resource) {
        java.util.Set<javax.lang.model.element.Modifier> modifiers = e.getModifiers();

        if (modifiers.contains(javax.lang.model.element.Modifier.PUBLIC)) {
            resource.addProperty(hasModifier, modifierPublic);
        } else if (modifiers.contains(javax.lang.model.element.Modifier.PRIVATE)) {
            resource.addProperty(hasModifier, modifierPrivate);
        } else if (modifiers.contains(javax.lang.model.element.Modifier.PROTECTED)) {
            resource.addProperty(hasModifier, modifierProtected);
        } 
        // Note: Package-private / default visibility typically omits the modifier property in WOC
    }

    @Override
    public Resource visitType(TypeElement e, Void p) {
        String qName = e.getQualifiedName().toString();
        Resource classRes = model.createResource(appNs + qName)
                .addProperty(RDF.type, wocClass)
                .addProperty(RDF.type, owlClass)
                .addProperty(hasSimpleName, e.getSimpleName().toString());
        if (e.getKind() == ElementKind.INTERFACE) {
            classRes.addProperty(RDF.type, wocInterface);
        }

        applyVersion(classRes);        

        if (classHierarchy != null && classHierarchy.contains(qName)) {
            for (String supertypeName : classHierarchy.getDirectSupertypes(qName)) {
                addDirectSupertype(supertypeName, classRes);
            }
        } else {
            addDirectSupertype(e.getSuperclass(), classRes);
            for (javax.lang.model.type.TypeMirror interfaceType : e.getInterfaces()) {
                addDirectSupertype(interfaceType, classRes);
            }
        }

        // Extract modifier for the class itself
        applyVisibilityModifiers(e, classRes);

        for (javax.lang.model.element.Element enclosed : e.getEnclosedElements()) {
            Resource childRes = enclosed.accept(this, null);
            if (childRes != null) {
                if (enclosed instanceof ExecutableElement) {
                    ExecutableElement executable = (ExecutableElement) enclosed;
                    classRes.addProperty(
                            executable.getKind() == ElementKind.CONSTRUCTOR ? hasConstructor : hasMethod,
                            childRes);
                } else if (enclosed instanceof VariableElement) {
                    classRes.addProperty(hasField, childRes);
                }
            }
        }
        return classRes;
    }

    private void addDirectSupertype(javax.lang.model.type.TypeMirror type, Resource classResource) {
        if (type.getKind() != TypeKind.DECLARED) {
            return;
        }

        Element supertypeElement = ((DeclaredType) type).asElement();
        if (!(supertypeElement instanceof TypeElement)) {
            return;
        }

        String supertypeName = ((TypeElement) supertypeElement).getQualifiedName().toString();
        addDirectSupertype(supertypeName, classResource);
    }

    private void addDirectSupertype(String supertypeName, Resource classResource) {
        Resource supertypeResource = model.createResource(appNs + supertypeName)
                .addProperty(RDF.type, owlClass);
        classResource.addProperty(subClassOf, supertypeResource);
    }

    @Override
    public Resource visitExecutable(ExecutableElement e, Void p) {
        TypeElement parentClass = (TypeElement) e.getEnclosingElement();
        boolean constructor = e.getKind() == ElementKind.CONSTRUCTOR;
        String memberName = constructor ? "constructor" : e.getSimpleName().toString();
        String memberUri = buildMemberUri(parentClass, e, memberName);
        List<String> parameterTypes = new ArrayList<>();
        for (VariableElement parameter : e.getParameters()) {
            parameterTypes.add(normalizeTypeName(parameter.asType().toString()));
        }
        SourceJavadocIndex.ExecutableDocumentation documentation = javadocIndex == null ? null
                : javadocIndex.find(parentClass.getQualifiedName().toString(),
                        constructor ? parentClass.getSimpleName().toString() : e.getSimpleName().toString(),
                        parameterTypes);

        Resource methodRes = model.createResource(memberUri)
                .addProperty(RDF.type, constructor ? wocConstructor : wocMethod)
                .addProperty(hasSimpleName, e.getSimpleName().toString());
        if (documentation != null && documentation.getComment() != null) {
            methodRes.addProperty(methodJavadoc, documentation.getComment());
        }
        if (!constructor) {
            String returnTypeName = normalizeTypeName(e.getReturnType().toString());
            Resource returnTypeResource = model.createResource(appNs + returnTypeName)
                    .addProperty(RDF.type, owlClass);
            methodRes.addProperty(hasReturnType, returnTypeResource);
        }
        if (!methodRes.hasProperty(parameters)) {        
                methodRes.addProperty(parameters, createParameterList(e, documentation));
        }
        
        applyVersion(methodRes);

        // Extract modifier for the method
        applyVisibilityModifiers(e, methodRes);
        
        return methodRes;
    }

    private RDFList createParameterList(ExecutableElement member,
            SourceJavadocIndex.ExecutableDocumentation documentation) {
        List<RDFNode> parameterResources = new ArrayList<>();
        int index = 0;
        for (VariableElement parameter : member.getParameters()) {
            Resource parameterResource = model.createResource()
                    .addProperty(RDF.type, parameterClass)
                    .addLiteral(parameterIndex, model.createTypedLiteral(index++))
                    .addProperty(parameterType, normalizeTypeName(parameter.asType().toString()));
            SourceJavadocIndex.ParameterDocumentation parameterDocumentation =
                    documentation != null && index <= documentation.getParameters().size()
                            ? documentation.getParameters().get(index - 1) : null;
            String name = parameterDocumentation == null
                    ? parameter.getSimpleName().toString() : parameterDocumentation.getName();
            parameterResource.addProperty(parameterName, name);
            if (parameterDocumentation != null && parameterDocumentation.getComment() != null) {
                parameterResource.addProperty(parameterJavadoc, parameterDocumentation.getComment());
            }
            parameterResources.add(parameterResource);
        }
        return model.createList(parameterResources.iterator());
    }

    private String buildMemberUri(TypeElement declaringType, ExecutableElement member, String memberName) {
        String qualifiedType = declaringType.getQualifiedName().toString();
        List<String> parameterTypes = new ArrayList<>();
        for (VariableElement parameter : member.getParameters()) {
            parameterTypes.add(normalizeTypeName(parameter.asType().toString()));
        }
        String signature = qualifiedType + "#" + memberName + "(" + String.join(",", parameterTypes) + ")";

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(signature.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (int index = 0; index < 6; index++) {
                hash.append(String.format("%02x", digest[index]));
            }
            String uriMemberName = memberName.equals("<clinit>") ? "staticInitializer" : memberName;
            return appNs + qualifiedType + "_" + uriMemberName + "_" + hash + "_stable";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String normalizeTypeName(String typeName) {
        int genericStart = typeName.indexOf('<');
        if (genericStart >= 0) {
            String suffix = typeName.substring(typeName.lastIndexOf('>') + 1);
            return (typeName.substring(0, genericStart) + suffix).trim();
        }
        return typeName;
    }

    @Override
    public Resource visitVariable(VariableElement e, Void p) {
        if (e.getKind().isField()) {
            TypeElement parentClass = (TypeElement) e.getEnclosingElement();
            String fieldUri = appNs + parentClass.getQualifiedName().toString() + "." + e.getSimpleName().toString();
            
            Resource fieldRes = model.createResource(fieldUri)
                    .addProperty(RDF.type, wocField)
                    .addProperty(hasSimpleName, e.getSimpleName().toString());

            applyVersion(fieldRes);
            
            // Extract modifier for the field
            applyVisibilityModifiers(e, fieldRes);
            
            return fieldRes;
        }
        return null;
    }
}