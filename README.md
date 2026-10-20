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

To generate RDF facts from a "stable" (released) library JAR using Revapi:
```sh
java -cp target/pajes-0.0.1-SNAPSHOT.jar JavaLibToRdf "/path/to/library.jar"
```
This writes the API surface to `refmodule.ttl` in the current directory.

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

## Dependencies

- https://github.com/INRIA/spoon for parsing java sources
- https://github.com/revapi/revapi for analyzing java APIs
- https://zenodo.org/records/577939/files/CodeOntology.owl?download=1 java code ontology

## Reproduced third-party licenses

The license texts for the projects referenced above are reproduced locally in this repository so they remain available alongside the project source:

- Spoon: [licenses/spoon/LICENSE-MIT.txt](licenses/spoon/LICENSE-MIT.txt) and [licenses/spoon/LICENSE-CECILL-C.txt](licenses/spoon/LICENSE-CECILL-C.txt)
- Revapi: [licenses/revapi/LICENSE](licenses/revapi/LICENSE)
- CodeOntology: [licenses/codeontology/LICENSE.txt](licenses/codeontology/LICENSE.txt)
