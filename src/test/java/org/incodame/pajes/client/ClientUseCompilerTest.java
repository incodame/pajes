package org.incodame.pajes.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.vocabulary.RDF;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.utils.cli.CommandLineException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import spoon.Launcher;
import spoon.MavenLauncher;
import spoon.SpoonModelBuilder;
import spoon.reflect.factory.Factory;
import spoon.support.StandardEnvironment;
import spoon.support.compiler.SpoonPom;

class ClientUseCompilerTest {

        @Test
        void launchesConfiguredMavenInsteadOfProjectLocalScript(@TempDir Path tempDir) throws Exception {
                Path home = tempDir.resolve("Maven installation");
                Path bin = Files.createDirectories(home.resolve("bin"));
                boolean windows = File.separatorChar == '\\';
                Path executable = bin.resolve(windows ? "mvn.cmd" : "mvn");
                Files.writeString(executable, windows
                                ? "@echo off\r\necho Configured Maven launched\r\nexit /b 0\r\n"
                                : "#!/bin/sh\nprintf '%s\\n' 'Configured Maven launched'\nexit 0\n");
                if (!windows) {
                        assertTrue(executable.toFile().setExecutable(true));
                }
                Path project = Files.createDirectories(tempDir.resolve("client project"));
                Files.writeString(project.resolve("mvn"), "#!/bin/sh\nexit 1\n");
                Invoker invoker = ClientUseClasspath.createMavenInvoker(home);
                assertEquals(executable.toAbsolutePath().toFile(), invoker.getMavenExecutable());

                org.apache.maven.shared.invoker.InvocationRequest request =
                                new org.apache.maven.shared.invoker.DefaultInvocationRequest();
                request.setBaseDirectory(project.toFile());
                request.setGoals(Arrays.asList("--version"));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (PrintStream log = new PrintStream(output)) {
                        request.setOutputHandler(log::println);
                        request.setErrorHandler(log::println);
                        InvocationResult result = invoker.execute(request);
                        assertNull(result.getExecutionException(), output.toString());
                        assertEquals(0, result.getExitCode(), output.toString());
                }
                assertTrue(output.toString().contains("Configured Maven launched"), output.toString());
        }

        @Test
        void rejectsMissingMavenExecutable(@TempDir Path tempDir) {
                IOException failure = assertThrows(IOException.class,
                                () -> ClientUseClasspath.createMavenInvoker(tempDir));
                assertTrue(failure.getMessage().contains("Maven executable not found"));
        }

        @Test
        void resolvesFreshModuleClasspathsWithExplicitSettings(@TempDir Path tempDir) throws Exception {
                SpoonPom pom = createMultiModulePom(tempDir);
                Path settings = tempDir.resolve("settings with spaces.xml");
                Files.writeString(settings, "<settings/>");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                Path staleClasspath = tempDir.resolve("spoon.classpath-app.tmp");
                Files.writeString(staleClasspath, "stale.jar");
                Invoker invoker = mavenInvocation(0, request -> {
                        assertEquals(settings.toFile(), request.getUserSettingsFile());
                        assertEquals(pom.toFile(), request.getPomFile());
                        assertEquals(InvocationRequest.ReactorFailureBehavior.FailFast, request.getReactorFailureBehavior());
                        assertTrue(request.isRecursive());
                        assertEquals("compile", request.getProperties().getProperty("includeScope"));
                        assertEquals(Arrays.asList("dependency:build-classpath"), request.getGoals());
                            assertDoesNotThrow(() -> request.getOutputHandler(null).consumeLine("Maven stdout"));
                            assertDoesNotThrow(() -> request.getErrorHandler(null).consumeLine("Maven stderr"));
                        String outputName = request.getProperties().getProperty("mdep.outputFile");
                        assertDoesNotThrow(() -> Files.writeString(tempDir.resolve(outputName), "jena-core-4.10.0.jar"));
                        assertDoesNotThrow(() -> Files.writeString(tempDir.resolve("child").resolve(outputName),
                                        "jena-core-4.10.0.jar" + File.pathSeparator + "jena-arq-4.10.0.jar"));
                });

                try (PrintStream log = new PrintStream(output)) {
                        assertArrayEquals(new String[] { "jena-core-4.10.0.jar", "jena-arq-4.10.0.jar" },
                                        ClientUseClasspath.resolve(pom, settings, log, invoker));
                }
                assertTrue(output.toString().contains("Maven stdout"));
                assertTrue(output.toString().contains("Maven stderr"));
                assertEquals("stale.jar", Files.readString(staleClasspath));
                assertNoTemporaryClasspaths(tempDir);
        }

