package org.incodame.pajes.apiref;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.OWL;
import org.junit.jupiter.api.Test;

class PajesOntologyTest {

    private static final String ICODM = "https://github.com/Incodam/";

    @Test
    void declaresIcodmVocabularyEmittedByRdfGenerators() {
        Model ontology = ModelFactory.createDefaultModel();
        RDFDataMgr.read(ontology, Path.of("owl/pajes.ttl").toUri().toString());

        assertDeclarations(ontology, OWL.Class, List.of(
                "JavaModule", "JavaSubModule", "JavaLibrary", "LibraryDependency",
                "ApiClientConstraint", "ApiClientConstraintSet", "JavaConstraintAnnotation", "CoAProperty"));
        assertDeclarations(ontology, OWL.ObjectProperty, List.of(
                "hasSubModule", "hasDependency", "targetPackage", "isNewConstructor",
                "isPatchOfConstructor", "isNewMethod", "isPatchOfMethod", "isDeleted",
                "onMethodCall", "onMethodReturn", "onCallback", "onCallbackReturn", "onTypeUse",
                "hasContraints", "annotations", "annotationProperties"));
        assertDeclarations(ontology, OWL.DatatypeProperty, List.of(
                "applBranch", "applFor", "targetVersion", "methodJavadoc", "paramJavadoc"));
    }

    private static void assertDeclarations(Model ontology, Resource declarationType, List<String> localNames) {
        for (String localName : localNames) {
            assertTrue(ontology.contains(ontology.createResource(ICODM + localName),
                    org.apache.jena.vocabulary.RDF.type, declarationType),
                    "Expected " + localName + " to be declared as " + declarationType.getLocalName());
        }
    }
}
