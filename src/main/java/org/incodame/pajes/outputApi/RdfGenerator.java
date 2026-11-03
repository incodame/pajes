package org.incodame.pajes.outputApi;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.jena.rdf.model.Model;

public interface RdfGenerator {

    /**
     * Generates RDF data based on the provided input model 
     * 
     * @param outputFilePath the path to the output file where the RDF data will be written
     * @param model the input model containing the data to be converted to RDF
     * @param timestamp an optional timestamp; implementations may ignore it
     * @return number of triples generated
     * @throws IOException if the RDF data cannot be written
     */
    int generateRdf(Path outputFilePath, Model model, String timestamp) throws IOException;
}
