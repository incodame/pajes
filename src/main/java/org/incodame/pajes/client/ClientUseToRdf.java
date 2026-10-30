package org.incodame.pajes.client;

import spoon.MavenLauncher;

import spoon.SpoonModelBuilder;
import spoon.reflect.factory.Factory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.jena.rdf.model.Model;
import org.incodame.pajes.annotations.ApiWrapper;
import org.incodame.pajes.annotations.EnumValue;
import org.incodame.pajes.annotations.ValueRange;

public class ClientUseToRdf {
    private static final Pattern RELEASE_BRANCH = Pattern.compile("\\d+(?:\\.\\d+)*");

    public static void main(String[] args) throws IOException {
        if (args.length != 3 || args[0].trim().isEmpty() || args[1].trim().isEmpty()
                || args[2].trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Usage: ClientUseToRdf <client-module-path> <branch> <target-package>");
        }
        if (!"main".equals(args[1]) && !RELEASE_BRANCH.matcher(args[1]).matches()) {
            throw new IllegalArgumentException("Branch must be 'main' or a dotted numeric release version: " + args[1]);
        }

        PrintStream previousError = System.err;
        try (PrintStream log = new PrintStream(
                Files.newOutputStream(Paths.get("client-use.log")), true, StandardCharsets.UTF_8)) {
         
            System.setErr(log);
            try {
                reportProgress(log, "Starting client usage analysis for module " + args[0]
                        + ", branch " + args[1] + " and target package " + args[2]);
                analyze(args[0], args[1], args[2], log);
            } finally {
                System.setErr(previousError);
            }
            if (log.checkError()) {
                throw new IOException("Cannot write to client-use.log.");
            }
        }
    }

    private static void analyze(String clientModulePath, String branch, String targetPackage, PrintStream log)
            throws IOException {
        MavenLauncher launcher = new MavenLauncher(clientModulePath, MavenLauncher.SOURCE_TYPE.APP_SOURCE,
                new String[0]) {
                    @Override 
                    protected SpoonModelBuilder getCompilerInstance(Factory factory) {
                        ClientUseCompiler compiler = new ClientUseCompiler(factory, Paths.get("client-use-errors.log"));
                        return compiler;
                    }
                };
        
        Path configurationFile = Paths.get("pajes-config.yaml");
        Path settingsXmlPath = resolveMavenSettings(configurationFile);
        Map<String, List<String>> clientAnnotations = resolveClientAnnotations(configurationFile);
        if (settingsXmlPath != null) {
            log.println("Resolving maven classpath using " + settingsXmlPath);
        }

        reportProgress(log, "Starting Maven dependency resolution; output: client-use.log");
        String[] classpathEntries = ClientUseClasspath.resolve(launcher.getPomFile(), settingsXmlPath, log);
        launcher.getModelBuilder().setSourceClasspath(classpathEntries);
        log.println("Maven source classpath resolved with " + classpathEntries.length + " entries.");
        for (String entry : classpathEntries) {
            log.println("  " + entry);
        }

        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(false);

        ClientUseProcessor rdfProcessor = new ClientUseProcessor(targetPackage, branch, clientAnnotations);
        launcher.addProcessor(rdfProcessor);
        
        // Exécution de l'analyse Spoon
        reportProgress(log, "Starting Spoon analysis; compiler diagnostics: client-use-errors.log");
        launcher.run();

        String targetVersion = ClientUseRdfMetadata.resolveTargetVersion(classpathEntries, targetPackage);
        String moduleGroupId = resolveGroupId(launcher);
        Set<String> clientPackages = rdfProcessor.getClientPackages();
        if (clientPackages.isEmpty()) {
            throw new IllegalStateException("No Java packages were found in client module: " + clientModulePath);
        }
        ClientUseRdfMetadata.addDependency(rdfProcessor.getModel(), moduleGroupId, clientPackages,
                branch, targetPackage, targetVersion);
        reportProgress(log, "Resolved " + targetPackage + " dependency version " + targetVersion);
        
        // Extraction du modèle RDF finalisé
        try (FileWriter out = new FileWriter("client-use.ttl")) {
            // Écriture du modèle au format Turtle
            writeModel(out, rdfProcessor.getModel(), "TURTLE");
        }
        reportProgress(log, "RDF analysis report generated: client-use.ttl ("
                + getModelSize(rdfProcessor.getModel()) + " triples)");

        try (FileWriter out = new FileWriter("client-use-constraints.ttl")) {
            writeModel(out, rdfProcessor.getConstraintModel(), "TURTLE");
        }
        reportProgress(log, "RDF constraints report generated: client-use-constraints.ttl ("
                + getModelSize(rdfProcessor.getConstraintModel()) + " triples)");

    }

