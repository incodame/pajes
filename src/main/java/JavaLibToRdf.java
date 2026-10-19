import java.io.File;
import java.io.IOException;

import org.revapi.API;
import org.revapi.AnalysisContext;
import org.revapi.AnalysisResult;
import org.revapi.Revapi;
import org.revapi.base.FileArchive;

public class JavaLibToRdf {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: JavaLibToRdf <jar-file>");
        }

        File jarFile = new File(args[0]);
        if (!jarFile.isFile() || !jarFile.canRead()) {
            throw new IOException("JAR file does not exist or is not readable: " + jarFile);
        }

        Revapi revapi = Revapi.builder()
                .withAnalyzersFromThreadContextClassLoader()
                .withReporters(CodeOntologyReporter.class)
                .build();

        AnalysisContext analysisContext = AnalysisContext.builder(revapi)
                .withOldAPI(API.of(new FileArchive(jarFile)).build())
                .withNewAPI(API.of().build())
                .build();

        try (AnalysisResult result = revapi.analyze(analysisContext)) {
            result.throwIfFailed();
        }
    }
}
