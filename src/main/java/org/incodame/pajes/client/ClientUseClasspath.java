package org.incodame.pajes.client;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;

import spoon.support.compiler.SpoonPom;

final class ClientUseClasspath {

    private ClientUseClasspath() {
    }

    static String[] resolve(SpoonPom pom, Path settings, PrintStream log) throws IOException {
        Invoker invoker = createMavenInvoker(new File(SpoonPom.guessMavenHome()).toPath());
        log.println("Using Maven executable " + invoker.getMavenExecutable());
        return resolve(pom, settings, log, invoker);
    }

    static Invoker createMavenInvoker(Path mavenHome) throws IOException {
        Path home = mavenHome.toAbsolutePath().normalize();
        String executableName = File.separatorChar == '\\' ? "mvn.cmd" : "mvn";
        Path executable = home.resolve("bin").resolve(executableName);
        if (!Files.isRegularFile(executable)) {
            throw new IOException("Maven executable not found: " + executable);
        }
        Invoker invoker = new DefaultInvoker();
        invoker.setMavenHome(home.toFile());
        invoker.setMavenExecutable(executable.toFile());
        return invoker;
    }

    static String[] resolve(SpoonPom pom, Path settings, PrintStream log, Invoker invoker) throws IOException {
        String outputName = ".pajes-classpath-" + UUID.randomUUID() + ".tmp";
        List<Path> outputFiles = new ArrayList<>();
        collectOutputFiles(pom, outputName, outputFiles);

        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(pom.toFile());
        request.setBaseDirectory(pom.toFile().getParentFile());
        request.setBatchMode(true);
        request.setRecursive(true);
        request.setReactorFailureBehavior(InvocationRequest.ReactorFailureBehavior.FailFast);
        request.setGoals(Collections.singletonList("dependency:build-classpath"));
        request.setOutputHandler(log::println);
        request.setErrorHandler(log::println);
        if (settings != null) {
            request.setUserSettingsFile(settings.toFile());
        }
        Properties properties = new Properties();
        properties.setProperty("includeScope", "compile");
        properties.setProperty("mdep.outputFile", outputName);
        request.setProperties(properties);

        log.println("Resolving Maven compile classpath for " + pom.toFile());
        try {
            InvocationResult result = invoker.execute(request);
            if (result.getExecutionException() != null) {
                throw new IOException("Could not execute Maven dependency resolution. See client-use.log.",
                        result.getExecutionException());
            }
            if (result.getExitCode() != 0) {
                throw new IOException("Maven dependency resolution failed with exit code " + result.getExitCode()
                        + ". See client-use.log. Analysis stopped; no cached classpath was used.");
            }
            if (log.checkError()) {
                throw new IOException("Cannot write Maven dependency resolution output to client-use.log.");
            }
            Set<String> classpath = new LinkedHashSet<>();
            for (Path outputFile : outputFiles) {
                if (Files.isRegularFile(outputFile)) {
                    String contents = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8).trim();
                    if (!contents.isEmpty()) {
                        Collections.addAll(classpath, contents.split(Pattern.quote(File.pathSeparator)));
                    }
                }
            }
            if (classpath.isEmpty()) {
                throw new IOException("Maven dependency resolution produced no classpath entries. See client-use.log.");
            }
            return classpath.toArray(new String[0]);
        } catch (MavenInvocationException exception) {
            throw new IOException("Could not execute Maven dependency resolution. See client-use.log.", exception);
        } finally {
            for (Path outputFile : outputFiles) {
                try {
                    Files.deleteIfExists(outputFile);
                } catch (IOException exception) {
                    log.println("Cannot remove temporary Maven classpath file " + outputFile + ": "
                            + exception.getMessage());
                }
            }
        }
    }

    private static void collectOutputFiles(SpoonPom pom, String outputName, List<Path> outputFiles) {
        outputFiles.add(pom.toFile().toPath().getParent().resolve(outputName));
        for (SpoonPom module : pom.getModules()) {
            collectOutputFiles(module, outputName, outputFiles);
        }
    }
}