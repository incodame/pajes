package org.incodame.pajes.apiref;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.revapi.AnalysisContext;
import org.revapi.Report;
import org.revapi.Reporter;
import org.revapi.java.spi.JavaModelElement;

public class CodeOntologyReporter implements Reporter {

    private Model model;
    private CodeOntologyVisitor visitor;
    private Path outputFile;
    
    private static final String WOC_NS = "http://webofcode.org";
    private static final String APP_NS = "https://github.com/Incodam/";
    static final String OUTPUT_FILE_PROPERTY = "pajes.rdf.output";
    static final String OUTPUT_DIRECTORY_PROPERTY = "pajes.rdf.output.directory";
    static final String VERSION_CONTEXT_KEY = "pajes.api.version";
    static final String JAVADOC_CONTEXT_KEY = "pajes.api.javadoc";

    @Override
    public void initialize(AnalysisContext analysisContext) {
        this.model = ModelFactory.createDefaultModel();
        
        Object versionData = analysisContext.getData(VERSION_CONTEXT_KEY);
        if (versionData != null && (!(versionData instanceof String) || ((String) versionData).isBlank())) {
            throw new IllegalStateException("Invalid API version data in Revapi analysis context");
        }
        String configuredOutputFile = System.getProperty(OUTPUT_FILE_PROPERTY);
        if (configuredOutputFile == null) {
            Path outputDirectory = Paths.get(System.getProperty(OUTPUT_DIRECTORY_PROPERTY, "."));
            configuredOutputFile = outputDirectory.resolve("refmodule-" + versionData + ".ttl").toString();
        }
        this.outputFile = Paths.get(configuredOutputFile).toAbsolutePath();

        this.model.setNsPrefix("woc", WOC_NS);
        this.model.setNsPrefix("icodm", APP_NS);
        this.model.setNsPrefix("owl", "http://www.w3.org/2002/07/owl#");
        this.model.setNsPrefix("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
        
        // Pass the model and namespaces down into our visitor instance
        Object hierarchyData = analysisContext.getData(ClassHierarchy.ANALYSIS_CONTEXT_KEY);
        if (hierarchyData != null && !(hierarchyData instanceof ClassHierarchy)) {
            throw new IllegalStateException("Invalid class hierarchy data in Revapi analysis context");
        }
        Object javadocData = analysisContext.getData(JAVADOC_CONTEXT_KEY);
        if (javadocData != null && !(javadocData instanceof JavadocIndex)) {
            throw new IllegalStateException("Invalid Javadoc index data in Revapi analysis context");
        }
        this.visitor = new CodeOntologyVisitor(model, WOC_NS, APP_NS, (ClassHierarchy) hierarchyData,
                (String) versionData, (JavadocIndex) javadocData);
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
        Path temporaryFile = Files.createTempFile(outputFile.getParent(), "pajes", ".ttl");
        try {
            try (Writer out = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                model.write(out, "TURTLE");
            }
            Files.move(temporaryFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    @Override public String getExtensionId() { return "codeontology-visitor-reporter"; }
    @Override public Reader getJSONSchema() { return null; }
}