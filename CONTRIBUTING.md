# Contributing

Thanks for looking at this. The project is small and early — a real watch face
from this repo has not yet been confirmed on physical hardware, so the most
valuable contribution right now is anything that moves that forward.

## Getting set up

```bash
scripts/bootstrap.sh          # WFF schemas, XSD 1.1 jars, Gradle wrapper
./gradlew :generator:test     # 91 tests, no Android SDK or device
```

`generator/libs/` and the schema directories are fetched, not committed — they
are Google's and Apache's files and are gitignored deliberately.

On Windows, run the shell scripts from WSL or Git Bash.

## Work in `:generator` whenever you can

It is pure Kotlin/JVM with no Android dependency, so it tests in seconds without
an emulator. Geometry, parameters, and WFF emission all belong there. Reach for
`:phone` or `:wear` only when the task genuinely needs Android APIs.

## Four rules that are not style preferences

Each of these was found the expensive way. `docs/SPEC.md` has the full list;
these are the ones most likely to bite a first contribution.

**Never change engine geometry in place.** Community faces are stored as
parameters, not images, which makes `PatternEngines` the renderer for the stored
file format. Changing an engine's output silently rewrites every face anyone has
ever saved. Add a branch keyed on `generatorVersion` and leave the older branches
untouched. `GeneratorVersionTest` fails loudly if you bump the version.

**Never skip `WffSchemaTest`.** A schema-invalid watch face compiles, links,
signs, and installs — and then silently never appears in the carousel. There is
no runtime error and no log line. That test is the only signal you get.

**Do not add AGP to the watch face APK build.** Gradle injects `kotlin/` and
`DebugProbesKt.bin`. Play accepts them; Watch Face Push rejects them. This is why
`watchface-template/build.sh` calls aapt2 directly and asserts the APK contents
afterward.

**Colours in params are `#RRGGBB`.** The emitter converts to WFF's `#AARRGGBB` —
eight digits, alpha first. Six-digit values in emitted XML are silently wrong
rather than rejected. Do not store 8-digit colours in `DialParams`.

## Pull requests

- Run `./gradlew :generator:test` before pushing. CI runs the same thing.
- Keep the diff scoped to one thing.
- If you made a decision a future reader would otherwise have to re-derive,
  add a dated entry to `DECISIONS.md` with the reasoning **and what you
  rejected**. It is a decision log, not a changelog.

## Face submissions

The catalog is a separate public repository:
<https://github.com/miopea/bfg-watchfaces-catalog>, holding `faces/<slug>.json`
and a generated `index.json`. In production it is served from jsDelivr — not
`raw.githubusercontent.com`, which is rate limited and is not a CDN.

Clone it beside this repo and the workbench finds it automatically; otherwise set
`BFG_CATALOG_DIR`.

To submit a face:

1. Design it in the workbench and **Save to Gallery**.
2. In **My faces**, tap **Share**, give an attribution, and stage it.
3. Commit both files and open a pull request:

   ```bash
   ./gradlew :workbench:catalog          # revalidate + rewrite the index
   cd ../bfg-watchfaces-catalog
   git add faces/<slug>.json index.json
   git commit -m "catalog: add <slug>"
   ```

The catalog repo's CI runs this same validator on every PR. It fails if a face
does not parse, does not render, emits schema-invalid WFF, has a slug that
disagrees with its name or filename, exceeds 8KB, or if `index.json` is stale.

**This is a build gate, not review advice.** A reviewer cannot see that a face is
schema-invalid by reading the diff, and on a watch the failure is silence: it
installs, reports success, and never appears in the carousel.

### Parametric only

Submissions are parameters — a few KB of JSON, no uploaded rasters. Faces using
the `TEXTURE` engine are refused automatically.

That is not a size optimisation. You cannot encode a copyrighted logo as
"clous engine, scale 34, pewter", so the parametric constraint is what keeps the
shared catalog clear of other people's IP. Import your own photos locally; those
never enter the catalog, and the app tells you so where you pick the image.

### Attribution

The `author` field is whatever you want to be credited as, and it shows in the
gallery. Leave it blank and the face reads as unattributed.

## Scope

Watch Face Push is Wear OS 6+ only — Pixel Watch 4 and 5, recent Galaxy Watch.
Pixel Watch 1–3 cannot run this, and no amount of shimming changes that.

## License

By contributing you agree that your contributions are licensed under the
Apache License 2.0, the same as the rest of the project.
