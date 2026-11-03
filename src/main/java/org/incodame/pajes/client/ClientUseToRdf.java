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
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFErrorHandler;
import org.apache.jena.rdf.model.RDFWriterI;
import org.incodame.pajes.annotations.ApiWrapper;
import org.incodame.pajes.annotations.ApiImplementation;
import org.incodame.pajes.annotations.EnumValue;
import org.incodame.pajes.annotations.ValueRange;
import org.incodame.pajes.annotations.ApiExpectedFormat;
import org.incodame.pajes.outputApi.RdfGenerator;

public class ClientUseToRdf implements RDFWriterI, RdfGenerator {
    private static final Pattern RELEASE_BRANCH = Pattern.compile("\\d+(?:\\.\\d+)*");
    private final Map<String, Object> writerProperties = new HashMap<>();
    private RDFErrorHandler errorHandler;

    @ApiImplementation
    @Override
    public void write(Model model, Writer out, @EnumValue(values = {
            "RDF/XML", "RDF/XML-ABBREV", "N-TRIPLE", "TURTLE", "TTL", "N3" }) String lang) {
        writeWithConfiguredWriter(model, out, lang);
    }

    @ApiImplementation
    @Override
    public void write(Model model, OutputStream out, @EnumValue(values = {
            "RDF/XML", "RDF/XML-ABBREV", "N-TRIPLE", "TURTLE", "TTL", "N3" }) String lang) {
        writeWithConfiguredWriter(model, out, lang);
    }

    private void writeWithConfiguredWriter(Model model, Writer out, String lang) {
        RDFWriterI writer = model.getWriter(lang);
        configureWriter(writer);
        writer.write(model, out, lang);
    }

    private void writeWithConfiguredWriter(Model model, OutputStream out, String lang) {
        RDFWriterI writer = model.getWriter(lang);
        configureWriter(writer);
        writer.write(model, out, lang);
    }

    private void configureWriter(RDFWriterI writer) {
        writerProperties.forEach(writer::setProperty);
        writer.setErrorHandler(errorHandler);
    }

    @ApiImplementation
    @Override
    public int generateRdf(Path outputFilePath, Model model, @ApiExpectedFormat(format = "aggregate('@', timestamp)") String timestamp) throws IOException {
        try (Writer out = Files.newBufferedWriter(outputFilePath, StandardCharsets.UTF_8)) {
            writeModel(out, model, "TURTLE");
        }
        return Math.toIntExact(model.size());
    }

    @ApiImplementation
    @Override
    public Object setProperty(String propName, Object propValue) {
        return writerProperties.put(propName, propValue);
    }

    @ApiImplementation
    @Override
    public RDFErrorHandler setErrorHandler(RDFErrorHandler errHandler) {
        RDFErrorHandler previousHandler = errorHandler;
        errorHandler = errHandler;
        return previousHandler;
    }

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
        ClientUseConfiguration clientUseConfiguration = resolveClientUseConfiguration(configurationFile);
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

        ClientUseProcessor rdfProcessor = new ClientUseProcessor(targetPackage, branch,
                clientUseConfiguration.clientAnnotations(), clientUseConfiguration.semanticProperties(),
                clientUseConfiguration.semanticPrefixes(), clientUseConfiguration.semanticTerms());
        launcher.addProcessor(rdfProcessor);
        
        // Exécution de l'analyse Spoon
        reportProgress(log, "Starting Spoon analysis; compiler diagnostics: client-use-errors.log");
        launcher.run();

        String targetVersion = ClientUseRdfMetadata.resolveTargetVersion(
                classpathEntries, targetPackage, launcher.getPomFile());
        String moduleGroupId = resolveGroupId(launcher);
        boolean targetIsProjectPackage = ClientUseRdfMetadata.isProjectPackage(launcher.getPomFile(), targetPackage);
        Set<String> clientPackages = rdfProcessor.getClientPackages();
        Set<String> targetClientPackages = rdfProcessor.getTargetClientPackages();
        if (clientPackages.isEmpty()) {
            throw new IllegalStateException("No Java packages were found in client module: " + clientModulePath);
        }
        ClientUseRdfMetadata.addDependency(rdfProcessor.getModel(), moduleGroupId, clientPackages,
                targetClientPackages, branch, targetPackage, targetVersion);
        if (targetIsProjectPackage) {
            reportProgress(log, "Resolved " + targetPackage + " as a package in the client project");
        } else {
            reportProgress(log, "Resolved " + targetPackage + " dependency version " + targetVersion);
        }
        
