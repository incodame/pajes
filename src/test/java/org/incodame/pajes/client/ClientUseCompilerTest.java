package org.incodame.pajes.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
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
