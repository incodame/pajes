# pajes
Paragraph's Java Extraction System

## Introduction

The goal of this tool is to transform java program information to either:
- prolog facts
- datalog triples (RDF/OWL2)

This allows to mix paragraph queries about software configuration with
information about dependent java software components.

## Example:

build the project using: mvn clean package

java -jar target/pajes-0.0.1-SNAPSHOT.jar "/Users/tanguyro/Incodame/paragraph/ParagraphUI/src/main/java/org/incodame/paragraph/sample/webapp/demo/Parameter.java" "/Users/tanguyro/Incodame/paragraph/set-doc-since-information.patch"

will generate RDF facts into file "refmodule-patch.ttl"

To generate RDF facts from a compiled library JAR using Revapi:

```sh
java -cp target/pajes-0.0.1-SNAPSHOT.jar JavaLibToRdf "/path/to/library.jar"
```

This writes the API surface to `refmodule.ttl` in the current directory.

## Dependencies

- https://github.com/INRIA/spoon for parsing java sources
- https://github.com/revapi/revapi for analyzing java APIs
- https://zenodo.org/records/577939/files/CodeOntology.owl?download=1 java code ontology

## Reproduced third-party licenses

The license texts for the projects referenced above are reproduced locally in this repository so they remain available alongside the project source:

- Spoon: [licenses/spoon/LICENSE-MIT.txt](licenses/spoon/LICENSE-MIT.txt) and [licenses/spoon/LICENSE-CECILL-C.txt](licenses/spoon/LICENSE-CECILL-C.txt)
- Revapi: [licenses/revapi/LICENSE](licenses/revapi/LICENSE)
- CodeOntology: [licenses/codeontology/LICENSE.txt](licenses/codeontology/LICENSE.txt)
