package org.incodame.pajes.apiref;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.incodame.pajes.apiref.JavaPatchToRdf;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPatchToRdfTest {

        private static List<String> parameterTypes(Resource member) {
                Property parameters = member.getModel().createProperty("http://webofcode.orgparameters");
                Property parameterType = member.getModel().createProperty("http://webofcode.orgparameterType");
                RDFList list = member.getProperty(parameters).getResource().as(RDFList.class);
                return list.asJavaList().stream()
                                .map(node -> node.asResource().getProperty(parameterType).getString())
                                .collect(Collectors.toList());
        }

    @Test
    void generatesRdfFactsForClassConstructors() throws Exception {
        Path sourceFile = Files.createTempFile("ConstructorExample", ".java");
        Files.write(sourceFile, (
                "package demo;\n" +
                "\n" +
                "public class Example {\n" +
                "    public Example(int value) {\n" +
                "    }\n" +
                "\n" +
                "    protected Example(String value, int other) {\n" +
                "    }\n" +
                "}\n"
        ).getBytes());

        Model model = JavaPatchToRdf.buildModelForFile(sourceFile.toString());

        Resource constructorType = model.getResource("http://webofcode.orgConstructor");
        List<Resource> constructors = model.listResourcesWithProperty(RDF.type, constructorType).toList();
        assertTrue(constructors.size() >= 2);

        Property hasModifier = model.createProperty("http://webofcode.orghasModifier");
        Resource publicModifier = model.getResource("http://webofcode.orgPublic");
        Resource protectedModifier = model.getResource("http://webofcode.orgProtected");
        assertTrue(constructors.stream().anyMatch(constructor ->
                constructor.hasProperty(hasModifier, publicModifier)));
        assertTrue(constructors.stream().anyMatch(constructor ->
                constructor.hasProperty(hasModifier, protectedModifier)));
        assertTrue(constructors.stream().allMatch(constructor ->
                constructor.getProperty(hasModifier).getObject().isResource()));
        assertTrue(constructors.stream().allMatch(constructor -> !constructor.getURI().contains("<")));
    }

    @Test
    void generatesRdfFactsForClassMethods() throws Exception {
        Path sourceFile = Files.createTempFile("MethodExample", ".java");
        Files.write(sourceFile, (
                "package demo;\n" +
                "\n" +
                "public class Example {\n" +
                "    public String getName() { return \"x\"; }\n" +
                "    protected void setName(String value) { }\n" +
                "}\n"
        ).getBytes());

        Model model = JavaPatchToRdf.buildModelForFile(sourceFile.toString());

        Resource methodType = model.getResource("http://webofcode.orgMethod");
        assertTrue(model.listResourcesWithProperty(RDF.type, methodType).toSet().size() >= 2);

        Property methodName = model.createProperty("http://webofcode.orgname");
        Resource methodResource = model.listResourcesWithProperty(RDF.type, methodType).toList().stream()
                .filter(method -> method.hasProperty(methodName, "getName"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing getName method record"));
        Property hasReturnType = model.createProperty("http://webofcode.orghasReturnType");
        assertTrue(methodResource.hasProperty(RDF.type, methodType));
        assertTrue(methodResource.getURI().startsWith("https://github.com/Incodam/"));
        assertEquals("https://github.com/Incodam/java.lang.String",
                methodResource.getProperty(hasReturnType).getResource().getURI(),
                "Reference return types should be represented as resource links");

        Resource voidMethod = model.listResourcesWithProperty(RDF.type, methodType).toList().stream()
                .filter(method -> method.hasProperty(methodName, "setName"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing setName method record"));
        assertTrue(voidMethod.hasProperty(hasReturnType, model.getResource("https://github.com/Incodam/void")),
                "Primitive and void return types should also be represented as resources");

        Property hasModifier = model.createProperty("http://webofcode.orghasModifier");
        assertTrue(methodResource.hasProperty(hasModifier, model.getResource("http://webofcode.orgPublic")));
        assertTrue(voidMethod.hasProperty(hasModifier, model.getResource("http://webofcode.orgProtected")));
        assertTrue(methodResource.getProperty(hasModifier).getObject().isResource());
        assertTrue(voidMethod.getProperty(hasModifier).getObject().isResource());
    }

    @Test
    void declaresClassesAsOwlClassesWithDirectInheritance() throws Exception {
        Path sourceFile = Files.createTempFile("InheritedExample", ".java");
        Files.write(sourceFile, (
                "package demo;\n" +
                "public class Child extends external.Base implements external.Contract {\n" +
                "    public void update() { }\n" +
                "}\n"
        ).getBytes());

        Model model = JavaPatchToRdf.buildModelForFile(sourceFile.toString());
        String namespace = "https://github.com/Incodam/";
        Resource child = model.getResource(namespace + "demo.Child");
        Resource owlClass = model.getResource("http://www.w3.org/2002/07/owl#Class");
        Resource wocClass = model.getResource("http://webofcode.orgClass");
        Property subClassOf = model.createProperty("http://www.w3.org/2000/01/rdf-schema#subClassOf");

        assertTrue(child.hasProperty(RDF.type, wocClass));
        assertTrue(child.hasProperty(RDF.type, owlClass));
        assertTrue(child.hasProperty(subClassOf,
                model.getResource(namespace + "external.Base")));
        assertTrue(child.hasProperty(subClassOf,
                model.getResource(namespace + "external.Contract")));
        assertTrue(model.getResource(namespace + "external.Base").hasProperty(RDF.type, owlClass));
        assertTrue(model.getResource(namespace + "external.Contract").hasProperty(RDF.type, owlClass));
        assertTrue(!child.hasProperty(subClassOf, model.getResource(namespace + "java.lang.Object")),
                "Only the declared direct superclass should be linked");
        assertTrue(child.hasProperty(model.createProperty("http://webofcode.orghasMethod")),
                "Class should link to its generated method records");
    }

    @Test
    void generatesInterfaceAndItsImplicitlyPublicMethods() throws Exception {
        Path sourceFile = Files.createTempFile("ExampleContract", ".java");
        Files.write(sourceFile, (
                "package demo;\n" +
                "interface ParentContract { void reset(); }\n" +
                "public interface ExampleContract extends ParentContract {\n" +
                "    String getName();\n" +
                "    default void setName(String name) { }\n" +
                "}\n"
        ).getBytes());

        Model model = JavaPatchToRdf.buildModelForFile(sourceFile.toString());
        String namespace = "https://github.com/Incodam/";
        Resource contract = model.getResource(namespace + "demo.ExampleContract");
        Resource parentContract = model.getResource(namespace + "demo.ParentContract");
        Resource wocInterface = model.getResource("http://webofcode.orgInterface");
        Resource owlClass = model.getResource("http://www.w3.org/2002/07/owl#Class");
        Property subClassOf = model.createProperty("http://www.w3.org/2000/01/rdf-schema#subClassOf");
        Property hasMethod = model.createProperty("http://webofcode.orghasMethod");
        Property hasConstructor = model.createProperty("http://webofcode.orghasConstructor");
        Property methodName = model.createProperty("http://webofcode.orgname");
        Property hasModifier = model.createProperty("http://webofcode.orghasModifier");
        Resource publicModifier = model.getResource("http://webofcode.orgPublic");

        assertTrue(contract.hasProperty(RDF.type, wocInterface));
        assertTrue(contract.hasProperty(RDF.type, owlClass));
        assertTrue(contract.hasProperty(subClassOf, parentContract),
                "Extended interfaces should be represented as direct supertypes");
        assertTrue(parentContract.hasProperty(RDF.type, wocInterface));
        assertTrue(contract.hasProperty(hasMethod));
        assertTrue(!contract.hasProperty(hasConstructor),
                "Interfaces should not produce constructor records");

        List<Resource> methods = model.listResourcesWithProperty(RDF.type,
                model.getResource("http://webofcode.orgMethod")).toList();
        for (String name : List.of("getName", "setName")) {
            Resource method = methods.stream()
                    .filter(candidate -> candidate.hasProperty(methodName, name))
                    .filter(candidate -> candidate.getURI().contains("demo.ExampleContract_"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing interface method " + name));
            assertTrue(method.hasProperty(hasModifier, publicModifier),
                    "Interface methods are implicitly public");
        }
    }

    @Test
    void linksClassesWithoutExplicitSuperclassToObject() throws Exception {
        Path sourceFile = Files.createTempFile("RootExample", ".java");
        Files.write(sourceFile, (
                "package demo;\n" +
                "public class RootExample {\n" +
                "    public void run() { }\n" +
                "}\n"
        ).getBytes());

        Model model = JavaPatchToRdf.buildModelForFile(sourceFile.toString());
        Resource rootClass = model.getResource("https://github.com/Incodam/demo.RootExample");
        Resource objectClass = model.getResource("https://github.com/Incodam/java.lang.Object");
        Resource owlClass = model.getResource("http://www.w3.org/2002/07/owl#Class");
        Property subClassOf = model.createProperty("http://www.w3.org/2000/01/rdf-schema#subClassOf");

        assertTrue(rootClass.hasProperty(subClassOf, objectClass),
                "A class without an explicit superclass implicitly extends java.lang.Object");
        assertTrue(objectClass.hasProperty(RDF.type, owlClass),
                "Implicit superclass should be declared as an OWL class");
    }

    @Test
    void usesCompactDistinctMethodUrisAndOrderedParameterLists() throws Exception {
        Path sourceFile = Files.createTempFile("OverloadedMethodExample", ".java");
        Files.write(sourceFile, (
                "package demo;\n" +
                "public class Example {\n" +
                "    public void update(String name) { }\n" +
                "    public void update(String name, int count) { }\n" +
                "}\n"
        ).getBytes());

        Model model = JavaPatchToRdf.buildModelForFile(sourceFile.toString());
        List<Resource> overloads = model.listResourcesWithProperty(RDF.type, model.getResource("http://webofcode.orgMethod"))
                .toList().stream()
                .filter(resource -> resource.hasProperty(model.createProperty("http://webofcode.orgname"), "update"))
                .collect(Collectors.toList());

        assertTrue(overloads.size() == 2);
        assertNotEquals(overloads.get(0).getURI(), overloads.get(1).getURI());
        assertTrue(overloads.stream().allMatch(resource -> resource.getURI()
                .matches(".*/demo\\.Example_update_[0-9a-f]{12}_patched")));
        assertTrue(overloads.stream().anyMatch(resource -> parameterTypes(resource).equals(List.of("java.lang.String"))));
        assertTrue(overloads.stream().anyMatch(resource -> parameterTypes(resource)
                .equals(List.of("java.lang.String", "int"))));
    }

    @Test
    void onlyAddsIsPatchOfMethodForPatchedMethods() throws Exception {
        String sourcePath = "/Users/tanguyro/Incodame/paragraph/ParagraphUI/src/main/java/org/incodame/paragraph/sample/webapp/demo/Parameter.java";
        String patchPath = "/Users/tanguyro/Incodame/paragraph/set-doc-since-information.patch";

        Model model = JavaPatchToRdf.buildModelForFile(sourcePath, patchPath);

        Resource patchedMethod = model.listResourcesWithProperty(RDF.type, model.getResource("http://webofcode.orgMethod")).toList().stream()
                .filter(r -> r.hasProperty(model.createProperty("http://webofcode.orgname"), "setDoc"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing patched method resource"));
        assertTrue(patchedMethod.hasProperty(model.createProperty("https://github.com/Incodam/isPatchOfMethod")));

        Resource unpatchedMethod = model.listResourcesWithProperty(RDF.type, model.getResource("http://webofcode.orgMethod")).toList().stream()
                .filter(r -> r.hasProperty(model.createProperty("http://webofcode.orgname"), "setName"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing unpatched method resource"));
        assertTrue(!unpatchedMethod.hasProperty(model.createProperty("https://github.com/Incodam/isPatchOfMethod")));
    }

    @Test
    void onlyAddsIsNewConstructorForAddedConstructors() throws Exception {
        String sourcePath = "/Users/tanguyro/Incodame/paragraph/ParagraphUI/src/main/java/org/incodame/paragraph/sample/webapp/demo/Parameter.java";
        String patchPath = "/Users/tanguyro/Incodame/paragraph/add-parameter-name-constructor.patch";

        Model model = JavaPatchToRdf.buildModelForFile(sourcePath, patchPath);

        Resource patchedConstructor = model.listResourcesWithProperty(RDF.type, model.getResource("http://webofcode.orgConstructor")).toList().stream()
                .filter(r -> parameterTypes(r).equals(java.util.Collections.singletonList("java.lang.String")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing patched constructor resource"));
        assertTrue(patchedConstructor.hasProperty(model.createProperty("https://github.com/Incodam/isNewConstructor")));

        Resource unpatchedConstructor = model.listResourcesWithProperty(RDF.type, model.getResource("http://webofcode.orgConstructor")).toList().stream()
                .filter(r -> parameterTypes(r).equals(java.util.Arrays.asList("java.lang.String", "java.lang.String")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing unpatched constructor resource"));
        assertTrue(!unpatchedConstructor.hasProperty(model.createProperty("https://github.com/Incodam/isNewConstructor")));
        assertTrue(!unpatchedConstructor.hasProperty(model.createProperty("https://github.com/Incodam/isPatchOfConstructor")));
    }

    @Test
    void generatesAllParametersForMultilineMethodSignature() throws Exception {
        Path sourceFile = Files.createTempFile("MultilineMethodExample", ".java");
        Files.write(sourceFile, (
                "package demo;\n" +
                "\n" +
                "public class Example {\n" +
                "    public void setDoc(String doc) {\n" +
                "    }\n" +
                "}\n"
        ).getBytes());

        Path patchFile = Files.createTempFile("MultilineMethodPatch", ".patch");
        Files.write(patchFile, (
                "diff --git a/Example.java b/Example.java\n" +
                "--- a/Example.java\n" +
                "+++ b/Example.java\n" +
                "@@\n" +
                "-    public void setDoc(String doc) {\n" +
                "+    public void setDoc(String doc,\n" +
                "+                        String sinceInformation) {\n"
        ).getBytes());

        Model model = JavaPatchToRdf.buildModelForFile(sourceFile.toString(), patchFile.toString());

        Resource patchedMethod = model.listResourcesWithProperty(RDF.type, model.getResource("http://webofcode.orgMethod")).toList().stream()
                .filter(r -> r.hasProperty(model.createProperty("http://webofcode.orgname"), "setDoc"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing multiline method resource"));

        assertTrue(parameterTypes(patchedMethod).equals(java.util.Arrays.asList("java.lang.String", "java.lang.String")));
    }

    @Test
    void onlyAddsIsPatchOfConstructorForChangedConstructors() throws Exception {
        Path sourceFile = Files.createTempFile("ConstructorPatchExample", ".java");
        Files.write(sourceFile, (
                "package demo;\n" +
                "\n" +
                "public class Example {\n" +
                "    public Example(String name, int version) { }\n" +
                "    public Example(int value) { }\n" +
                "}\n"
        ).getBytes());

        Path patchFile = Files.createTempFile("ConstructorPatch", ".patch");
        Files.write(patchFile, (
                "diff --git a/Example.java b/Example.java\n" +
                "--- a/Example.java\n" +
                "+++ b/Example.java\n" +
                "@@\n" +
                "-    public Example(String name) { }\n" +
                "+    public Example(String name, int version) { }\n"
        ).getBytes());

        Model model = JavaPatchToRdf.buildModelForFile(sourceFile.toString(), patchFile.toString());
        Property isPatchOfConstructor = model.createProperty("https://github.com/Incodam/isPatchOfConstructor");
        Resource constructorType = model.getResource("http://webofcode.orgConstructor");
        Resource changedConstructor = model.listResourcesWithProperty(RDF.type, constructorType).toList().stream()
                .filter(resource -> parameterTypes(resource).equals(java.util.Arrays.asList("java.lang.String", "int")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing changed constructor resource"));
        Resource unchangedConstructor = model.listResourcesWithProperty(RDF.type, constructorType).toList().stream()
                .filter(resource -> parameterTypes(resource).equals(java.util.Collections.singletonList("int")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing unchanged constructor resource"));

        assertTrue(changedConstructor.hasProperty(isPatchOfConstructor));
        assertTrue(!unchangedConstructor.hasProperty(isPatchOfConstructor));
        assertTrue(changedConstructor.getProperty(isPatchOfConstructor).getResource().getURI().endsWith("_stable"));
    }

    @Test
    void processesEveryJavaFileInPatchAndMarksDeletedTypes() throws Exception {
        Path workspace = Files.createTempDirectory("patch-source-base");
        Path sourceBase = Files.createDirectories(workspace.resolve("src/main/java"));
        Path packageDirectory = Files.createDirectories(sourceBase.resolve("demo"));
        Files.writeString(packageDirectory.resolve("First.java"),
                "package demo; public class First { public void first() { } }");
        Files.writeString(packageDirectory.resolve("Second.java"),
                "package demo; public interface Second { void second(); }");

        Path patchFile = Files.createTempFile("multi-file-patch", ".patch");
        Files.writeString(patchFile,
                "diff --git a/src/main/java/demo/First.java b/src/main/java/demo/First.java\n" +
                "--- a/src/main/java/demo/First.java\n" +
                "+++ b/src/main/java/demo/First.java\n" +
                "@@\n" +
                "- public void first() { }\n" +
                "+ public void first() { }\n" +
                "diff --git a/src/main/java/demo/Second.java b/src/main/java/demo/Second.java\n" +
                "--- a/src/main/java/demo/Second.java\n" +
                "+++ b/src/main/java/demo/Second.java\n" +
                "@@\n" +
                "- void second();\n" +
                "+ void second();\n" +
                "diff --git a/src/main/java/demo/Removed.java b/src/main/java/demo/Removed.java\n" +
                "--- a/src/main/java/demo/Removed.java\n" +
                "+++ /dev/null\n" +
                "@@\n" +
                "- package demo;\n" +
                "- public class Removed { }\n");

        Model model = JavaPatchToRdf.buildModelForPatch(sourceBase.toString(), patchFile.toString());
        String namespace = "https://github.com/Incodam/";
        Resource first = model.getResource(namespace + "demo.First");
        Resource second = model.getResource(namespace + "demo.Second");
        Resource removed = model.getResource(namespace + "demo.Removed");
        Property isDeleted = model.createProperty(namespace + "isDeleted");

        assertTrue(first.hasProperty(RDF.type, model.getResource("http://webofcode.orgClass")));
        assertTrue(second.hasProperty(RDF.type, model.getResource("http://webofcode.orgInterface")));
        assertTrue(removed.hasProperty(RDF.type, model.getResource("http://webofcode.orgClass")));
        assertTrue(removed.hasProperty(RDF.type, model.getResource("http://www.w3.org/2002/07/owl#Class")));
        assertTrue(removed.hasProperty(isDeleted, removed));
    }
}
