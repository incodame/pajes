package org.incodame.pajes.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import spoon.Launcher;
import spoon.SpoonModelBuilder;
import spoon.compiler.Environment;
import spoon.reflect.factory.Factory;

class ClientUseCompilerTest {

    @Test
    void associatesAnalyzedClassesWithTheProvidedBranch(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("BranchExample.java");
        Files.writeString(sourceFile,
                "package example;\n"
                        + "public class BranchExample { }\n");

        ClientUseProcessor processor = new ClientUseProcessor("org.apache.jena.rdf.model", "main");
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.addInputResource(sourceFile.toString());
        launcher.addProcessor(processor);
        launcher.run();

        String namespace = "https://github.com/Incodam/";
        org.apache.jena.rdf.model.Resource branchClass = processor.getModel()
                .getResource(namespace + "example.BranchExample");
        assertTrue(branchClass.hasProperty(
                        processor.getModel().createProperty(namespace + "applBranch"), "main"),
                "Generated client class should retain the selected branch");
        assertTrue(processor.getClientPackages().contains("example"),
                "Processor should report analyzed source packages");

        StringWriter turtle = new StringWriter();
        processor.getModel().write(turtle, "TURTLE");
        assertTrue(turtle.toString().contains("owl:Class"),
                "Turtle output should use the owl prefix");
        assertFalse(turtle.toString().contains("<http://www.w3.org/2002/07/owl#Class>"),
                "Turtle output should not repeat the full OWL class URI");
    }

    @Test
    void recordsTargetApiCallsMadeByClientInterfaceDefaultMethods(@TempDir Path tempDir) throws Exception {
        Path apiSource = tempDir.resolve("src/org/apache/jena/rdf/model/Api.java");
        Files.createDirectories(apiSource.getParent());
        Files.writeString(apiSource,
                "package org.apache.jena.rdf.model;\n"
                        + "public class Api {\n"
                        + "    public static void ping() { }\n"
                        + "}\n");

        Path clientSource = tempDir.resolve("src/example/ClientContract.java");
        Files.createDirectories(clientSource.getParent());
        Files.writeString(clientSource,
                "package example;\n"
                        + "import org.apache.jena.rdf.model.Api;\n"
                        + "public interface ClientContract {\n"
                        + "    default void invokeApi() { Api.ping(); }\n"
                        + "}\n");

        ClientUseProcessor processor = new ClientUseProcessor("org.apache.jena.rdf.model", "main");
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.addInputResource(apiSource.toString());
        launcher.addInputResource(clientSource.toString());
        launcher.addProcessor(processor);
        launcher.run();

        String namespace = "https://github.com/Incodam/";
        org.apache.jena.rdf.model.Model model = processor.getModel();
        org.apache.jena.rdf.model.Resource clientInterface = model.getResource(namespace + "example.ClientContract");
        assertTrue(clientInterface.hasProperty(RDF.type, model.getResource("http://webofcode.orgInterface")));
        assertTrue(clientInterface.hasProperty(
                        model.createProperty(namespace + "applBranch"), "main"));

        Property calls = model.createProperty("http://webofcode.orgcalls");
        assertTrue(clientInterface.listProperties(calls).toList().stream()
                        .map(statement -> statement.getResource().getURI())
                        .anyMatch(uri -> uri.startsWith(namespace + "org.apache.jena.rdf.model.Api_ping_")
                                && uri.endsWith("_stable")),
                "A call from a default interface method should be attached to its interface component");
        assertTrue(processor.getClientPackages().contains("example"),
                "Client interface packages should be included in processor package results");
    }

    @Test
    void handlesAnonymousClassesWithoutPackageMetadata(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("AnonymousExample.java");
        Files.writeString(sourceFile,
                "package example;\n"
                        + "public class AnonymousExample {\n"
                        + "    public Object create() {\n"
                        + "        return new Runnable() {\n"
                        + "            public void run() { }\n"
                        + "        };\n"
                        + "    }\n"
                        + "}\n");

        ClientUseProcessor processor = new ClientUseProcessor("org.apache.jena.rdf.model", "main");
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.addInputResource(sourceFile.toString());
        launcher.addProcessor(processor);
        launcher.run();

        assertTrue(processor.getClientPackages().contains("example"),
                "Named classes should still contribute their package");
        assertTrue(processor.getModel().size() > 0,
                "Anonymous classes should be processed without aborting RDF generation");
    }

    @Test
    void compilesGeneratedSourceUsingJenaApiAndWritesDiagnostics(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("JenaClientExample.java");
        Files.writeString(sourceFile,
                "package example;\n"
                        + "import org.apache.jena.rdf.model.Model;\n"
                        + "import org.apache.jena.rdf.model.ModelFactory;\n"
                        + "public class JenaClientExample {\n"
                        + "    public Model createModel() {\n"
                        + "        Model model = ModelFactory.createDefaultModel();\n"
                        + "        model.createResource(\"https://example.org/subject\");\n"
                        + "        return model;\n"
                        + "    }\n"
                        + "}\n");

        Path diagnosticsFile = tempDir.resolve("client-use-errors.log");
        String[] sourceClasspath = Arrays.stream(System.getProperty("java.class.path")
                        .split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .filter(entry -> !entry.isEmpty())
                .toArray(String[]::new);
        assertTrue(sourceClasspath.length > 0, "Test runtime classpath should be available");

        class TestLauncher extends Launcher {
            private ClientUseCompiler compiler;

            @Override
            protected SpoonModelBuilder getCompilerInstance(Factory factory) {
                compiler = new ClientUseCompiler(factory, diagnosticsFile);
                compiler.setSourceClasspath(sourceClasspath);
                return compiler;
            }
        }

        TestLauncher launcher = new TestLauncher();
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(false);
        launcher.addInputResource(sourceFile.toString());
        launcher.buildModel();

        assertTrue(launcher.compiler != null, "Spoon should create the diagnostic compiler");
        Environment environment = launcher.getEnvironment();
        launcher.compiler.reportProblem(environment);

        assertTrue(Files.isRegularFile(diagnosticsFile), "Compiler should create the diagnostics file");
        String diagnostics = Files.readString(diagnosticsFile);
        assertFalse(diagnostics.contains("ERROR:"),
                "Generated source using Jena should compile without errors: " + diagnostics);
    }
}
