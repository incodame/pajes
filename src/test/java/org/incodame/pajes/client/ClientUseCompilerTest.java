package org.incodame.pajes.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

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
