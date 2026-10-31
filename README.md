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
An optional Maven sources JAR containing Java source files can be supplied as a
third argument:
```sh
java -cp target/pajes-0.0.1-SNAPSHOT.jar org.incodame.pajes.apiref.JavaLibToRdf \
  "/path/to/library.jar" "1.2.3" "/path/to/library-sources.jar"
```
Pajes uses Spoon to analyze the sources JAR. Method comments are emitted as
`icodm:methodJavadoc`, parameter names as `rdfs:label`, and `@param` comments as
`icodm:paramJavadoc`. Parameter names are also emitted without the optional
archive, using names available in the compiled library.

To generate RDF facts for all Java source files mentioned in a patch using Spoon,
pass the source base directory followed by the patch file:
```sh
java -jar target/pajes-0.0.1-SNAPSHOT.jar "path/to/source/base/directory" "path/to/git/patch/file"
```
Java paths from the patch are resolved under the source base directory and
processed one by one. Deleted top-level classes and interfaces are recorded in
the RDF with `icodm:isDeleted` pointing to the deleted type. This writes the
RDF facts about the Java patch to `refmodule-patch.ttl` in the current directory.

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

To configure Maven settings for dependency resolution, create `pajes-config.yaml`
in the current working directory:
```yaml
maven:
  settings: /path/to/maven/settings.xml
client-annotations:
  - name: EnumValue
    properties:
      - values
  - name: ValueRange
    properties:
      - lower
      - upper
```
Relative settings paths are resolved relative to the configuration file. If the
file or `maven.settings` property is absent, Maven uses its default settings.
A configured settings file must exist and be readable.
`client-annotations` is an optional YAML list. Each entry names a Java annotation
and selects the annotation properties to include in client constraints. The
processor reads matching annotations from target API method parameters and
methods' return annotations, and writes call-site constraints to
`client-use-constraints.ttl`. Constraint identifiers use the API call's source
line; parameter constraint identifiers also include the one-based API parameter
position.
For APIs that cannot be annotated directly, a client wrapper method can be
marked with `@ApiWrapper`:
```java
@ApiWrapper
void writeModel(@EnumValue(values = {"TURTLE", "N3"}) String format) {
    model.write(format);
}
```
The processor recognizes the marker by its simple annotation name, regardless
of its package. An annotated wrapper must contain exactly one call into the
selected target package, and annotated parameters must be passed directly to
that API call in the same positions. RDF links and constraints are generated
for the underlying API method; the wrapper and its marker are omitted. The
constraint line number is the client-side line where the wrapper is called.

Example:
```sh
java -cp target/pajes-0.0.1-SNAPSHOT.jar org.incodame.pajes.client.ClientUseToRdf /home/of/pajes main "org.apache.jena.rdf.model"
Starting client usage analysis for module /home/of/pajes, branch main and target package org.apache.jena.rdf.model
Starting Maven dependency resolution; output: client-use.log
Starting Spoon analysis; compiler diagnostics: client-use-errors.log
Resolved org.apache.jena.rdf.model dependency version 4.10.0
RDF analysis report generated: client-use.ttl (<triple-count> triples)
```

`ClientUseToRdf` uses Spoon with `ClientUseCompiler` to compile and analyze client
sources. Maven resolves the reactor's compile classpath, including `provided`
dependencies, using the configured settings file. Maven stdout and stderr are
written to `client-use.log`. Resolution must succeed before analysis starts;
failures stop analysis with a reference to that log. Only fresh, run-specific
classpath files are read across modules, then removed; old Spoon classpath caches
are not used. The compiler uses this classpath so client code can use Jena and
other project dependencies. The target package's dependency version is
derived from the resolved Maven classpath. The branch, package and resolved version
identify an `icodm:LibraryDependency`; for this project, Jena resolves to version
`4.10.0` on `main` and `4.9.0` on branch `1.0.1`.
Client classes are marked with `icodm:applBranch`, while the generated module and
submodule records link to the library dependency and its target package/version.
Class and interface records are emitted only when they have a `woc:calls`,
`woc:implements`, or `woc:extends` link to the target API. Unrelated types are
omitted; package and module metadata is still retained.

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
