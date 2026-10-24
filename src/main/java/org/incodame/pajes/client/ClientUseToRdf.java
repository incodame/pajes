package org.incodame.pajes.client;

import spoon.MavenLauncher;

import spoon.SpoonModelBuilder;
import spoon.reflect.factory.Factory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Set;
import java.util.regex.Pattern;

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
        
        Path settingsXmlPath = Paths.get("path/to/settings.xml");
        if (Files.isReadable(settingsXmlPath)) {    
            String absoluteSettingsPath = settingsXmlPath.toAbsolutePath().toString();
            launcher.setEnvironmentVariable("MAVEN_ARGS", "-s " + absoluteSettingsPath);
            log.println("Resolving maven classpath using " + absoluteSettingsPath);
        }

        launcher.rebuildClasspath();
        String[] classpathEntries = launcher.getEnvironment().getSourceClasspath();
        if (classpathEntries.length == 0) {
            throw new IllegalStateException("Spoon's maven source classpath is empty; resolution did not produce any entries.");
        }
        log.println("Spoon's maven source classpath resolved with " + classpathEntries.length + " entries.");
        for (String entry : classpathEntries) {
            log.println("  " + entry);
        }

        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(false);

        // Instanciation du processeur RDF
        ClientUseProcessor rdfProcessor = new ClientUseProcessor(targetPackage, branch);
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
            rdfProcessor.getModel().write(out, "TURTLE");
        }
        reportProgress(log, "RDF analysis report generated: client-use.ttl ("
                + rdfProcessor.getModel().size() + " triples)");

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