    @ApiWrapper 
    private static void writeModel(FileWriter out, Model model, 
        @EnumValue(values = {"RDF/XML", "RDF/XML-ABBREV", "N-TRIPLE", "TURTLE", "TTL", "N3"}) String format) throws IOException {
        model.write(out, format);
    }

    @ApiWrapper
    private static @ValueRange(lower = 1, upper = 4000000) long getModelSize(Model model) {
        return model.size();
    }

    static Path resolveMavenSettings(Path configurationFile) throws IOException {
        if (!Files.exists(configurationFile)) {
            return null;
        }
        JsonNode configuration = new ObjectMapper(new YAMLFactory()).readTree(configurationFile.toFile());
        JsonNode settings = configuration == null ? null : configuration.path("maven").path("settings");
        if (settings == null || settings.isMissingNode() || settings.isNull()) {
            return null;
        }
        if (!settings.isTextual() || settings.asText().trim().isEmpty()) {
            throw new IOException("maven.settings must be a non-empty path in " + configurationFile);
        }
        Path settingsPath = configurationFile.toAbsolutePath().getParent()
                .resolve(settings.asText().trim()).normalize();
        if (!Files.isRegularFile(settingsPath) || !Files.isReadable(settingsPath)) {
            throw new IOException("Maven settings file configured in " + configurationFile
                    + " is not readable: " + settingsPath);
        }
        return settingsPath;
    }

    static Map<String, List<String>> resolveClientAnnotations(Path configurationFile) throws IOException {
        if (!Files.exists(configurationFile)) {
            return Map.of();
        }
        JsonNode configuration = new ObjectMapper(new YAMLFactory()).readTree(configurationFile.toFile());
        JsonNode annotations = configuration == null ? null : configuration.get("client-annotations");
        if (annotations == null || annotations.isNull()) {
            return Map.of();
        }
        if (!annotations.isArray()) {
            throw new IOException("client-annotations must be a YAML list in " + configurationFile);
        }

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (int index = 0; index < annotations.size(); index++) {
            JsonNode annotation = annotations.get(index);
            if (!annotation.isObject() || !annotation.path("name").isTextual()
                    || annotation.path("name").asText().trim().isEmpty()
                    || !annotation.path("properties").isArray()) {
                throw new IOException("Each client-annotations entry must have a non-empty name and a properties list "
                        + "in " + configurationFile + " (entry " + index + ")");
            }

            String name = annotation.path("name").asText().trim();
            List<String> properties = new ArrayList<>();
            for (JsonNode property : annotation.path("properties")) {
                if (!property.isTextual() || property.asText().trim().isEmpty()) {
                    throw new IOException("client-annotations properties must be non-empty strings in "
                            + configurationFile + " (entry " + index + ")");
                }
                properties.add(property.asText().trim());
            }
            if (result.putIfAbsent(name, List.copyOf(properties)) != null) {
                throw new IOException("Duplicate client annotation name in " + configurationFile + ": " + name);
            }
        }
        return Map.copyOf(result);
    }

    private static String resolveGroupId(MavenLauncher launcher) {
        org.apache.maven.model.Model model = launcher.getPomFile().getModel();
        String groupId = model.getGroupId();
        if ((groupId == null || groupId.isBlank()) && model.getParent() != null) {
            groupId = model.getParent().getGroupId();
        }
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalStateException("Could not determine Maven groupId for client module");
        }
        return groupId;
    }

    private static void reportProgress(PrintStream log, String message) {
        log.println(message);
        System.out.println(message);
    }
}
