# Stub overrides

`generate_stubs.py` writes `src/main/resources/stubs/<version>/*.script` from the wiki. Anything the wiki gets
wrong for a version (old signatures, missing docs, deprecations) goes here and is merged on every run:

- `overrides/<version>/<Actions|States|Utils|Events|Types>.script`
- entries are separated by blank lines; an entry is `//` doc-comment lines followed by one declaration
  (`Name: procedure(...);`, `TName = (...);`, `TName = record ... end;`); the generator renders the comment as a
  `{ }` block comment and adds the `@kind` tags from `tools/kinds.txt` for the declaration's parameters and result
- an entry replaces the generated member with the same name (case-insensitive) or is appended if new
- an entry whose comment contains `@remove` deletes the member
