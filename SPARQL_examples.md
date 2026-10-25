# Pajes SPARQL examples

These examples query the Pajes RDF data served by QLever at
`http://localhost:7001`. They use the `icodm:` vocabulary defined in
[`owl/pajes.owl`](./owl/pajes.owl), together with Web of Code (`woc`) terms.

Run a query from the command line by passing it to QLever, for example:

```sh
qlever query --sparql-endpoint http://localhost:7001 \
  'SELECT (COUNT(*) AS ?triples) WHERE { ?s ?p ?o }'
```

Alternatively, paste any query below into the QLever UI.

## 1. Find dependencies by module, branch, and version

This follows the ontology's module → submodule → dependency relationships.

```sparql
PREFIX icodm: <https://github.com/Incodam/>

SELECT ?submodule ?branch ?package ?version
WHERE {
  <https://github.com/Incodam/org.incodame.pajes>
      icodm:hasSubModule ?submodule .

  ?submodule icodm:hasDependency ?dependency .
  ?dependency a icodm:LibraryDependency ;
              icodm:applBranch ?branch ;
              icodm:targetPackage ?package ;
              icodm:targetVersion ?version .
}
ORDER BY ?submodule ?branch
```

## 2. List API classes and their direct superclasses

This returns explicit `owl:Class` and `rdfs:subClassOf` statements. QLever
queries the indexed triples; it does not automatically perform OWL inference.

```sparql
PREFIX icodm: <https://github.com/Incodam/>
PREFIX owl:   <http://www.w3.org/2002/07/owl#>
PREFIX rdfs:  <http://www.w3.org/2000/01/rdf-schema#>
PREFIX woc:   <http://webofcode.org>

SELECT ?class ?name ?superclass ?version
WHERE {
  ?class a owl:Class, woc:Class ;
         woc:hasSimpleName ?name ;
         icodm:applFor ?version .
  OPTIONAL { ?class rdfs:subClassOf ?superclass }
}
ORDER BY ?name ?version
```

## 3. List methods and constructors declared on classes

```sparql
PREFIX woc: <http://webofcode.org>

SELECT ?class ?memberType ?member ?name ?modifier
WHERE {
  {
    ?class woc:hasMethod ?member .
    ?member a woc:Method ;
            woc:hasSimpleName ?name ;
            woc:hasModifier ?modifier .
    BIND("method" AS ?memberType)
  }
  UNION
  {
    ?class woc:hasConstructor ?member .
    ?member a woc:Constructor ;
            woc:hasSimpleName ?name ;
            woc:hasModifier ?modifier .
    BIND("constructor" AS ?memberType)
  }
}
ORDER BY ?class ?memberType ?name
```

## 4. List methods and parameters, including zero-parameter methods

The parameter-list traversal is optional. Therefore methods with an empty
`woc:parameters` RDF list are still returned, with `?index` and `?type`
unbound. Methods with parameters produce one result row per parameter.

```sparql
PREFIX woc: <http://webofcode.org>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

SELECT ?class ?method ?name ?index ?type
WHERE {
  ?class woc:hasMethod ?method .
  ?method a woc:Method ;
          woc:hasSimpleName ?name .

  OPTIONAL {
    ?method woc:parameters/rdf:rest*/rdf:first ?parameter .
    ?parameter woc:parameterIndex ?index ;
               woc:parameterType ?type .
  }
}
ORDER BY ?class ?name ?index
```

## 5. Connect patched methods to their stable versions

```sparql
PREFIX icodm: <https://github.com/Incodam/>

SELECT ?patchedMethod ?stableMethod
WHERE {
  ?patchedMethod icodm:isPatchOfMethod ?stableMethod .
}
```

## 6. Find methods called by Pajes client classes

```sparql
PREFIX icodm: <https://github.com/Incodam/>
PREFIX woc:   <http://webofcode.org>

SELECT ?clientClass ?branch ?calledMethod
WHERE {
  ?clientClass a woc:Class ;
               icodm:applBranch ?branch ;
               woc:calls ?calledMethod .
}
ORDER BY ?clientClass ?branch
```

