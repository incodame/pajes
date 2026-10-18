import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

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
        assertTrue(model.listResourcesWithProperty(RDF.type, constructorType).toSet().size() >= 2);

        Resource constructorResource = model.listResourcesWithProperty(RDF.type, constructorType).nextResource();
        assertTrue(constructorResource.hasProperty(RDF.type, constructorType));
        assertTrue(!constructorResource.getURI().contains("<"));
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

        Resource methodResource = model.listResourcesWithProperty(RDF.type, methodType).nextResource();
        assertTrue(methodResource.hasProperty(RDF.type, methodType));
        assertTrue(methodResource.getURI().startsWith("https://github.com/Incodam/"));
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
    void onlyAddsIsPatchOfConstructorForPatchedConstructors() throws Exception {
        String sourcePath = "/Users/tanguyro/Incodame/paragraph/ParagraphUI/src/main/java/org/incodame/paragraph/sample/webapp/demo/Parameter.java";
        String patchPath = "/Users/tanguyro/Incodame/paragraph/add-parameter-name-constructor.patch";

        Model model = JavaPatchToRdf.buildModelForFile(sourcePath, patchPath);

        Resource patchedConstructor = model.listResourcesWithProperty(RDF.type, model.getResource("http://webofcode.orgConstructor")).toList().stream()
                .filter(r -> parameterTypes(r).equals(java.util.Collections.singletonList("java.lang.String")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing patched constructor resource"));
        assertTrue(patchedConstructor.hasProperty(model.createProperty("https://github.com/Incodam/isPatchOfConstructor")));

        Resource unpatchedConstructor = model.listResourcesWithProperty(RDF.type, model.getResource("http://webofcode.orgConstructor")).toList().stream()
                .filter(r -> parameterTypes(r).equals(java.util.Arrays.asList("java.lang.String", "java.lang.String")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing unpatched constructor resource"));
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
}
