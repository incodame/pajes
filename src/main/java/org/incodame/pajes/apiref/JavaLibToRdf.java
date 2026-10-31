package org.incodame.pajes.apiref;
import java.io.File;
import java.io.IOException;

import org.revapi.API;
import org.revapi.AnalysisContext;
import org.revapi.AnalysisResult;
import org.revapi.Revapi;
import org.revapi.base.FileArchive;

public class JavaLibToRdf {

    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: JavaLibToRdf <jar-file> <version> [<sources-javadoc.jar>]");
        }

        String version = args[1].trim();
        if (!version.matches("\\d+(\\.\\d+)*")) {
            throw new IllegalArgumentException("Invalid version format: " + version);
        }
        
        File jarFile = new File(args[0]);
        if (!jarFile.isFile() || !jarFile.canRead()) {
            throw new IOException("JAR file does not exist or is not readable: " + jarFile);
        }

        File sourcesJar = args.length == 3 ? new File(args[2]) : null;
        if (sourcesJar != null && (!sourcesJar.isFile() || !sourcesJar.canRead())) {
            throw new IOException("Sources JAR does not exist or is not readable: " + sourcesJar);
        }

        try (JavadocIndex javadocIndex = sourcesJar == null
                ? JavadocIndex.empty() : JavadocIndex.open(sourcesJar)) {
            ClassHierarchy classHierarchy = ClassHierarchy.read(jarFile);
            Revapi revapi = Revapi.builder()
                    .withAnalyzersFromThreadContextClassLoader()
                    .withReporters(CodeOntologyReporter.class)
                    .build();

            AnalysisContext analysisContext = AnalysisContext.builder(revapi)
                    .withOldAPI(API.of(new FileArchive(jarFile)).build())
                    .withNewAPI(API.of().build())
                    .withData(ClassHierarchy.ANALYSIS_CONTEXT_KEY, classHierarchy)
                    .withData(CodeOntologyReporter.VERSION_CONTEXT_KEY, version)
                    .withData(CodeOntologyReporter.JAVADOC_CONTEXT_KEY, javadocIndex)
                    .build();

            try (AnalysisResult result = revapi.analyze(analysisContext)) {
                result.throwIfFailed();
            }
        }
    }
}
