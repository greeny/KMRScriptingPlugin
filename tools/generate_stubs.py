#!/usr/bin/env python3
"""
Generates the API stub scripts (src/main/resources/stubs/<version>/*.script) from the KaM Remake wiki.

Sources (raw GitHub wiki markdown, cached in tools/wiki-cache/):
  Actions-(Mission-Script-Dynamic), States-..., Utils-..., Events-..., Types-...

Output per version: Actions.script, States.script, Utils.script (records of procedural-type fields + object
variables), Types.script (enums, records, arrays, sets), Events.script (TKMScriptEvents record, not in user scope).
Everything is plain PascalScript; doc comments are "//" lines directly above a declaration with tags
@since / @param / @return / @deprecated / @hidden / @event / @kind / @prefer.

Kinds: integers that mean something specific (unit ID, hand index, X coordinate, ...) keep their wiki type and get
"@kind <param> <kind>" / "@kind <kind>" doc tags, the same tags users can write in their own scripts. The kinds
themselves are declared as hidden alias types in Types.script (TKMUnitID = Integer with "@kind id unit ID").
tools/kinds.txt lists the aliases and maps parameter names, record fields and result comments to them; the mapping is
applied to generated members, events, record fields and to override entries alike.

Doc comments are block comments ({ ... } with the text indented one tab deeper); indentation uses tabs.

A member is emitted for a version when its wiki Version (e.g. "7000+", "14600") is <= the version's revision;
the newest version (r16020, no maximum) takes everything. Members marked "-" (not implemented) are skipped. The wiki only documents *current*
signatures, so older versions may need hand-written corrections: tools/overrides/<version>/<File>.script holds
complete replacement entries (comment + declaration, separated by blank lines) matched by name; an entry whose
comment contains "@remove" deletes the member.

Usage: python3 tools/generate_stubs.py [--offline] [--out DIR]
"""
import argparse
import html
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, "tools", "wiki-cache")
OVERRIDES = os.path.join(ROOT, "tools", "overrides")
KINDS_FILE = os.path.join(ROOT, "tools", "kinds.txt")
DEFAULT_OUT = os.path.join(ROOT, "src", "main", "resources", "stubs")
WIKI = "https://raw.githubusercontent.com/wiki/reyandme/kam_remake/{page}-(Mission-Script-Dynamic).md"

# version name -> max revision (None = everything)
# "r16020" is the current build the stubs are generated for and takes everything the wiki lists
VERSIONS = {"r16020": None, "r6720": 6720}

# page -> (record type, variable names)
OBJECTS = {
    "Actions": ("TKMScriptActions", ["Actions", "A"], "tells the game what to do"),
    "States": ("TKMScriptStates", ["States", "S"], "queries the game state"),
    "Utils": ("TKMScriptUtils", ["Utils", "U"], "helper functions"),
}

ROW = re.compile(r'^\| ([^|]*) \| <a id="([^"]+)">')
PARAM = re.compile(r'^\*\*([^*]+)\*\*:\s*([^;/]+?)\s*;?\s*(?://\s*(.*?)\s*)?$')
RETURN = re.compile(r'^([A-Za-z][A-Za-z0-9_ ]*?)\s*(?://\s*(.*?)\s*)?$')


