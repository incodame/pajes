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
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import spoon.MavenLauncher;
import spoon.support.StandardEnvironment;
import spoon.support.compiler.SpoonPom;

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
    void resolvesTargetVersionFromProjectPomForAnOwnedPackage(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>org.incodame.pajes</groupId>"
                        + "<artifactId>pajes</artifactId><version>0.0.1-SNAPSHOT</version></project>");
        Path packageDirectory = Files.createDirectories(tempDir.resolve(
                "src/main/java/org/incodame/pajes/outputApi"));
        Files.writeString(packageDirectory.resolve("RdfGenerator.java"),
                "package org.incodame.pajes.outputApi; public interface RdfGenerator { }");
        SpoonPom pom = new SpoonPom(tempDir.toString(), MavenLauncher.SOURCE_TYPE.APP_SOURCE,
                new StandardEnvironment());

        assertEquals("0.0.1-SNAPSHOT", ClientUseRdfMetadata.resolveTargetVersion(
                new String[0], "org.incodame.pajes.outputApi", pom));
    }

    @Test
    void doesNotUseProjectVersionForAnExternalPackageWithoutClasspathVersion(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>org.incodame.pajes</groupId>"
                        + "<artifactId>pajes</artifactId><version>0.0.1-SNAPSHOT</version></project>");
        SpoonPom pom = new SpoonPom(tempDir.toString(), MavenLauncher.SOURCE_TYPE.APP_SOURCE,
                new StandardEnvironment());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> ClientUseRdfMetadata.resolveTargetVersion(
                        new String[0], "org.external.library", pom));
    }

    @Test
    void createsBranchAndVersionSpecificLibraryDependency() {
        Model model = ModelFactory.createDefaultModel();
        String modulePackage = "org.incodame.pajes.client";
        String targetPackage = "org.apache.jena.rdf.model";
        ClientUseRdfMetadata.addDependency(model, "org.incodame.pajes",
                Collections.singleton(modulePackage), Collections.singleton(modulePackage),
                "main", targetPackage, "4.10.0");

        Resource rootModule = model.getResource(ICODM + "org.incodame.pajes");
        Resource clientModule = model.getResource(ICODM + modulePackage);
        Resource targetPackageResource = model.getResource(ICODM + targetPackage);
        Resource dependency = model.getResource(ClientUseRdfMetadata.buildDependencyUri(
                "org.incodame.pajes", "main", targetPackage, "4.10.0"));
        Resource expectedDependency = model.getResource(dependency.getURI());

        assertTrue(dependency.getURI().matches(
                java.util.regex.Pattern.quote(ICODM + "org.incodame.pajes_dependency_") + "[0-9a-f]{12}"));
        assertTrue(rootModule.hasProperty(model.createProperty(ICODM + "hasSubModule"), clientModule));
        assertTrue(clientModule.hasProperty(RDF.type, model.getResource(ICODM + "JavaSubModule")));
        assertTrue(clientModule.hasProperty(RDF.type, model.getResource("http://rdf.webofcode.org/woc/Package")));
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

    @Test
    void doesNotLinkProjectOwnedPackageAsLibraryDependency() {
        Model model = ModelFactory.createDefaultModel();
        String targetPackage = "org.incodame.pajes.outputApi";
        ClientUseRdfMetadata.addDependency(model, "org.incodame.pajes",
                java.util.List.of("org.incodame.pajes.apiref", "org.incodame.pajes.client", targetPackage),
                Collections.singleton("org.incodame.pajes.client"),
                "main", targetPackage, "0.0.1-SNAPSHOT");

        Property dependencyProperty = model.createProperty(ICODM + "hasDependency");
        Resource clientPackage = model.getResource(ICODM + "org.incodame.pajes.client");
        Resource projectPackage = model.getResource(ICODM + "org.incodame.pajes.apiref");
        Resource targetResource = model.getResource(ICODM + targetPackage);
        Resource dependency = model.getResource(ClientUseRdfMetadata.buildDependencyUri(
                "org.incodame.pajes", "main", targetPackage, "0.0.1-SNAPSHOT"));
        assertTrue(projectPackage.hasProperty(RDF.type,
                model.getResource(ICODM + "JavaSubModule")));
        assertTrue(targetResource.hasProperty(RDF.type,
                model.getResource(ICODM + "JavaLibrary")));
        assertTrue(clientPackage.hasProperty(dependencyProperty, dependency));
        assertTrue(dependency.hasProperty(RDF.type, model.getResource(ICODM + "LibraryDependency")));
        assertTrue(dependency.hasProperty(model.createProperty(ICODM + "targetPackage"), targetResource));
        assertTrue(!projectPackage.hasProperty(dependencyProperty));
        assertTrue(!targetResource.hasProperty(dependencyProperty));
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
