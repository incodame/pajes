# pajes
Paragraph's Java Extraction System

## Introduction

The goal of this tool is to transform java program information to either:
- prolog facts
- datalog triples (RDF/OWL2)

This allows to mix paragraph queries about software configuration with
information about dependent java software components.

## Dependencies

- https://github.com/INRIA/spoon for parsing java sources
- https://github.com/revapi/revapi for analyzing java APIs
- https://zenodo.org/records/577939/files/CodeOntology.owl?download=1 java code ontology
