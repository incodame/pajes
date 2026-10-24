package org.incodame.pajes.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientUseRdfMetadataTest {

    private static final String ICODM = "https://github.com/Incodam/";

    @Test
    void resolvesTargetVersionFromMavenClasspathJar(@TempDir Path tempDir) throws Exception {
        Path jena49 = createJenaJar(tempDir.resolve("org/apache/jena/jena-core/4.9.0/jena-core-4.9.0.jar"));
        Path jena410 = createJenaJar(tempDir.resolve("org/apache/jena/jena-core/4.10.0/jena-core-4.10.0.jar"));

        assertEquals("4.9.0", ClientUseRdfMetadata.resolveTargetVersion(
                new String[] { jena49.toString() }, "org.apache.jena.rdf.model"));
        assertEquals("4.10.0", ClientUseRdfMetadata.resolveTargetVersion(
                new String[] { jena410.toString() }, "org.apache.jena.rdf.model"));
    }

    @Test
    void createsBranchAndVersionSpecificLibraryDependency() {
        Model model = ModelFactory.createDefaultModel();
        String modulePackage = "org.incodame.pajes.client";
        String targetPackage = "org.apache.jena.rdf.model";
        Resource dependency = ClientUseRdfMetadata.addDependency(model, "org.incodame.pajes",
                Collections.singleton(modulePackage), "main", targetPackage, "4.10.0");

        Resource rootModule = model.getResource(ICODM + "org.incodame.pajes");
        Resource clientModule = model.getResource(ICODM + modulePackage);
        Resource targetPackageResource = model.getResource(ICODM + targetPackage);
        Resource expectedDependency = model.getResource(dependency.getURI());

        assertTrue(dependency.getURI().matches(
                java.util.regex.Pattern.quote(ICODM + "org.incodame.pajes_dependency_") + "[0-9a-f]{12}"));
        assertTrue(rootModule.hasProperty(model.createProperty(ICODM + "hasSubModule"), clientModule));
        assertTrue(clientModule.hasProperty(RDF.type, model.getResource(ICODM + "JavaSubModule")));
        assertTrue(clientModule.hasProperty(RDF.type, model.getResource("http://webofcode.orgPackage")));
        assertTrue(clientModule.hasProperty(model.createProperty(ICODM + "hasDependency"), expectedDependency));
        assertTrue(dependency.hasProperty(RDF.type, model.getResource(ICODM + "LibraryDependency")));
        assertTrue(dependency.hasProperty(model.createProperty(ICODM + "applBranch"), "main"));
        assertTrue(dependency.hasProperty(model.createProperty(ICODM + "targetPackage"), targetPackageResource));
        assertTrue(dependency.hasProperty(model.createProperty(ICODM + "targetVersion"), "4.10.0"));
        assertTrue(targetPackageResource.hasProperty(RDF.type, model.getResource(ICODM + "JavaLibrary")));

        assertNotEquals(dependency.getURI(), ClientUseRdfMetadata.buildDependencyUri(
                "org.incodame.pajes", "1.0.1", targetPackage, "4.10.0"));
        assertNotEquals(dependency.getURI(), ClientUseRdfMetadata.buildDependencyUri(
                "org.incodame.pajes", "main", targetPackage, "4.9.0"));
        assertNotEquals(dependency.getURI(), ClientUseRdfMetadata.buildDependencyUri(
                "org.incodame.pajes", "main", "org.apache.jena", "4.10.0"));
    }

    private static Path createJenaJar(Path jarPath) throws Exception {
        Files.createDirectories(jarPath.getParent());
        try (OutputStream output = Files.newOutputStream(jarPath);
                JarOutputStream jar = new JarOutputStream(output)) {
            jar.putNextEntry(new JarEntry("org/apache/jena/rdf/model/Model.class"));
            jar.write(new byte[] { 0 });
            jar.closeEntry();
        }
        return jarPath;
    }
}
