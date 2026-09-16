# KMR Scripting

JetBrains IDE plugin for the PascalScript dialect used to script maps in
[Knights and Merchants Remake](https://www.kamremake.com/) (KMR). Works in any IntelliJ-based IDE
(IntelliJ IDEA, PhpStorm, WebStorm, PyCharm, ...) from build 232 (2023.2) on.

**Status: beta.** The plugin is used on real map scripts, but expect rough edges. Please report
anything the game accepts and the plugin flags (or the other way round).

## Features

- Syntax highlighting (configurable under *Settings | Editor | Color Scheme | PascalScript*), brace
  matching for `begin`/`end`, `repeat`/`until` and `{$IFDEF}`/`{$ENDIF}`, commenting, folding,
  breadcrumbs, structure view, Go to Symbol.
- **The game's model of a script.** A map script (`<Map>.script` next to `<Map>.dat` / `<Map>.map`)
  plus everything it pulls in with `{$I}` / `{$INCLUDE}` forms one compilation unit. Shared scripts
  show a banner to pick the map they are analysed in. `{$DEFINE}` / `{$IFDEF}` / `{$IFNDEF}` /
  `{$ELSE}` / `{$ENDIF}` are evaluated like the game's preprocessor; excluded code is dimmed.
- **The KaM Remake scripting API** (`Actions`, `States`, `Utils`, types, events) as navigable,
  documented stubs generated from the [official wiki](https://github.com/reyandme/kam_remake/wiki),
  plus PascalScript's standard routines (`Inc`, `Length`, `IntToStr`, ...) exactly as the game's
  compiler defines them. Ctrl+click `Actions.ShowMsg` to open its declaration.
- **Game version** per project and per map (`r16020`, `r6720`), switchable from the status bar or under
  *Settings | Languages & Frameworks | KMR PascalScript*. Members missing in the selected version are
  reported, with a quick fix to switch.
- Code completion (API members, your own declarations, keywords, event handler templates when you type
  `On...` at file level), parameter info (Ctrl+P), quick documentation (Ctrl+Q), parameter name
  hints in calls (`ShowMsg(aHand: 0, aText: 'Hi')`, hidden when the argument is named like the parameter).
- Completion inside directives: `{$` offers every directive the game knows (`{$I}`, `{$INCLUDE}`,
  `{$DEFINE}`, `{$UNDEF}`, `{$IFDEF}`, `{$IFNDEF}`, `{$ELSE}`, `{$ENDIF}`, `{$EVENT}`, `{$COMMAND}` /
  `{$CMD}`, `{$CUSTOM_TH_TROOP_COST}`, `{$CUSTOM_MARKET_GOLD_PRICE_X}`), `{$EVENT ` the events of the
  selected game version, and `{$EVENT evtHouseBuilt:` the procedures of the unit - those with the
  event's signature first, without the ones that already handle an event.
- Go to declaration, find usages and rename (case-insensitive, across includes).
- **Inspections:** unresolved identifiers and members, deprecated members and event handlers (with a fix that migrates exact
  integer-id calls to the `Ex` variants), wrong argument counts,
  duplicate declarations across a unit, unused locals, wrong event handler signatures (with fix),
  include and conditional directive problems, an `{$EVENT}` naming a procedure the unit does not declare
  (with a fix that creates it) or one that is already a handler of an event, missing include guards (with fix), invalid statements,
  statements without a `;` after them (the game accepts that, so it is a warning with a fix),
  `X := X + 1` that can be `Inc(X)` (with fix),
  type mismatches, and *kinds*: a house ID passed as a unit ID, swapped X/Y, arithmetic on IDs, a
  literal where an ID is expected, comparisons between different kinds.
- **Known values.** The plugin follows what conditions and assignments say about variables, record fields,
  constant-indexed elements and `States` queries through a routine, the way the PHP plugin does: inside
  `if A = True then`, a nested `if A = False then` is reported as always false; after `if not Ok then exit;`,
  `Ok` is known to be true; `if Found then` right after `Found := False` is always false; `X < 3` inside
  `if X > 5` can never hold. Hover a variable (or quick documentation, Ctrl+Q) to see the value known at
  that point and where it comes from. Facts are forgotten where a write, a loop or a call with unknown
  effects may change them.
- **States queries are stable within a tick.** A script runs to completion inside one game tick and only
  `Actions` change the game, so `States.UnitOwner(U)` returns the same value until an `Actions` member (or
  a routine of the script, which might call one) runs or `U` changes. A second `if States.UnitOwner(U) = 3`
  is therefore "always true", and a repeated query is a weak warning with an *Extract to variable* fix that
  stores the first result in a local. `KaMRandom` and friends are excluded; `Utils` and the standard
  routines are pure functions of their arguments.
- **Refactorings:** Introduce Variable (Ctrl+Alt+V) with occurrence replacement and in-place naming,
  wrapping a one-liner branch into `begin`/`end` when needed; Introduce Constant (Ctrl+Alt+C) into the
  `const` block before the declaration; Extract Method (Ctrl+Alt+M) turns selected statements into a
  procedure (locals read become parameters, locals changed and used afterwards `var` parameters, locals
  only the selection uses move along) or an expression into a function.
- Reformat Code with a Code Style page, live templates (`proc`, `func`, `if`, `ifb`, `ife`, `for`,
  `while`, `repeat`, `case`, `guard`, `inc`, `msg`, `tick`), *New | KMR Script*.

## Doc comments and tags

A `//` run or a `{ }` block directly above a declaration is its documentation (shown in Ctrl+Q and
rendered in Reader Mode). A few tags at the start of a line have meaning for the plugin:

| Tag | Meaning |
|-----|---------|
| `@deprecated <text>` | Uses are struck out and reported. |
| `@param <name> <text>`, `@return <text>`, `@see <text>` | Documentation sections. |
| `@kind <label>` | The integer variable, constant, field, or function result is a unit ID, house ID, group ID, hand index, X/Y coordinate, ... (`unitId`, `houseId`, `groupId`, `handIndex`, `xCoordinate`, `yCoordinate`, `tickCount`, or any word of your own). |
| `@kind <param> <label>` | Same for one parameter of the routine below. `unitId\|houseId` allows either. |
| `@kind none` | Switch kind inference off for that declaration. |
| `{@kind <label>}` above an assignment | The assigned value is of that kind (`{@kind houseId}` above `H := Data[I];`). The same comment directly in front of an argument or operand names that expression's kind; `{@kind none}` makes it unknown. |

Kinds are also inferred from what is assigned to a variable or passed to a routine, so existing
scripts get most of the checks without any tags.

## Development

Requirements: JDK 17. Gradle is provided by the wrapper.

```
./gradlew build          # generate lexer/parser, compile, run tests, build the plugin zip
./gradlew runIde         # start a sandbox IDE with the plugin
./gradlew test
```

The lexer (`KmrPascal.flex`) and grammar (`KmrPascal.bnf`) are compiled into `src/main/gen` on every
build; that directory is not committed.

### API stubs

`src/main/resources/stubs/<version>/*.script` are generated by `tools/generate_stubs.py` (Python 3,
standard library only) from the wiki pages cached in `tools/wiki-cache/`:

```
python3 tools/generate_stubs.py            # refresh the cache from GitHub and regenerate
python3 tools/generate_stubs.py --offline  # regenerate from the cache
```

Corrections that the wiki gets wrong for a version live in `tools/overrides/<version>/` (see the
README there); the mapping of parameter names to kinds lives in `tools/kinds.txt`.
`stubs/system/System.script` is hand-written.

## License

[MIT](LICENSE)
