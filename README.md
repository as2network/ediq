# ediq
Command line XPath for EDI files

## Building

```bash
mvn package
```

## How to Use

```bash
java -jar ./target/ediq.jar [OPTIONS]... [EDIFILE]
```

## Options

- `--epath`: "EPath" expression (same as XPath) to use for selection of nodes in the EDI input
- `--format`: write each segment to the output on a separate line (optional)
- `--schema-file`: path to a schema file to use when parsing the EDI input

## License

Apache-2.0
