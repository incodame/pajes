package org.incodame.pajes.client;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jdt.core.compiler.CategorizedProblem;

import spoon.compiler.Environment;
import spoon.reflect.factory.Factory;
import spoon.support.compiler.jdt.JDTBasedSpoonCompiler;

public class ClientUseCompiler extends JDTBasedSpoonCompiler {

    private final Path errorLog;

    public ClientUseCompiler(Factory factory, Path errorLog) {
        super(factory);
        this.errorLog = errorLog;
    }
     
    @Override
    public void reportProblems(Environment environment) {
        int errors = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(errorLog, StandardCharsets.UTF_8)) {
            for (CategorizedProblem problem : getProblems()) {
                if (problem == null) {
                    continue;
                }
                String severity = problem.isError() ? "ERROR" : "WARNING";
                String message = String.format("%s: %s at %s:%d", severity, problem.getMessage(),
                        String.valueOf(problem.getOriginatingFileName()), problem.getSourceLineNumber());
                writer.write(message);
                writer.newLine();
                if (problem.isError()) {
                    errors++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write client analysis diagnostics: " + errorLog, e);
        }
        if (errors > 0) {
            String message = String.format("Client analysis continuing with %d compiler error(s). RDF results may be incomplete. See diagnostics: %s", errors, errorLog.toAbsolutePath());
            System.out.println(message);
            System.err.println(message);
        }
    }
}
