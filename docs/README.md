# BFG Watch Faces Documentation

Documentation index for the watch face generator. The app has no default face:
it ships styles, and people name what they make. See
[`../DECISIONS.md`](../DECISIONS.md) 2026-08-26 and 2026-08-27 for why that
distinction is load-bearing.

## Reading paths

### New here, want to change a pattern or a layout

1. [`SPEC.md`](SPEC.md) — architecture and the constraints you cannot relax
2. [`../CLAUDE.md`](../CLAUDE.md) — ground rules, commands, current state of play
3. Run `./gradlew :workbench:workbench` and open <http://localhost:7777>

Almost all design work happens in the workbench. You do not need an Android SDK,
an emulator, or a watch to change geometry, colour, layout or ambient behaviour.

### Trying to get a face onto a watch

1. [`SPEC.md`](SPEC.md) § Build order — step one gates everything after it
2. [`SPEC.md`](SPEC.md) § Watch Face Push — the packaging rules that reject an APK
3. `scripts/setup-emulators.sh`, then `scripts/deploy.sh`

### Wondering why something is the way it is

[`../DECISIONS.md`](../DECISIONS.md) — dated, with the reasoning and what was
rejected. Read it before proposing a change to the file format, the build, or
the renderer; several obvious-looking improvements are recorded there as already
tried and rejected for reasons that are not visible from the code.

## Documentation structure

```text
docs/
├── README.md      # this index
└── SPEC.md        # architecture, constraints, build order

../CLAUDE.md       # AI entry point: rules, commands, state of play
../DECISIONS.md    # dated decision record (see note below)
../README.md       # human entry point: quick start
../CONTRIBUTING.md # contribution guide
```

### Note on the decision record

This repo keeps decisions in a single dated [`../DECISIONS.md`](../DECISIONS.md)
rather than the `docs/architecture/adr/ADR-XXX-*.md` layout the fleet doc
standard recommends. That is a deliberate deviation for a repo this size: the
decisions are tightly coupled to one another and read better as a single
narrative than as numbered files. If this repo grows a second major subsystem,
splitting into ADRs is the right move.

## Quick reference

### Commands

| Command | What |
| --- | --- |
| `scripts/bootstrap.sh` | Fetch WFF schemas, XSD 1.1 jars, Gradle wrapper |
| `./gradlew :generator:test` | Engines, params, WFF emission |
| `./gradlew :workbench:test` | Rasterizer, quantizer, ambient budget, face store |
| `./gradlew :workbench:workbench` | The app at <http://localhost:7777> |
| `./gradlew :workbench:bake` | Generate `dial_bg.png`, `preview.png`, `watchface.xml` |
| `cd watchface-template && ./build.sh` | Validate, build and sign the APK |
| `make docs-check` | markdownlint + cspell over the docs |

### Constraints you will trip over

| Constraint | Consequence if ignored |
| --- | --- |
| Colours are `#AARRGGBB`, alpha first | Six-digit values are silently wrong, not rejected |
| Canvas is 456×456 | Wrong geometry on Pixel Watch 4 and 5 |
| Ambient is per-element `<Variant>` | A second scene is not how WFF does it |
| APK may contain only four path families | Watch Face Push rejects the package |
| Schema validity is not checked at runtime | Face installs, then never appears in the carousel |
| Never change engine geometry in place | Every stored community face silently re-renders |

The last two are the expensive ones. Both fail silently.

### Module boundaries

| Module | Depends on | Ships to a device |
| --- | --- | --- |
| `generator/` | nothing | via `phone/` |
| `workbench/` | `generator/` | no — dev tooling |
| `phone/`, `wear/` | `generator/` | yes (scaffolds only today) |

`generator/` is deliberately dependency-free: it is the definition of the stored
file format, so nothing may make it harder to test on a plain JVM.
