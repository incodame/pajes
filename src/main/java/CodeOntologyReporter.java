import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

import org.revapi.AnalysisContext;
import org.revapi.Report;
import org.revapi.Reporter;
import org.revapi.java.spi.JavaModelElement;

public class CodeOntologyReporter implements Reporter {

    private Model model;
    private CodeOntologyVisitor visitor;
    
    private static final String WOC_NS = "http://webofcode.org";
    private static final String APP_NS = "https://github.com/Incodam/";
    static final String OUTPUT_FILE_PROPERTY = "pajes.rdf.output";

    @Override
    public void initialize(AnalysisContext analysisContext) {
        this.model = ModelFactory.createDefaultModel();
        this.model.setNsPrefix("woc", WOC_NS);
        this.model.setNsPrefix("icodm", APP_NS);
        
        // Pass the model and namespaces down into our visitor instance
        this.visitor = new CodeOntologyVisitor(model, WOC_NS, APP_NS);
    }

    @Override
    public void report(Report report) {
        // Intercept elements during traversal phase
        if (report.getOldElement() instanceof JavaModelElement) {
            JavaModelElement javaElem = (JavaModelElement) report.getOldElement();

            // Extract the standard javax.lang.model structural element and pass to visitor
            if (javaElem.getDeclaringElement() != null) {
                javaElem.getDeclaringElement().accept(visitor, null);
            }
        }
    }

    @Override
    public void close() throws IOException {
        String outputFile = System.getProperty(OUTPUT_FILE_PROPERTY, "refmodule.ttl");
        try (FileWriter out = new FileWriter(outputFile)) {
            model.write(out, "TURTLE");
        }
    }

    @Override public String getExtensionId() { return "codeontology-visitor-reporter"; }
    @Override public Reader getJSONSchema() { return null; }
}