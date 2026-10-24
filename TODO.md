# TODO

These are follow-up improvements identified during a review of the current RDF
generation code. They are suggestions only; no implementation changes are
included here.

## Client-use RDF generation

- [ ] **Make runner configuration explicit.** `ClientUseToRdf` currently hardcodes
  `path/to/settings.xml`, `client-use.log`, `client-use-errors.log`, and
  `client-use.ttl`. Accept these as optional configuration or derive sensible
  defaults from the client module path, and validate the module directory and
  its `pom.xml` before starting Spoon.
- [ ] **Avoid replacing process-wide stderr.** `ClientUseToRdf` temporarily
  redirects `System.err`. Prefer passing a logger/output stream to the
  components that need it so unrelated threads and callers are not affected.
- [ ] **Make target dependency resolution robust to Maven layouts.**
  `ClientUseRdfMetadata.resolveTargetVersion` infers a version from a
  `groupId/artifactId/version` path and searches for classes under the exact
  target package. Consider reading Maven coordinates from dependency metadata
  (for example, the resolved artifact's POM) and supporting packages split
  across artifacts, shaded JARs, and versioned artifacts.
- [ ] **Validate dependency resolution behavior.** Add tests for packages not
  found on the classpath, multiple artifacts or versions providing the target
  package, Maven snapshots/classifiers, and package directories in classpath
  folders. Keep failures explicit rather than guessing a version.
- [ ] **Separate client analysis from RDF serialization.** Extract a service
  that accepts module path, branch, and package and returns an RDF model;
  keep `main` focused on argument parsing, logging, and writing the result.
  This would make end-to-end tests possible without changing the process
  working directory or writing fixed-name files.

## Library API RDF generation

- [ ] **Make output configuration consistent.** The reporter chooses a
  versioned filename and supports output properties. Document or expose that
  configuration consistently, and test that concurrent analyses can write
  separate outputs without relying on process-wide system properties.
- [ ] **Check analysis completeness for public API elements.** Add representative
  fixtures covering public/protected fields, constructors, methods, inherited
  members, and external parents. Assert that the RDF reports the intended
  records and direct hierarchy for each fixture.
- [ ] **Centralize member URI generation.** The API visitor and client-use
  processor each build member identifiers from signatures. Share a tested
  URI/hash utility so overloads, arrays, generics, constructors, and nested
  types resolve consistently across outputs.

## Patch RDF generation

- [ ] **Split `JavaPatchToRdf` into focused components.** The class currently
  handles patch parsing, signature matching, Spoon model traversal, URI
  creation, RDF graph construction, and file output. Consider extracting:
  `PatchSignatureParser`, a member-signature/URI utility, an RDF model builder,
  and a small command-line/output layer. Preserve current RDF output with
  fixture-based regression tests during the refactor.
- [ ] **Replace ad hoc patch signature parsing.** Helpers currently parse
  method declarations and parameters using string slicing and comma splitting.
  Nested generic types, annotations, arrays, varargs, and multiline declarations
  can make this ambiguous. Use a Java-aware parser for patch declarations or
  define and test the supported syntax explicitly.
- [ ] **Make patch matching results explicit.** Signature matching and
  parameter-type resolution currently fall back through several heuristics.
  Return a structured match result that distinguishes exact matches,
  changed signatures, added members, and unresolved/ambiguous matches; report
  unresolved cases rather than silently assigning a potentially incorrect RDF
  relationship.
- [ ] **Expand patch RDF regression coverage.** Add focused fixtures for
  overloads, nested generic parameters, varargs, imports with duplicate simple
  names, constructors, interfaces/enums, and multiple top-level types. Compare
  semantic RDF graphs rather than serialized Turtle ordering.
- [ ] **Use consistent output handling.** Consider sharing the atomic,
  UTF-8-based writer used by the API reporter instead of writing directly to
  the final `refmodule-patch.ttl` file. This avoids leaving a partial result if
  serialization fails.
