import org.apache.jena.rdf.model.Model;
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
}