## 7. Find client components affected by patched method parameters

This connects a client component's call to a stable library method with the
patched method that identifies it through `icodm:isPatchOfMethod`. It returns
the client component and its branch when at least one parameter index/type pair
was added, removed, or changed.

```sparql
PREFIX icodm: <https://github.com/Incodam/>
PREFIX rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX woc:   <http://webofcode.org>

SELECT DISTINCT ?clientComponent ?branch ?stableMethod ?patchedMethod
WHERE {
  ?clientComponent woc:calls ?stableMethod ;
                   icodm:applBranch ?branch .

  ?patchedMethod icodm:isPatchOfMethod ?stableMethod ;
                 a woc:Method .

  {
    ?patchedMethod woc:parameters/rdf:rest*/rdf:first ?patchedParameter .
    ?patchedParameter woc:parameterIndex ?index ;
                      woc:parameterType ?patchedType .
    FILTER NOT EXISTS {
      ?stableMethod woc:parameters/rdf:rest*/rdf:first ?stableParameter .
      ?stableParameter woc:parameterIndex ?index ;
                       woc:parameterType ?patchedType .
    }
  }
  UNION
  {
    ?stableMethod woc:parameters/rdf:rest*/rdf:first ?stableParameter .
    ?stableParameter woc:parameterIndex ?index ;
                     woc:parameterType ?stableType .
    FILTER NOT EXISTS {
      ?patchedMethod woc:parameters/rdf:rest*/rdf:first ?patchedParameter .
      ?patchedParameter woc:parameterIndex ?index ;
                        woc:parameterType ?stableType .
    }
  }
}
ORDER BY ?branch ?clientComponent
```

The comparison uses parameter index and type, so it detects additions,
removals, type changes, and order changes that alter the indexed type pairs. It
compares the parameter descriptions in the RDF; it does not infer source-level
binary or behavioral compatibility.
For results, the indexed client-use and patch/API data must refer to the same
stable method IRI. If the client calls the stable method but the patch data does
not contain an `icodm:isPatchOfMethod` link to that IRI, this query returns no
row.

## 8. Find methods deleted by a patch

This compares stable methods from the original API data with the patch data.
For each stable method, it constructs the expected patched IRI by replacing
the `_stable` suffix with `_patched`. If no method record exists at that exact
IRI, the query reports the stable method.

```sparql
PREFIX woc:   <http://webofcode.org>

SELECT DISTINCT ?class ?methodName ?stableMethod
WHERE {
  ?class woc:hasMethod ?stableMethod .
  ?stableMethod a woc:Method ;
                woc:hasSimpleName ?methodName .

  BIND(IRI(REPLACE(STR(?stableMethod), "_stable$", "_patched"))
       AS ?sameSignaturePatchedMethod)

  FILTER NOT EXISTS {
    ?sameSignaturePatchedMethod a woc:Method .
  }
}
ORDER BY ?class ?methodName
```

This uses only the Pajes method IRI naming convention and does not use
`icodm:isPatchOfMethod`. It assumes the patch RDF successfully represents the
full current source; missing or incomplete patch output can otherwise look like
deleted methods.

## Dataset notes

- Query 1 and query 6 use the extended client-use proposal in
  [`client-use-extended.ttl`](./client-use-extended.ttl). The current setup may
  instead index `client-use.ttl`; use the extended file in its place if you
  want those branch, dependency-version, and called-method results.
- Query 7 requires client-use data and patch/API data with matching stable
  method IRIs and parameter records.
- Query 8 requires both baseline API data and patch data, for example
  `refmodule-0.0.1.ttl` and `refmodule-patch.ttl`.
- Queries 2–5 use API or patch statements in `refmodule-0.0.1.ttl` and
  `refmodule-patch.ttl`.
- Include `owl/pajes.owl` in the index if you also want to query the ontology's
  class and property declarations.
