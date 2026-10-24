# qlever setup

Run the commands below from the Pajes repository root. They copy the RDF files
into a separate QLever working directory. Copies are used instead of symlinks
so the files are available to QLever regardless of how it resolves input paths.

Reference information is available at: https://docs.qlever.dev

```sh
PAJES_DIR="$(pwd)"
QLEVER_DIR="../qlever/pajes"
mkdir -p "$QLEVER_DIR/data"
cp "$PAJES_DIR/refmodule-0.0.1.ttl" \
   "$PAJES_DIR/refmodule-patch.ttl" \
   "$PAJES_DIR/client-use.ttl" \
   "$PAJES_DIR/owl/pajes.owl" \
   "$QLEVER_DIR/data/"
cd "$QLEVER_DIR"

qlever index \
  --name pajes \
  --format ttl \
  --input-files 'data/refmodule-0.0.1.ttl data/refmodule-patch.ttl data/client-use.ttl data/pajes.owl' \
  --multi-input-json '[{"cmd":"cat data/refmodule-0.0.1.ttl","format":"ttl","graph":"-","parallel":"false"},{"cmd":"cat data/refmodule-patch.ttl","format":"ttl","graph":"-","parallel":"false"},{"cmd":"cat data/client-use.ttl","format":"ttl","graph":"-","parallel":"false"},{"cmd":"cat data/pajes.owl","format":"ttl","graph":"-","parallel":"false"}]' \
  --system native

qlever start \
  --name pajes \
  --description 'Pajes RDF data' \
  --port 7001 \
  --system native
```

## run SPARQL queries on the command line

example of command:

```sh
qlever query --sparql-endpoint http://localhost:7001 \
  'SELECT (COUNT(*) AS ?triples) WHERE { ?s ?p ?o }'
```

## run SPARQL queries using the docker frontend

In case a local docker environment is available:

```sh
qlever % cat Qleverfile-ui.yml 
config:
  backend:
    name: pajes
    slug: default
    sortKey: 1
    baseUrl: http://localhost:7001
    apiToken: ''
    ... rest of the file omitted
```    

```sh
qlever ui --name pajes --ui-config-file Qleverfile-ui.yml
```
then point your browser to the local URL: http://localhost:8176/default
