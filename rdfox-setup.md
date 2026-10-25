# RDFox setup with Docker Swarm

This is a deployment checklist for loading Pajes RDF data into a persistent
RDFox server and querying it through the SPARQL endpoint. Adapt image tags,
license settings, and deployment details to the RDFox release and license
available to you.

## 1. Prerequisites

- A Docker Engine host with Swarm enabled. For a single-machine deployment,
  initialize it with `docker swarm init`.
- Access to the official `oxfordsemantic/rdfox` image and a valid RDFox license.
  Use the image tag supported by that license. The matching
  `oxfordsemantic/rdfox-init` image is used to prepare a persistent server
  directory.
- Keep the license key and administrator password out of this repository and
  out of committed stack files. Use Docker secrets or another secret manager.
- Choose the Swarm node that will hold the RDFox data. A local Docker volume or
  bind mount is node-local; constrain the service to that node, or configure
  storage shared by every node where the service could run.

## 2. Prepare the Pajes input files

Copy the Turtle files to a stable directory on the storage node and mount that
directory read-only into the RDFox task. Include:

- `refmodule-0.0.1.ttl`
- `refmodule-patch.ttl`
- `owl/pajes.owl`
- Either `client-use.ttl` or `client-use-extended.ttl`, depending on which
  client-use data you want to query. Do not include both unless merging their
  overlapping data is intentional.

The ontology file contributes vocabulary declarations as RDF triples. Loading
it does not cause RDFox to automatically perform OWL reasoning over the data.

## 3. Initialize persistent RDFox storage

Before starting the long-lived server, initialize its persistent server
directory with the `oxfordsemantic/rdfox-init` image whose tag matches the
chosen RDFox image. Provide the license and initial administrator credentials
through the supported RDFox configuration mechanism, and mount the server
directory at the image's default `/home/rdfox/.RDFox` path.

The initialization image prepares directory ownership and access control. It
can also execute an `initialize.rdfox` shell script if one is supplied in its
working directory. Initialization is a one-time operation for a new server
directory; retain that directory as persistent storage for subsequent restarts.

## 4. Deploy the RDFox service in Swarm

Create a stack definition for the official `oxfordsemantic/rdfox` image and
deploy it with `docker stack deploy`. The service should:

- Use the same RDFox image tag as the initialization image.
- Run RDFox in daemon mode.
- Mount persistent storage at `/home/rdfox/.RDFox`.
- Mount the Pajes input directory read-only if you plan to import files from
  inside the server task.
- Run one replica unless you have configured RDFox-supported shared storage and
  a multi-instance deployment.
- Publish the RDFox endpoint's default container port, `12110`, on the Swarm
  host (choose a host port as appropriate).
- Use a placement constraint when storage is node-local.
- Avoid exposing the endpoint publicly without appropriate authentication,
  network restrictions, and TLS termination.

Keep the stack file in a secure deployment location if it contains environment
variable substitutions. Docker Swarm secrets should be preferred for
credentials, subject to the environment variables or secret-file options
supported by the selected RDFox image.

## 5. Create a data store and load the RDF

Connect to the running server using the RDFox shell's remote mode or the RDFox
REST API, authenticate as an authorized role, and create a data store named
`pajes`. Import each selected Turtle file into that data store. The RDFox shell
supports commands of this form:

```text
dstore create pajes
import "/path/visible/inside/container/refmodule-0.0.1.ttl"
import "/path/visible/inside/container/refmodule-patch.ttl"
import "/path/visible/inside/container/pajes.owl"
import "/path/visible/inside/container/client-use.ttl"
```

Run these commands from an RDFox shell process that can read the mounted files
(for example, a remote shell running inside the task). Replace the example
paths with the actual mount path available to that process. If using the
extended proposal, import `client-use-extended.ttl` instead of `client-use.ttl`.
Alternatively, upload the Turtle content through the REST API. Preserve the
data store and server directory between restarts.

## 6. Query and verify the endpoint

The RDFox SPARQL endpoint for the `pajes` data store is normally:

```text
http://<swarm-host>:12110/datastores/pajes/sparql
```

The endpoint port is configurable. Use the authentication method configured for
the server when submitting queries. For example, a SPARQL query can count the
loaded triples:

```sparql
SELECT (COUNT(*) AS ?triples)
WHERE {
  ?subject ?predicate ?object
}
```

Check the RDFox health endpoint and server logs if the service does not become
ready or the query fails. Back up the persistent server directory according to
the RDFox guidance for the version in use.

## References

- [RDFox Docker documentation](https://docs.oxfordsemantic.tech/7.3/docker.html)
- [RDFox executable modes, including `init`, `daemon`, and `remote`](https://docs.oxfordsemantic.tech/7.3/rdfox-executable.html)
- [RDFox shell commands](https://docs.oxfordsemantic.tech/7.3/rdfox-shell.html)
- [RDFox APIs and REST endpoint](https://docs.oxfordsemantic.tech/7.3/apis.html)
- [RDFox endpoint configuration](https://docs.oxfordsemantic.tech/7.3/rdfox-endpoint.html)

The links above point to the RDFox 7.3 documentation; consult the documentation
matching the image tag you deploy if using another release.