        @Test
        void rejectsFailedMavenResolutionDespitePartialAndCachedClasspaths(@TempDir Path tempDir) throws Exception {
                SpoonPom pom = createMultiModulePom(tempDir);
                Path staleClasspath = tempDir.resolve("child/spoon.classpath-app.tmp");
                Files.writeString(staleClasspath, "stale.jar");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                Invoker invoker = mavenInvocation(1, request -> {
                        assertNull(request.getUserSettingsFile());
                            assertDoesNotThrow(() -> request.getErrorHandler(null).consumeLine("[ERROR] Could not resolve dependency"));
                        String outputName = request.getProperties().getProperty("mdep.outputFile");
                        assertDoesNotThrow(() -> Files.writeString(tempDir.resolve(outputName), "partial.jar"));
                });

                try (PrintStream log = new PrintStream(output)) {
                        IOException failure = assertThrows(IOException.class,
                                        () -> ClientUseClasspath.resolve(pom, null, log, invoker));
                        assertTrue(failure.getMessage().contains("exit code 1"));
                        assertTrue(failure.getMessage().contains("client-use.log"));
                }
                assertTrue(output.toString().contains("Could not resolve dependency"));
                assertEquals("stale.jar", Files.readString(staleClasspath));
                assertNoTemporaryClasspaths(tempDir);
        }

        @Test
        void rejectsSuccessfulMavenInvocationWithoutFreshClasspath(@TempDir Path tempDir) throws Exception {
                SpoonPom pom = createMultiModulePom(tempDir);
                Files.writeString(tempDir.resolve("child/spoon.classpath-app.tmp"), "stale.jar");
                try (PrintStream log = new PrintStream(new ByteArrayOutputStream())) {
                        IOException failure = assertThrows(IOException.class,
                                        () -> ClientUseClasspath.resolve(pom, null, log, mavenInvocation(0, request -> { })));
                        assertTrue(failure.getMessage().contains("no classpath entries"));
                }
                assertNoTemporaryClasspaths(tempDir);
        }

        private static SpoonPom createMultiModulePom(Path directory) throws Exception {
                Files.writeString(directory.resolve("pom.xml"),
                                "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId>"
                                                + "<artifactId>parent</artifactId><version>1</version><packaging>pom</packaging>"
                                                + "<modules><module>child</module></modules></project>");
                Path child = Files.createDirectories(directory.resolve("child"));
                Files.writeString(child.resolve("pom.xml"),
                                "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId>"
                                                + "<artifactId>child</artifactId><version>1</version></project>");
                return new SpoonPom(directory.toString(), MavenLauncher.SOURCE_TYPE.APP_SOURCE, new StandardEnvironment());
        }

        private static Invoker mavenInvocation(int exitCode, Consumer<InvocationRequest> action) {
                return new DefaultInvoker() {
                        @Override
                        public InvocationResult execute(InvocationRequest request) {
                                action.accept(request);
                                return new InvocationResult() {
                                        @Override
                                        public int getExitCode() {
                                                return exitCode;
                                        }

                                        @Override
                                        public CommandLineException getExecutionException() {
                                                return null;
                                        }
                                };
                        }
                };
        }

