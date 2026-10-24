package org.incodame.pajes.client;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

final class ClientUseRdfMetadata {

    private static final String ICODM = "https://github.com/Incodam/";
    private static final String WOC = "http://webofcode.org";
    private static final Pattern VERSION_PATTERN = Pattern.compile("\\d+(?:\\.[A-Za-z0-9_-]+)*");

    private ClientUseRdfMetadata() {
    }

    static String resolveTargetVersion(String[] classpath, String targetPackage) throws IOException {
        String packagePath = targetPackage.replace('.', '/') + "/";
        Set<String> versions = new LinkedHashSet<>();

        for (String classpathEntry : classpath) {
            File entry = new File(classpathEntry);
            if (entry.isDirectory()) {
                File packageDirectory = new File(entry, packagePath);
                File[] classes = packageDirectory.listFiles(
                        file -> file.isFile() && file.getName().endsWith(".class"));
                if (classes != null && classes.length > 0) {
                    String version = versionFromMavenPath(entry.toPath());
                    if (version != null) {
                        versions.add(version);
                    }
                }
            } else if (entry.isFile() && entry.getName().endsWith(".jar")) {
                try (JarFile jar = new JarFile(entry)) {
                    boolean containsPackageClass = jar.stream()
                            .map(JarEntry::getName)
                            .anyMatch(name -> name.startsWith(packagePath)
                                    && name.substring(packagePath.length()).indexOf('/') < 0
                                    && name.endsWith(".class"));
                    if (containsPackageClass) {
                        String version = versionFromMavenPath(entry.toPath());
                        if (version != null) {
                            versions.add(version);
                        }
                    }
                }
            }
        }

        if (versions.isEmpty()) {
            throw new IllegalStateException(
                    "Could not determine Maven dependency version for package " + targetPackage);
        }
        if (versions.size() > 1) {
            throw new IllegalStateException(
                    "Multiple Maven versions found for package " + targetPackage + ": " + versions);
        }
        return versions.iterator().next();
    }

    static Resource addDependency(Model model, String moduleGroupId, Collection<String> packages,
            String branch, String targetPackage, String targetVersion) {
        String icodm = ICODM;
        String woc = WOC;
        model.setNsPrefix("icodm", icodm);
        model.setNsPrefix("woc", woc);

        Resource javaModuleType = model.createResource(icodm + "JavaModule");
        Resource javaSubModuleType = model.createResource(icodm + "JavaSubModule");
        Resource libraryDependencyType = model.createResource(icodm + "LibraryDependency");
        Resource javaLibraryType = model.createResource(icodm + "JavaLibrary");
        Resource wocPackageType = model.createResource(woc + "Package");
        Property hasSubModule = model.createProperty(icodm + "hasSubModule");
        Property hasDependency = model.createProperty(icodm + "hasDependency");
        Property applBranch = model.createProperty(icodm + "applBranch");
        Property dependencyTargetPackage = model.createProperty(icodm + "targetPackage");
        Property dependencyTargetVersion = model.createProperty(icodm + "targetVersion");

        Resource rootModule = model.createResource(icodm + moduleGroupId)
                .addProperty(RDF.type, javaModuleType);
        for (String packageName : packages) {
            Resource subModule = model.createResource(icodm + packageName)
                    .addProperty(RDF.type, javaSubModuleType)
                    .addProperty(RDF.type, wocPackageType);
            rootModule.addProperty(hasSubModule, subModule);
            subModule.addProperty(hasDependency,
                    model.createResource(buildDependencyUri(moduleGroupId, branch, targetPackage, targetVersion)));
        }

        Resource dependency = model.createResource(buildDependencyUri(
                moduleGroupId, branch, targetPackage, targetVersion))
                .addProperty(RDF.type, libraryDependencyType)
                .addProperty(applBranch, branch)
                .addProperty(dependencyTargetPackage, model.createResource(icodm + targetPackage))
                .addProperty(dependencyTargetVersion, targetVersion);
        model.createResource(icodm + targetPackage)
                .addProperty(RDF.type, javaLibraryType)
                .addProperty(RDF.type, wocPackageType);
        return dependency;
    }

    static String buildDependencyUri(String moduleGroupId, String branch, String targetPackage, String targetVersion) {
        String identity = branch + "\n" + targetPackage + "\n" + targetVersion;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (int index = 0; index < 6; index++) {
                hash.append(String.format("%02x", digest[index]));
            }
            return ICODM + moduleGroupId + "_dependency_" + hash;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String versionFromMavenPath(java.nio.file.Path artifactPath) {
        java.nio.file.Path versionDirectory = artifactPath.getParent();
        if (versionDirectory == null) {
            return null;
        }
        String version = versionDirectory.getFileName().toString();
        return VERSION_PATTERN.matcher(version).matches() ? version : null;
    }
}
