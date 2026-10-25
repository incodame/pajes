package org.incodame.pajes.apiref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaLibToRdfTest {

    private static boolean hasNonEmptyParameterList(Resource member) {
        Property parameters = member.getModel().createProperty("http://webofcode.orgparameters");
        return member.hasProperty(parameters)
                && !member.getProperty(parameters).getResource().as(RDFList.class).isEmpty();
    }

    private static final Path SAMPLE_JAR = Paths.get(
            "/Users/tanguyro/Incodame/paragraph/ParagraphUI/target/paragraph-ui-0.0.1-SNAPSHOT-classes.jar");

    @Test
    void rejectsIncorrectArgumentCount() {
        assertThrows(IllegalArgumentException.class, () -> JavaLibToRdf.main(new String[0]));
        assertThrows(IllegalArgumentException.class,
                () -> JavaLibToRdf.main(new String[] { SAMPLE_JAR.toString() }));
        assertThrows(IllegalArgumentException.class,
                () -> JavaLibToRdf.main(new String[] { SAMPLE_JAR.toString(), "1.2.3", "extra" }));
    }

    @Test
    void generatesRdfRecordsFromSampleJar(@TempDir Path tempDir) throws Exception {
        assertTrue(Files.isReadable(SAMPLE_JAR), "Sample JAR must be readable: " + SAMPLE_JAR);

        String version = "1.2.3";
        Path outputFile = tempDir.resolve("refmodule-" + version + ".ttl");
        String previousOutputFile = System.getProperty(CodeOntologyReporter.OUTPUT_FILE_PROPERTY);
        String previousOutputDirectory = System.getProperty(CodeOntologyReporter.OUTPUT_DIRECTORY_PROPERTY);
        System.clearProperty(CodeOntologyReporter.OUTPUT_FILE_PROPERTY);
        System.setProperty(CodeOntologyReporter.OUTPUT_DIRECTORY_PROPERTY, tempDir.toString());

        try {
            JavaLibToRdf.main(new String[] { SAMPLE_JAR.toString(), version });

            assertTrue(Files.isRegularFile(outputFile),
                    "Reporter should include the version in its Turtle output filename");
            try (InputStream input = Files.newInputStream(outputFile)) {
                Model model = ModelFactory.createDefaultModel();
                model.read(input, outputFile.toUri().toString(), "TURTLE");
                assertTrue(model.size() > 0, "Reporter should generate at least one RDF record");

                String namespace = "https://github.com/Incodam/";
                Resource methodType = model.getResource("http://webofcode.orgMethod");
                Resource constructorType = model.getResource("http://webofcode.orgConstructor");
                Resource classType = model.getResource("http://webofcode.orgClass");
                Resource fieldType = model.getResource("http://webofcode.orgField");
                java.util.List<Resource> methods = model.listResourcesWithProperty(RDF.type, methodType).toList();
                java.util.List<Resource> constructors = model.listResourcesWithProperty(RDF.type, constructorType).toList();
                java.util.List<Resource> classes = model.listResourcesWithProperty(RDF.type, classType).toList();
                java.util.List<Resource> fields = model.listResourcesWithProperty(RDF.type, fieldType).toList();

                assertTrue(!methods.isEmpty(), "Reporter should generate method records");
                assertTrue(!constructors.isEmpty(), "Reporter should generate constructor records");
                assertFalse(classes.isEmpty(), "Reporter should generate class records");
                assertFalse(fields.isEmpty(), "Reporter should generate field records");
                Property applicableFor = model.createProperty(namespace + "applFor");
                assertVersion(classes, applicableFor, version, "class");
                assertVersion(fields, applicableFor, version, "field");
                assertVersion(constructors, applicableFor, version, "constructor");
                assertVersion(methods, applicableFor, version, "method");

                Property hasReturnType = model.createProperty("http://webofcode.orghasReturnType");
                Resource owlClass = model.getResource("http://www.w3.org/2002/07/owl#Class");
                Property simpleName = model.createProperty("http://webofcode.orghasSimpleName");
                assertTrue(methods.stream().allMatch(method -> method.hasProperty(hasReturnType)
                                && method.getProperty(hasReturnType).getObject().isResource()),
                        "Method return types should be resource-valued hasReturnType properties");
                Resource getter = methods.stream()
                        .filter(method -> method.hasProperty(simpleName, "getName"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Sample JAR should include getName"));
                Resource getterReturnType = getter.getProperty(hasReturnType).getResource();
                assertEquals(namespace + "java.lang.String", getterReturnType.getURI());
                assertTrue(getterReturnType.hasProperty(RDF.type, owlClass),
                        "Return type resources should be declared as OWL classes");

                assertTrue(methods.stream().anyMatch(JavaLibToRdfTest::hasNonEmptyParameterList),
                        "Method records should contain parameter lists");
                assertTrue(constructors.stream().anyMatch(JavaLibToRdfTest::hasNonEmptyParameterList),
                        "Constructor records should contain parameter lists");

                Property parameters = model.createProperty("http://webofcode.orgparameters");
                Property parameterType = model.createProperty("http://webofcode.orgparameterType");
                Property parameterIndex = model.createProperty("http://webofcode.orgparameterIndex");
                List<Resource> members = new java.util.ArrayList<>(methods);
                members.addAll(constructors);
                List<Resource> parameterResources = members.stream()
                        .filter(member -> member.hasProperty(parameters))
                        .flatMap(member -> member.getProperty(parameters).getResource().as(RDFList.class)
                                .asJavaList().stream())
                        .map(node -> node.asResource())
                        .collect(Collectors.toList());
                assertTrue(!parameterResources.isEmpty(), "Parameter records should be generated");
                assertTrue(parameterResources.stream().allMatch(parameter -> parameter.hasProperty(RDF.type,
                                model.getResource("http://webofcode.orgParameter"))
                                && parameter.hasProperty(parameterIndex)
                                && parameter.hasProperty(parameterType)),
                        "Each parameter record should include its type and index");

                assertTrue(methods.stream().allMatch(resource -> resource.getURI()
                                .matches(java.util.regex.Pattern.quote(namespace) + ".+_[0-9a-f]{12}_stable")),
                        "Method URIs should use the stable signature-hash format");
                assertTrue(constructors.stream().allMatch(resource -> resource.getURI()
                                .matches(java.util.regex.Pattern.quote(namespace) + ".+_constructor_[0-9a-f]{12}_stable")),
                        "Constructor URIs should use the stable signature-hash format");

                Resource servletInitializer = model.getResource(
                        namespace + "org.incodame.paragraph.sample.webapp.demo.ServletInitializer");
                Resource springBootServletInitializer = model.getResource(
                        namespace + "org.springframework.boot.web.servlet.support.SpringBootServletInitializer");
                Property subClassOf = model.createProperty("http://www.w3.org/2000/01/rdf-schema#subClassOf");
                assertTrue(servletInitializer.hasProperty(RDF.type, model.getResource("http://webofcode.orgClass")),
                        "Generated class should retain its Web of Code class type");
                assertTrue(servletInitializer.hasProperty(RDF.type, owlClass),
                        "Generated class should also be declared as an OWL class");
                assertTrue(springBootServletInitializer.hasProperty(RDF.type, owlClass),
                        "Referenced parent should be declared as an OWL class");
                assertTrue(servletInitializer.hasProperty(subClassOf, springBootServletInitializer),
                        "Generated class should link to its direct superclass");
            }
        } finally {
            if (previousOutputFile == null) {
                System.clearProperty(CodeOntologyReporter.OUTPUT_FILE_PROPERTY);
            } else {
                System.setProperty(CodeOntologyReporter.OUTPUT_FILE_PROPERTY, previousOutputFile);
            }
            if (previousOutputDirectory == null) {
                System.clearProperty(CodeOntologyReporter.OUTPUT_DIRECTORY_PROPERTY);
            } else {
                System.setProperty(CodeOntologyReporter.OUTPUT_DIRECTORY_PROPERTY, previousOutputDirectory);
            }
        }
    }

    private static void assertVersion(List<Resource> resources, Property applicableFor,
            String version, String recordType) {
        assertFalse(resources.isEmpty(), "Reporter should generate " + recordType + " records");
        assertTrue(resources.stream().allMatch(resource -> resource.hasProperty(applicableFor)
                        && version.equals(resource.getProperty(applicableFor).getString())),
                "Every " + recordType + " record should include applFor " + version);
    }
}