        private static void assertNoTemporaryClasspaths(Path directory) throws IOException {
                try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
                        assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".pajes-classpath-")));
                }
        }

        @Test
        void readsMavenSettingsFromYaml(@TempDir Path tempDir) throws Exception {
                Path settings = Files.createDirectories(tempDir.resolve("maven configuration")).resolve("settings.xml");
                Files.writeString(settings, "<settings/>");
                Path configuration = tempDir.resolve("pajes-config.yaml");
                Files.writeString(configuration, "maven:\n  settings: '"
                                + settings.toString().replace('\\', '/') + "'\n");

                assertEquals(settings.toAbsolutePath(), ClientUseToRdf.resolveMavenSettings(configuration));

                Files.writeString(configuration, "maven:\n  settings: maven configuration/settings.xml\n");
                assertEquals(settings.toAbsolutePath(), ClientUseToRdf.resolveMavenSettings(configuration));
        }

        @Test
        void usesDefaultMavenSettingsWithoutConfiguration(@TempDir Path tempDir) throws Exception {
                Path configuration = tempDir.resolve("pajes-config.yaml");
                assertNull(ClientUseToRdf.resolveMavenSettings(configuration));

                Files.writeString(configuration, "maven: {}\n");
                assertNull(ClientUseToRdf.resolveMavenSettings(configuration));
        }

        @Test
        void rejectsInvalidMavenSettingsConfiguration(@TempDir Path tempDir) throws Exception {
                Path configuration = tempDir.resolve("pajes-config.yaml");
                Files.writeString(configuration, "maven:\n  settings: missing-settings.xml\n");
                IOException missingFile = assertThrows(IOException.class,
                                () -> ClientUseToRdf.resolveMavenSettings(configuration));
                assertTrue(missingFile.getMessage().contains("missing-settings.xml"));

                Files.writeString(configuration, "maven:\n  settings: []\n");
                assertThrows(IOException.class, () -> ClientUseToRdf.resolveMavenSettings(configuration));

                Files.writeString(configuration, "maven:\n  settings: ' '\n");
                assertThrows(IOException.class, () -> ClientUseToRdf.resolveMavenSettings(configuration));
        }

        @Test
        void readsConfiguredClientAnnotationsFromYaml(@TempDir Path tempDir) throws Exception {
                Path configuration = tempDir.resolve("pajes-config.yaml");
                Files.writeString(configuration,
                                "client-annotations:\n"
                                                + "  - name: EnumValue\n"
                                                + "    properties: [outputLanguages]\n"
                                                + "  - name: sample.ValueRange\n"
                                                + "    properties: [lower, upper]\n");

                assertEquals(Map.of(
                                "EnumValue", List.of("outputLanguages"),
                                "sample.ValueRange", List.of("lower", "upper")),
                                ClientUseToRdf.resolveClientAnnotations(configuration));
        }

        @Test
        void rejectsMalformedClientAnnotationConfiguration(@TempDir Path tempDir) throws Exception {
                Path configuration = tempDir.resolve("pajes-config.yaml");
                Files.writeString(configuration, "client-annotations: {}\n");
                assertThrows(IOException.class,
                                () -> ClientUseToRdf.resolveClientAnnotations(configuration));

                Files.writeString(configuration,
                                "client-annotations:\n  - name: EnumValue\n    properties: [outputLanguages, ' ']\n");
                assertThrows(IOException.class,
                                () -> ClientUseToRdf.resolveClientAnnotations(configuration));
        }

    @Test
    void associatesAnalyzedClassesWithTheProvidedBranch(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("BranchExample.java");
        Files.writeString(sourceFile,
                "package example;\n"
                        + "public class BranchExample {\n"
                        + "    public Object create() { return org.apache.jena.rdf.model.ModelFactory.createDefaultModel(); }\n"
                        + "}\n");

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
    void emitsParameterAndReturnConstraintsToSeparateModel(@TempDir Path tempDir) throws Exception {
        Path apiSource = tempDir.resolve("src/org/apache/jena/rdf/model/Api.java");
        Files.createDirectories(apiSource.getParent());
        Files.writeString(apiSource,
                "package org.apache.jena.rdf.model;\n"
                        + "import java.lang.annotation.ElementType;\n"
                        + "import java.lang.annotation.Target;\n"
                        + "@Target(ElementType.PARAMETER) @interface EnumValue { "
                        + "String[] outputLanguages() default {\"TURTLE\", \"N3\"}; }\n"
                        + "@Target(ElementType.TYPE_USE) @interface ValueRange { int lower(); int upper(); }\n"
                        + "public class Api {\n"
                        + "    public void write(@EnumValue String language) { }\n"
                        + "    public @ValueRange(lower = 1, upper = 4000000) int size() { return 1; }\n"
                        + "}\n");

        Path clientSource = tempDir.resolve("src/example/Client.java");
        Files.createDirectories(clientSource.getParent());
        Files.writeString(clientSource,
                "package example;\n"
                        + "import org.apache.jena.rdf.model.Api;\n"
                        + "public class Client {\n"
                        + "    public void use() {\n"
                        + "        Api api = new Api();\n"
                        + "        api.write(\"TURTLE\");\n"
                        + "        api.size();\n"
                        + "    }\n"
                        + "}\n");

        ClientUseProcessor processor = new ClientUseProcessor("org.apache.jena.rdf.model", "main",
                Map.of("EnumValue", List.of("outputLanguages"),
                        "ValueRange", List.of("lower", "upper")));
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.addInputResource(apiSource.toString());
        launcher.addInputResource(clientSource.toString());
        launcher.addProcessor(processor);
        launcher.run();

        String namespace = "https://github.com/Incodam/";
        org.apache.jena.rdf.model.Model constraints = processor.getConstraintModel();
        org.apache.jena.rdf.model.Resource client = constraints.getResource(namespace + "example.Client");
        Property onMethodCall = constraints.createProperty(namespace + "onMethodCall");
        Property onMethodReturn = constraints.createProperty(namespace + "onMethodReturn");
        assertTrue(client.hasProperty(constraints.createProperty(namespace + "applBranch"), "main"));
        org.apache.jena.rdf.model.Resource constraintSet = client.getProperty(onMethodCall).getResource();
        assertTrue(constraintSet.getURI().contains("_constraint_set_6"));

        Property hasConstraints = constraints.createProperty(namespace + "hasContraints");
        org.apache.jena.rdf.model.RDFList listedConstraints = constraintSet.getProperty(hasConstraints)
                .getResource().as(org.apache.jena.rdf.model.RDFList.class);
        org.apache.jena.rdf.model.Resource parameterConstraint = listedConstraints.getHead().asResource();
        assertTrue(parameterConstraint.getURI().contains("_constraint_6_1"));
        org.apache.jena.rdf.model.Resource annotation = parameterConstraint
                .getProperty(constraints.createProperty(namespace + "annotations")).getResource();
        assertEquals("EnumValue", annotation.getProperty(
                constraints.createProperty("http://www.w3.org/2000/01/rdf-schema#label")).getString());
        org.apache.jena.rdf.model.RDFList annotationProperties = annotation.getProperty(
                constraints.createProperty(namespace + "annotationProperties"))
                .getResource().as(org.apache.jena.rdf.model.RDFList.class);
        org.apache.jena.rdf.model.Resource enumProperty = annotationProperties.getHead().asResource();
        assertTrue(enumProperty.getURI().endsWith("_a_ev"));
        org.apache.jena.rdf.model.RDFList enumValues = enumProperty.getProperty(RDF.value)
                .getResource().as(org.apache.jena.rdf.model.RDFList.class);
        assertEquals(List.of("TURTLE", "N3"), enumValues.asJavaList().stream()
                .map(org.apache.jena.rdf.model.RDFNode::asLiteral)
                .map(org.apache.jena.rdf.model.Literal::getString)
                .toList());

        org.apache.jena.rdf.model.Resource returnConstraint = client.getProperty(onMethodReturn).getResource();
        assertTrue(returnConstraint.getURI().contains("_constraint_7"));
        org.apache.jena.rdf.model.Resource returnAnnotation = returnConstraint
                .getProperty(constraints.createProperty(namespace + "annotations")).getResource();
        assertEquals("ValueRange", returnAnnotation.getProperty(
                constraints.createProperty("http://www.w3.org/2000/01/rdf-schema#label")).getString());
        assertEquals(2, returnAnnotation.getProperty(
                constraints.createProperty(namespace + "annotationProperties")).getResource()
                .as(org.apache.jena.rdf.model.RDFList.class).size());
        assertFalse(processor.getModel().getResource(namespace + "example.Client")
                        .hasProperty(processor.getModel().createProperty(namespace + "onMethodCall")),
                "Constraint links should remain separate from the client-use model");
    }

    @Test
    void treatsApiWrapperAsTransparentAndUsesWrapperCallLine(@TempDir Path tempDir) throws Exception {
        Path apiSource = tempDir.resolve("src/org/apache/jena/rdf/model/Api.java");
        Files.createDirectories(apiSource.getParent());
        Files.writeString(apiSource,
                "package org.apache.jena.rdf.model;\n"
                        + "public class Api {\n"
                        + "    public void write(String value) { }\n"
                        + "}\n");

        Path clientSource = tempDir.resolve("src/example/Client.java");
        Files.createDirectories(clientSource.getParent());
        Files.writeString(clientSource,
                "package example;\n"
                        + "import org.apache.jena.rdf.model.Api;\n"
                        + "@interface ApiWrapper { }\n"
                        + "@interface EnumValue { String[] values(); }\n"
                        + "class ClientApi {\n"
                        + "    private final Api api = new Api();\n"
                        + "    @ApiWrapper\n"
                        + "    public void write(@EnumValue(values = {\"one\", \"two\"}) String value) {\n"
                        + "        api.write(value);\n"
                        + "    }\n"
                        + "}\n"
                        + "public class Client {\n"
                        + "    public void use(ClientApi client) {\n"
                        + "        client.write(\"one\");\n"
                        + "    }\n"
                        + "}\n");

        ClientUseProcessor processor = new ClientUseProcessor("org.apache.jena.rdf.model", "main",
                Map.of("EnumValue", List.of("values")));
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.addInputResource(apiSource.toString());
        launcher.addInputResource(clientSource.toString());
        launcher.addProcessor(processor);
        launcher.run();

        String namespace = "https://github.com/Incodam/";
        org.apache.jena.rdf.model.Model model = processor.getModel();
        org.apache.jena.rdf.model.Resource client = model.getResource(namespace + "example.Client");
        Property calls = model.createProperty("http://webofcode.orgcalls");
        List<String> callUris = client.listProperties(calls).toList().stream()
                .map(statement -> statement.getResource().getURI())
                .toList();
        assertEquals(1, callUris.size());
        assertTrue(callUris.get(0).startsWith(namespace + "org.apache.jena.rdf.model.Api_write_"));
        assertFalse(callUris.get(0).contains("ClientApi"),
                "The wrapper method should not replace the target API method in RDF");
        StringWriter constraintTurtle = new StringWriter();
        processor.getConstraintModel().write(constraintTurtle, "TURTLE");
        assertFalse(constraintTurtle.toString().contains("ApiWrapper"),
                "The wrapper marker should not be serialized as a constraint annotation");

        org.apache.jena.rdf.model.Model constraints = processor.getConstraintModel();
        org.apache.jena.rdf.model.Resource constraintClient = constraints.getResource(namespace + "example.Client");
        org.apache.jena.rdf.model.Resource constraintSet = constraintClient.getProperty(
                constraints.createProperty(namespace + "onMethodCall")).getResource();
        assertTrue(constraintSet.getURI().contains("_constraint_set_14"),
                "Constraint IDs should use the client-side wrapper invocation line");
        org.apache.jena.rdf.model.Resource constraint = constraintSet.getProperty(
                constraints.createProperty(namespace + "hasContraints")).getResource()
                .as(org.apache.jena.rdf.model.RDFList.class).getHead().asResource();
        assertTrue(constraint.getURI().contains("_constraint_14_1"));
    }

    @Test
    void handlesAnonymousClassesWithoutPackageMetadata(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("AnonymousExample.java");
        Files.writeString(sourceFile,
                "package example;\n"
                        + "public class AnonymousExample {\n"
                        + "    public Object create() {\n"
                        + "        return new Runnable() {\n"
                        + "            public void run() { org.apache.jena.rdf.model.ModelFactory.createDefaultModel(); }\n"
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
        assertFalse(processor.getModel().containsResource(processor.getModel()
                        .getResource("https://github.com/Incodam/example.AnonymousExample")),
                "The outer class should be omitted when only its anonymous class calls the API");
    }

    @Test
    void omitsTypesWithoutTargetApiLinks(@TempDir Path tempDir) throws Exception {
        Path apiSource = tempDir.resolve("Api.java");
        Files.writeString(apiSource,
                "package api;\n"
                        + "public class Api {\n"
                        + "    public static void ping() { }\n"
                        + "    public static class Base { }\n"
                        + "    public interface Contract { }\n"
                        + "}\n");
        Path clientSource = tempDir.resolve("Clients.java");
        Files.writeString(clientSource,
                "package example;\n"
                        + "class CallsApi { void run() { api.Api.ping(); } }\n"
                        + "class ConstructsApi { Object create() { return new api.Api(); } }\n"
                        + "class ExtendsApi extends api.Api.Base { }\n"
                        + "class ImplementsApi implements api.Api.Contract { }\n"
                        + "interface ExtendsApiContract extends api.Api.Contract { }\n"
                        + "class Unrelated { void run() { System.out.println(\"other API\"); } }\n"
                        + "interface UnrelatedContract extends java.lang.Runnable { }\n"
                        + "class Outer { static class Nested extends api.Api.Base { } }\n");
        ClientUseProcessor processor = new ClientUseProcessor("api", "1.0.1");
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.addInputResource(apiSource.toString());
        launcher.addInputResource(clientSource.toString());
        launcher.addProcessor(processor);
        launcher.run();

        org.apache.jena.rdf.model.Model model = processor.getModel();
        String namespace = "https://github.com/Incodam/";
        for (String className : Arrays.asList("Unrelated", "UnrelatedContract", "Outer")) {
            assertFalse(model.containsResource(model.getResource(namespace + "example." + className)),
                    "Unrelated type should be omitted: " + className);
        }
        for (String className : Arrays.asList("CallsApi", "ConstructsApi", "ExtendsApi", "ImplementsApi",
                "ExtendsApiContract", "Outer$Nested")) {
            org.apache.jena.rdf.model.Resource client = model.getResource(namespace + "example." + className);
            assertTrue(client.hasProperty(RDF.type), "API client should retain its type: " + className);
            assertTrue(client.hasProperty(model.createProperty(namespace + "applBranch"), "1.0.1"));
            assertTrue(client.hasProperty(model.createProperty("http://webofcode.orgcalls"))
                    || client.hasProperty(model.createProperty("http://webofcode.orgimplements"))
                    || client.hasProperty(model.createProperty("http://webofcode.orgextends")),
                    "Each emitted client type must have a target API link: " + className);
        }
        assertTrue(processor.getClientPackages().contains("example"),
                "Package metadata should remain available");
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

        assertTrue(Files.isRegularFile(diagnosticsFile), "Compiler should create the diagnostics file");
        String diagnostics = Files.readString(diagnosticsFile);
        assertFalse(diagnostics.contains("ERROR:"),
                "Generated source using Jena should compile without errors: " + diagnostics);
    }
}
