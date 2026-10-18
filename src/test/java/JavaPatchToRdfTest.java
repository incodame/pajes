import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPatchToRdfTest {

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
    void onlyAddsIsPatchOfMethodForPatchedMethods() throws Exception {
        String sourcePath = "/Users/tanguyro/Incodame/paragraph/ParagraphUI/src/main/java/org/incodame/paragraph/sample/webapp/demo/Parameter.java";
        String patchPath = "/Users/tanguyro/Incodame/paragraph/set-doc-since-information.patch";

        Model model = JavaPatchToRdf.buildModelForFile(sourcePath, patchPath);

        Resource patchedMethod = model.listResourcesWithProperty(RDF.type, model.getResource("http://webofcode.orgMethod")).toList().stream()
                .filter(r -> r.getURI().contains("Parameter_setDoc_patched"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing patched method resource"));
        assertTrue(patchedMethod.hasProperty(model.createProperty("https://github.com/Incodam/isPatchOfMethod")));

        Resource unpatchedMethod = model.listResourcesWithProperty(RDF.type, model.getResource("http://webofcode.orgMethod")).toList().stream()
                .filter(r -> r.getURI().contains("Parameter_setName_patched"))
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
                                .filter(r -> r.getURI().contains("Parameter_java_lang_String_constructor"))
                                .findFirst()
                                .orElseThrow(() -> new AssertionError("Missing patched constructor resource"));
                assertTrue(patchedConstructor.hasProperty(model.createProperty("https://github.com/Incodam/isPatchOfConstructor")));

                Resource unpatchedConstructor = model.listResourcesWithProperty(RDF.type, model.getResource("http://webofcode.orgConstructor")).toList().stream()
                                .filter(r -> r.getURI().contains("Parameter_java_lang_String_java_lang_String_constructor"))
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
                .filter(r -> r.getURI().contains("Example_setDoc_patched"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing multiline method resource"));

        assertTrue(patchedMethod.listProperties(model.createProperty("http://webofcode.orghasParameter")).toList().size() == 2);
        Resource sinceInformationParameter = model.getResource(patchedMethod.getURI() + "_param_1");
        assertTrue(sinceInformationParameter.getProperty(model.createProperty("http://webofcode.orgparameterType"))
                .getString().equals("java.lang.String"));
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
                .filter(resource -> resource.getURI().contains("java_lang_String_int_constructor"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing changed constructor resource"));
        Resource unchangedConstructor = model.listResourcesWithProperty(RDF.type, constructorType).toList().stream()
                .filter(resource -> resource.getURI().contains("_int_constructor"))
                .filter(resource -> !resource.getURI().contains("java_lang_String_int_constructor"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing unchanged constructor resource"));

        assertTrue(changedConstructor.hasProperty(isPatchOfConstructor));
        assertTrue(!unchangedConstructor.hasProperty(isPatchOfConstructor));
        assertTrue(changedConstructor.getProperty(isPatchOfConstructor).getResource().getURI()
                .equals(changedConstructor.getURI() + "_stable"));
    }
}