        // Check of the generated RDF model for consistency and completeness
        ClientUseRdfMetadata.checkModel(rdfProcessor.getModel(), log);

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
    private static void writeModel(Writer out, Model model, 
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
        return resolveClientUseConfiguration(configurationFile).clientAnnotations();
    }

    static ClientUseConfiguration resolveClientUseConfiguration(Path configurationFile) throws IOException {
        if (!Files.exists(configurationFile)) {
            return new ClientUseConfiguration(Map.of(), Map.of(), Map.of(), Map.of());
        }
        JsonNode configuration = new ObjectMapper(new YAMLFactory()).readTree(configurationFile.toFile());
        JsonNode annotations = configuration == null ? null : configuration.get("client-annotations");
        if (annotations == null || annotations.isNull()) {
            annotations = new ObjectMapper().createArrayNode();
        }
        if (!annotations.isArray()) {
            throw new IOException("client-annotations must be a YAML list in " + configurationFile);
        }

        Map<String, List<String>> result = new LinkedHashMap<>();
        Map<String, Set<String>> semanticProperties = new LinkedHashMap<>();
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
            JsonNode configuredSemanticProperties = annotation.get("semantic-properties");
            if (configuredSemanticProperties != null && !configuredSemanticProperties.isNull()) {
                if (!configuredSemanticProperties.isArray()) {
                    throw new IOException("semantic-properties must be a YAML list in " + configurationFile
                            + " (entry " + index + ")");
                }
                Set<String> selectedProperties = new LinkedHashSet<>();
                for (JsonNode property : configuredSemanticProperties) {
                    if (!property.isTextual() || property.asText().trim().isEmpty()
                            || !properties.contains(property.asText().trim())) {
                        throw new IOException("semantic-properties must list configured annotation properties in "
                                + configurationFile + " (entry " + index + ")");
                    }
                    selectedProperties.add(property.asText().trim());
                }
                if (!selectedProperties.isEmpty()) {
                    semanticProperties.put(name, Collections.unmodifiableSet(selectedProperties));
                }
            }
        }

        JsonNode semanticMappings = configuration == null ? null : configuration.get("semantic-mappings");
        Map<String, String> semanticPrefixes = readStringMap(
                semanticMappings == null ? null : semanticMappings.get("prefixes"),
                "semantic-mappings.prefixes", configurationFile);
        Map<String, String> semanticTerms = readStringMap(
                semanticMappings == null ? null : semanticMappings.get("terms"),
                "semantic-mappings.terms", configurationFile);
        for (Map.Entry<String, String> term : semanticTerms.entrySet()) {
            int separator = term.getValue().indexOf(':');
            if (separator <= 0 || separator == term.getValue().length() - 1
                    || !semanticPrefixes.containsKey(term.getValue().substring(0, separator))) {
                throw new IOException("Semantic mapping for term " + term.getKey()
                        + " must use a configured prefix in " + configurationFile);
            }
        }
        return new ClientUseConfiguration(
                Collections.unmodifiableMap(result),
                Collections.unmodifiableMap(semanticProperties),
                Collections.unmodifiableMap(semanticPrefixes),
                Collections.unmodifiableMap(semanticTerms));
    }

    private static Map<String, String> readStringMap(JsonNode node, String path, Path configurationFile)
            throws IOException {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IOException(path + " must be a YAML mapping in " + configurationFile);
        }
        Map<String, String> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getKey().trim().isEmpty() || !entry.getValue().isTextual()
                    || entry.getValue().asText().trim().isEmpty()) {
                throw new IOException(path + " keys and values must be non-empty strings in "
                        + configurationFile);
            }
            result.put(entry.getKey().trim(), entry.getValue().asText().trim());
        }
        return result;
    }

    static final class ClientUseConfiguration {
        private final Map<String, List<String>> clientAnnotations;
        private final Map<String, Set<String>> semanticProperties;
        private final Map<String, String> semanticPrefixes;
        private final Map<String, String> semanticTerms;

        ClientUseConfiguration(Map<String, List<String>> clientAnnotations,
                Map<String, Set<String>> semanticProperties, Map<String, String> semanticPrefixes,
                Map<String, String> semanticTerms) {
            this.clientAnnotations = clientAnnotations;
            this.semanticProperties = semanticProperties;
            this.semanticPrefixes = semanticPrefixes;
            this.semanticTerms = semanticTerms;
        }

        Map<String, List<String>> clientAnnotations() {
            return clientAnnotations;
        }

        Map<String, Set<String>> semanticProperties() {
            return semanticProperties;
        }

        Map<String, String> semanticPrefixes() {
            return semanticPrefixes;
        }

        Map<String, String> semanticTerms() {
            return semanticTerms;
        }
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
