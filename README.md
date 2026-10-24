# pajes
Paragraph's Java Extraction System

## Introduction

The goal of this tool is to transform java program information to either:
- prolog facts
- datalog triples (RDF/OWL2)

This allows to mix paragraph queries about software configuration with
information about dependent java software components. 

One of the focus points is to assess the impact of changes being made on a "reference" module that is used by a "client" java project.

## Example:

First build the project using: 
```sh
mvn clean package
```

### Example of facts generation for an API (refmodule)

To generate RDF facts from a "stable" (released) library JAR using Revapi:
```sh
java -cp target/pajes-0.0.1-SNAPSHOT.jar org.incodame.pajes.apiref.JavaLibToRdf "/path/to/library.jar" "1.2.3"
```
This writes the API surface to `refmodule-1.2.3.ttl` in the current directory. The
version is also recorded as `icodm:applFor` on the generated API resources.

To generate RDF facts about a patched java class using Spoon:
```sh
java -jar target/pajes-0.0.1-SNAPSHOT.jar "path/to/java/source/class" "path/to/git/patch/file"
```
This will write the RDF facts about the java patch to `refmodule-patch.ttl` in the current directory. 

Both .ttl files can then be imported for analysis to a triple store, and queried using SPARQL
to assess the impact of the java source changes:
- which methods or constructors have been changed
- are the patched classes still backwards compatible with the stable API ?
- etc...

### Example of facts generation for an API client (client usage report)

In order to generate facts documenting API client usage:
```sh
java -cp target/pajes-0.0.1-SNAPSHOT.jar org.incodame.pajes.client.ClientUseToRdf <client-module-path> <branch> <target-package>
```
produces a `client-use.ttl` in the current directory.

Example:
```sh
java -cp target/pajes-0.0.1-SNAPSHOT.jar org.incodame.pajes.client.ClientUseToRdf /home/of/pajes main "org.apache.jena.rdf.model"
Starting client usage analysis for module /home/of/pajes, branch main and target package org.apache.jena.rdf.model
Starting Spoon analysis; compiler diagnostics: client-use-errors.log
Resolved org.apache.jena.rdf.model dependency version 4.10.0
RDF analysis report generated: client-use.ttl (<triple-count> triples)
```

`ClientUseToRdf` uses Spoon with `ClientUseCompiler` to compile and analyze client
sources. The compiler uses the resolved maven project's classpath, so client code can use
Jena and other project dependencies. The target package's dependency version is
derived from the resolved Maven classpath. The branch, package and resolved version
identify an `icodm:LibraryDependency`; for this project, Jena resolves to version
`4.10.0` on `main` and `4.9.0` on branch `1.0.1`.
Client classes are marked with `icodm:applBranch`, while the generated module and
submodule records link to the library dependency and its target package/version.

`ClientUseCompiler` writes JDT compiler diagnostics to `client-use-errors.log`;
compiler errors are reported because they can make the generated client-use RDF
incomplete.

## Dependencies

- https://github.com/INRIA/spoon for parsing java sources
- https://github.com/revapi/revapi for analyzing java APIs
- https://zenodo.org/records/577939/files/CodeOntology.owl?download=1 java code ontology

## Reproduced third-party licenses

The license texts for the projects referenced above are reproduced locally in this repository so they remain available alongside the project source:

- Spoon: [licenses/spoon/LICENSE-MIT.txt](licenses/spoon/LICENSE-MIT.txt) and [licenses/spoon/LICENSE-CECILL-C.txt](licenses/spoon/LICENSE-CECILL-C.txt)
- Revapi: [licenses/revapi/LICENSE](licenses/revapi/LICENSE)
- CodeOntology: [licenses/codeontology/LICENSE.txt](licenses/codeontology/LICENSE.txt)