def fetch(page, offline):
    path = os.path.join(CACHE, page + ".md")
    if not offline:
        try:
            with urllib.request.urlopen(WIKI.format(page=page), timeout=30) as response:
                text = response.read().decode("utf-8")
            os.makedirs(CACHE, exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
            return text
        except Exception as e:  # noqa
            print(f"warning: could not download {page}: {e}; using cache", file=sys.stderr)
    with open(path, encoding="utf-8") as f:
        return f.read()


def clean(text):
    """HTML fragment -> plain text lines."""
    text = re.sub(r"<br\s*/?>", "\n", text)
    text = re.sub(r"<[^>]+>", "", text)
    text = html.unescape(text)
    lines = [line.strip() for line in text.split("\n")]
    return [line for line in lines if line]


def parse_version(text):
    """'7000+' -> 7000, '-' -> None (not implemented), '' -> 0."""
    text = text.strip()
    if text == "-":
        return None
    match = re.match(r"(\d+)", text)
    return int(match.group(1)) if match else 0


class Member:
    def __init__(self, name, version, description, params, returns):
        self.name, self.version, self.description, self.params, self.returns = name, version, description, params, returns


# ---------------------------------------------------------------------------------------------------------------------
# kinds (tools/kinds.txt)
# ---------------------------------------------------------------------------------------------------------------------

INTEGER_TYPES = {"integer", "byte", "word", "cardinal", "smallint", "shortint", "longint", "longword", "int64"}
INTEGER_ARRAY_TYPES = {"tintegerarray", "array of integer"}
MODIFIERS = ("var ", "out ", "const ")


class Kinds:
    def __init__(self):
        self.aliases = {}          # alias name -> {underlying, policy, label, prefer, description}
        self.params = {}           # lower-case "name" or "Qualifier.name" -> alias
        self.returns = {}          # lower-case "Page.Member" -> alias
        self.return_comments = {}  # lower-case wiki return comment -> alias

    @staticmethod
    def load(path):
        kinds = Kinds()
        with open(path, encoding="utf-8") as f:
            for number, raw in enumerate(f, 1):
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                directive, _, rest = line.partition(" ")
                where = f"{path}:{number}"
                if directive == "kind":
                    name, _, spec = rest.partition(":")
                    parts = [p.strip() for p in spec.split(";")]
                    prefer = None
                    if len(parts) > 3 and parts[3].startswith("prefer "):
                        prefer = parts.pop(3)[len("prefer "):].strip()
                    if len(parts) < 4:
                        raise SystemExit(f"{where}: expected 'kind Name: Underlying; policy; label; description'")
                    kinds.aliases[name.strip()] = {"underlying": parts[0], "policy": parts[1], "label": parts[2],
                                                   "prefer": prefer, "description": "; ".join(parts[3:])}
                elif directive in ("param", "return", "return-comment"):
                    keys, _, alias = rest.rpartition("=")
                    alias = alias.strip()
                    if alias not in kinds.aliases:
                        raise SystemExit(f"{where}: unknown alias '{alias}'")
                    if directive == "return-comment":
                        kinds.return_comments[keys.strip().lower()] = alias
                    else:
                        target = kinds.params if directive == "param" else kinds.returns
                        for key in keys.split():
                            target[key.lower()] = alias
                else:
                    raise SystemExit(f"{where}: unknown directive '{directive}'")
        return kinds

    def param_alias(self, qualifier, name, type_):
        """Alias for an integer parameter / record field, by qualified then by bare name; None to keep the type."""
        if type_.strip().lower() not in INTEGER_TYPES:
            return None
        return self.params.get(f"{qualifier}.{name}".lower()) or self.params.get(name.lower())

    def return_alias(self, page, member, type_, comment):
        alias = self.returns.get(f"{page}.{member}".lower())
        if alias is None and comment:
            alias = self.return_comments.get(comment.strip().lower())
        if alias is None:
            return None
        return alias if type_.strip().lower() in INTEGER_TYPES | INTEGER_ARRAY_TYPES else None

    def tag_word(self, alias):
        """The word users write after @kind: "unit ID" -> unitId, "X coordinate" -> xCoordinate."""
        words = self.aliases[alias]["label"].split()
        return words[0].lower() + "".join(w[:1].upper() + w[1:].lower() for w in words[1:])


KINDS = None  # set in main()


def bare_name(name):
    """'out aX' -> 'aX' (the first name of a parameter group carries the modifier)."""
    for candidate in MODIFIERS:
        if name.startswith(candidate):
            return name[len(candidate):].strip()
    return name


def kind_tags(qualifier, params, returns):
    """@kind lines for the kinded parameters and result of a member ("@kind aUnitID unitId", "@kind unitId")."""
    tags = []
    for names, type_, _comment in params:
        for name in names:
            alias = KINDS.param_alias(qualifier, bare_name(name), type_)
            if alias:
                tags.append(f"@kind {bare_name(name)} {KINDS.tag_word(alias)}")
    if returns:
        page, _, member = qualifier.partition(".")
        alias = KINDS.return_alias(page, member, returns[0], returns[1])
        if alias:
            tags.append(f"@kind {KINDS.tag_word(alias)}")
    return tags


def render_kind_types():
    out = []
    for name, alias in KINDS.aliases.items():
        lines = [alias["description"], "@hidden", f"@kind {alias['policy']} {alias['label']}"]
        if alias["prefer"]:
            lines.append(f"@prefer {alias['prefer']}")
        out.append(doc(lines, "\t") + f"\t{name} = {alias['underlying']};\n\n")
    return "".join(out)


def parse_members(md, has_returns):
    members = []
    for line in md.split("\n"):
        match = ROW.match(line)
        if not match:
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        version = parse_version(cells[0])
        name = match.group(2)
        description = clean(cells[1])
        if description and description[0] == name:
            description = description[1:]
        params = []
        for raw in re.sub(r"</?sub>", "", cells[2]).split("<br/>"):
            raw = raw.strip()
            if not raw:
                continue
            pm = PARAM.match(raw)
            if not pm:
                raise SystemExit(f"cannot parse parameter '{raw}' of {name}")
            names = [n.strip() for n in pm.group(1).split(",")]
            params.append((names, pm.group(2).strip(), (pm.group(3) or "").strip("_ ")))
        returns = None
        if has_returns and len(cells) > 3:
            raw = re.sub(r"</?sub>", "", cells[3]).strip()
            if raw:
                rm = RETURN.match(raw)
                if not rm:
                    raise SystemExit(f"cannot parse return '{raw}' of {name}")
                returns = (rm.group(1).strip(), (rm.group(2) or "").strip("_ "))
        members.append(Member(name, version, description, params, returns))
    return members


class TypeDef:
    def __init__(self, name, kind, description, elements):
        self.name, self.kind, self.description, self.elements = name, kind, description, elements


TYPE_ROW = re.compile(r'^\| ([^|]*) \| <sub>(\w+)</sub> \| <a id="([^"]+)">')


def parse_types(md):
    types = []
    for line in md.split("\n"):
        match = TYPE_ROW.match(line)
        if not match:
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        kind = match.group(2)
        name = match.group(3)
        description = clean(cells[2])
        if description and description[0] == name:
            description = description[1:]
        elements = []
        for raw in re.sub(r"</?sub>", "", cells[3]).split("<br/>"):
            raw = raw.strip()
            if not raw:
                continue
            em = re.match(r"^\*\*([^*]+)\*\*\s*(?://\s*(.*?)\s*)?$", raw)
            if not em:
                raise SystemExit(f"cannot parse element '{raw}' of {name}")
            elements.append((em.group(1).strip(), (em.group(2) or "").strip()))
        types.append(TypeDef(name, kind, description, elements))
    return types


# ---------------------------------------------------------------------------------------------------------------------
# rendering
# ---------------------------------------------------------------------------------------------------------------------

def doc(lines, indent):
    """A { ... } block comment: braces on their own lines, the text one tab deeper. Braces inside the text would end
    the comment early, so they become parentheses."""
    body = "".join(f"{indent}\t{line.replace('{', '(').replace('}', ')')}\n" for line in lines)
    return f"{indent}{{\n{body}{indent}}}\n"


def member_doc(member, page, names=()):
    lines = list(member.description) or [member.name]
    if member.version:
        lines.append(f"@since {member.version}")
    if (member.name + "Ex").lower() in names:
        # a newer variant taking TKMUnitType-style enums exists: the integer-id one is legacy
        lines.append(f"@deprecated Use {member.name}Ex instead")
    for names, _type, comment in member.params:
        # the wiki documents only some parameters; the others get a bare @param so the list is complete
        lines.append(f"@param {', '.join(bare_name(n) for n in names)}" + (f" {comment}" if comment else ""))
    if member.returns and member.returns[1]:
        lines.append(f"@return {member.returns[1]}")
    lines.extend(kind_tags(f"{page}.{member.name}", member.params, member.returns))
    return lines


def signature(member):
    params = "; ".join(f"{', '.join(names)}: {type_}" for names, type_, _ in member.params)
    params = f"({params})" if params else ""
    if member.returns:
        return f"function{params}: {member.returns[0]}"
    return f"procedure{params}"


def render_object(page, members, version_name):
    record, variables, purpose = OBJECTS[page]
    out = [header(page, version_name), "type\n", doc([f"{record}: type of the {page} object.", "@hidden"], "\t"), f"\t{record} = record\n"]
    names = {m.name.lower() for m in members}
    for member in members:
        out.append(doc(member_doc(member, page, names), "\t\t"))
        out.append(f"\t\t{member.name}: {signature(member)};\n\n")
    out.append("\tend;\n\nvar\n")
    out.append(doc([f"The {page} object: {purpose}."], "\t") + f"\t{variables[0]}: {record};\n")
    for alias in variables[1:]:
        out.append(doc([f"Short alias of {variables[0]}."], "\t") + f"\t{alias}: {record};\n")
    return "".join(out)


def render_events(members, version_name):
    out = [header("Events", version_name),
           doc(["This file is NOT part of the user's scope. Each field describes one event: the field name is the conventional",
                "handler procedure name, its type is the required handler signature, @event the name used in the $EVENT directive."], ""),
           "type\n\tTKMScriptEvents = record\n"]
    names = {m.name.lower() for m in members}
    for member in members:
        lines = member_doc(member, "Events", names)
        event_name = "evt" + member.name[2:] if member.name.lower().startswith("on") else member.name
        lines.insert(len(member.description) or 1, f"@event {event_name}")
        out.append(doc(lines, "\t\t"))
        out.append(f"\t\t{member.name}: {signature(member)};\n\n")
    out.append("\tend;\n")
    return "".join(out)


def render_types(types, version_name):
    out = [header("Types", version_name), "type\n", render_kind_types()]
    for t in types:
        out.append(doc(t.description or [t.name], "\t"))
        if t.kind == "enum":
            values = []
            for index, (value, comment) in enumerate(t.elements):
                separator = "," if index < len(t.elements) - 1 else ""
                values.append(f"\t\t{value}{separator}" + (f"  // {comment}" if comment else ""))
            out.append(f"\t{t.name} = (\n" + "\n".join(values) + "\n\t);\n\n")
        elif t.kind == "record":
            out.append(f"\t{t.name} = record\n")
            for field, comment in t.elements:
                names, colon, type_ = field.rpartition(":")
                lines = ([comment] if comment else [])
                if colon:
                    names = [n.strip() for n in names.split(",")]
                    lines += kind_tags(t.name, [(names, type_.strip(), "")], None)
                    field = f"{', '.join(names)}: {type_.strip()}"
                if lines:
                    out.append(doc(lines, "\t\t"))
                out.append(f"\t\t{field};\n")
            out.append("\tend;\n\n")
        else:  # array, set: single element "array of X" / "set of X"
            out.append(f"\t{t.name} = {t.elements[0][0]};\n\n")
    return "".join(out)


def header(page, version_name):
    return doc([f"KaM Remake scripting API stubs - {page} ({version_name}).",
                f"GENERATED by tools/generate_stubs.py from the wiki page {page}-(Mission-Script-Dynamic); do not edit,",
                f"put corrections into tools/overrides/{version_name}/{page}.script instead."], "") + "\n"


# ---------------------------------------------------------------------------------------------------------------------
# overrides: "comment + declaration" entries separated by blank lines, matched by declared name
# ---------------------------------------------------------------------------------------------------------------------

def load_overrides(version_name, page):
    path = os.path.join(OVERRIDES, version_name, page + ".script")
    if not os.path.exists(path):
        return {}
    entries = {}
    with open(path, encoding="utf-8") as f:
        blocks = re.split(r"\n\s*\n", f.read().strip())
    for block in blocks:
        block = block.strip("\n")
        if not block.strip():
            continue
        code = [line for line in block.split("\n") if not line.strip().startswith("//")]
        if not code:
            continue
        nm = re.match(r"\s*([A-Za-z_][A-Za-z0-9_]*)\s*[:=]", code[0])
        if not nm:
            raise SystemExit(f"override entry without a name in {path}: {code[0]}")
        entries[nm.group(1).lower()] = (page, block)
    return entries


OVERRIDE_DECLARATION = re.compile(r"^\s*([A-Za-z_]\w*): (function|procedure)(?:\(([^)]*)\))?(?:: (\w+))?;\s*$")


def render_override(entry, indent):
    """An override entry ("//" comment lines + declaration) as a block comment plus the declaration, with the
    @kind tags of its parameters and result added like for generated members."""
    page, block = entry
    lines = block.split("\n")
    comment = [line.strip()[2:].strip() for line in lines if line.strip().startswith("//")]
    code = [line for line in lines if not line.strip().startswith("//")]
    match = OVERRIDE_DECLARATION.match(code[0]) if len(code) == 1 else None
    if match and page != "Types":
        name, _kind, params, returns = match.groups()
        parsed = []
        for raw in (params or "").split(";"):
            raw = raw.strip()
            if raw:
                names, _, type_ = raw.rpartition(":")
                parsed.append(([n.strip() for n in names.split(",")], type_.strip(), ""))
        return_comment = next((c[len("@return"):].strip() for c in comment if c.startswith("@return")), "")
        comment += kind_tags(f"{page}.{name}", parsed, (returns, return_comment) if returns else None)
    text = (doc(comment, indent) if comment else "") + reindent("\n".join(code), indent)
    return text


def apply_overrides(text, entries, indent):
    """Replace or append generated entries (block comment + declaration) by name."""
    if not entries:
        return text
    remaining = dict(entries)
    result = []
    lines = text.split("\n")
    i = 0
    while i < len(lines):
        # an entry is an optional block comment at the entry indent followed by "Name: ..." / "Name = ..."
        j = i
        if lines[j] == indent + "{":
            while j < len(lines) and lines[j] != indent + "}":
                j += 1
            j += 1
        nm = re.match(re.escape(indent) + r"([A-Za-z_][A-Za-z0-9_]*)\s*[:=]", lines[j]) if j < len(lines) else None
        if nm and nm.group(1).lower() in remaining:
            entry = remaining.pop(nm.group(1).lower())
            # skip the generated entry (multi-line records/enums end at a line ending with ';' at entry indent)
            k = j
            while k < len(lines) and not (lines[k].rstrip().endswith(";") and (k == j or lines[k].startswith(indent) and not lines[k].startswith(indent + "\t"))):
                k += 1
            if "@remove" not in entry[1]:
                result.extend(render_override(entry, indent).split("\n"))
                result.append("")
            i = k + 1
            continue
        result.append(lines[i])
        i += 1
    # append new entries before the closing "end;" of a record or at the end of the type section
    extra = [render_override(entry, indent) + "\n" for entry in remaining.values() if "@remove" not in entry[1]]
    if extra:
        text = "\n".join(result)
        anchor = text.rfind("\n" + indent[:-1] + "end;") if len(indent) >= 2 else -1
        if anchor >= 0:
            return text[:anchor] + "\n" + "\n".join(extra) + text[anchor:]
        return text + "\n".join(extra)
    return "\n".join(result)


def reindent(block, indent):
    lines = [line.rstrip() for line in block.split("\n")]
    strip = min((len(line) - len(line.lstrip()) for line in lines if line.strip()), default=0)
    return "\n".join((indent + line[strip:]) if line.strip() else "" for line in lines)


# ---------------------------------------------------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--offline", action="store_true", help="use tools/wiki-cache only")
    parser.add_argument("--out", default=DEFAULT_OUT)
    args = parser.parse_args()

    global KINDS
    KINDS = Kinds.load(KINDS_FILE)

    pages = {page: fetch(page, args.offline) for page in ["Actions", "States", "Utils", "Events", "Types"]}
    members = {page: parse_members(pages[page], has_returns=True) for page in OBJECTS}
    events = parse_members(pages["Events"], has_returns=False)
    types = parse_types(pages["Types"])

    for version_name, max_revision in VERSIONS.items():
        out_dir = os.path.join(args.out, version_name)
        os.makedirs(out_dir, exist_ok=True)

        def keep(member):
            return member.version is not None and (max_revision is None or member.version <= max_revision)

        for page in OBJECTS:
            selected = [m for m in members[page] if keep(m)]
            target = os.path.join(out_dir, page + ".script")
            if not selected and not load_overrides(version_name, page):
                # the object did not exist in that version (e.g. Utils before r7000): no file, no variables
                if os.path.exists(target):
                    os.remove(target)
                print(f"{version_name}/{page}.script: not present in this version")
                continue
            text = apply_overrides(render_object(page, selected, version_name), load_overrides(version_name, page), "\t\t")
            write(target, text)
            print(f"{version_name}/{page}.script: {len(selected)} members")
        selected_events = [e for e in events if keep(e)]
        write(os.path.join(out_dir, "Events.script"), apply_overrides(render_events(selected_events, version_name), load_overrides(version_name, "Events"), "\t\t"))
        print(f"{version_name}/Events.script: {len(selected_events)} events")
        # the Types page has no version information: all types go into every version (fix per version via overrides)
        write(os.path.join(out_dir, "Types.script"), apply_overrides(render_types(types, version_name), load_overrides(version_name, "Types"), "\t"))
        print(f"{version_name}/Types.script: {len(types)} types")


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text.rstrip("\n") + "\n")


if __name__ == "__main__":
    main()
