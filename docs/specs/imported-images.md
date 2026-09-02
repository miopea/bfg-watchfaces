# Imported images

Scoped 2026-09-02, from `launch-scope.md` §3.3. A launch gate: photo dials are
table stakes in this category, and "90% of the goals" includes this one.

## 0. What already exists, and what is actually missing

More than half of this is built, in `:workbench`, and has been since before the
phone app had a Studio screen.

| Piece | Where | State |
| --- | --- | --- |
| `Engine.TEXTURE` | `:generator` | exists, in the file format |
| `DialParams.texture` (an id, not bytes) | `:generator` | exists |
| `isLocalOnly` | `:generator` | exists |
| `TextureStore` — save, load, sha1 id | `:workbench` | **works, wrong module** |
| `drawTexture` — cover-crop, contrast fade | `:workbench` | works |
| `render(p, size, texture)` | `:workbench` | works |
| A picker | `:mobile` | **missing** |
| Texture storage on the phone | `:mobile` | **missing** |
| `AndroidDialRenderer` accepting an image | `:mobile` | **missing** |
| `FaceBuilder` baking it into `dial_bg.png` | `:mobile` | **missing** |
| The engine offered in the picker | `:mobile` | deliberately hidden |
| Share hidden on a local-only face | `:mobile` | **missing** |

**The gap is entirely on the phone.** The rendering question was answered years
of decisions ago; nothing about the look needs designing.

`Engine.TEXTURE` is currently in `Presentation.UNOFFERED` and correctly so — a
chip that draws a plain dial is the silent-failure shape this project keeps
naming. That entry comes out as the last step, not the first.

## 1. Where a photo lives in the app

**In the pattern list, as one more style.** It is not a separate mode or a
different kind of face: a dial is either a pattern, a generated surface, or a
photo, and all three are `Engine` values already. Everything else about the face
— hands, complications, colours, the ring — works exactly the same.

## 2. Decisions

### Android photo picker, no permission at all

`PickVisualMedia` returns the single image somebody chose and can see nothing
else. **No permission is requested, so there is no prompt, no denial path to
build, and nothing about photo access to declare on the Data Safety form.**

`READ_MEDIA_IMAGES` was rejected: it asks for the entire library in order to use
one picture.

### Baked into `dial_bg.png`, on the path that already exists

The photo becomes the dial image at build time — cover-cropped, faded, quantized
to 64 colours like every other dial. Nothing new crosses to the watch and a face
costs what it already cost.

The cost, accepted: **changing the photo means sending the face again.** That is
inherent to the delivery model, not to this feature.

### Copied into app storage, keyed by the texture id

The face JSON stores an id, never content — that is already the design. The
chosen image is copied into the app's own files so **the face survives the
original being deleted from the gallery**, which is what happens to photos.

A `content://` URI was rejected: it can be revoked, the photo can be deleted, and
the face would then render as a blank dial with no explanation — a face that
breaks later without being touched.

### `contrast` protects the time

Already built and already documented: `contrast` fades an imported image toward
the dial colour so the time stays readable. A photo arrives at a default pushed
back far enough to read over, and people can bring it forward.

No automatic scrim behind the time — it looks like a UI element pasted on a
photograph, which is what makes photo faces look cheap. No sampling the image to
pick the ink automatically — a face that changes its own colours reads as a bug.

### Local only, said once and plainly

`isLocalOnly` already returns true for a TEXTURE face with an image, and the
catalog stores parameters rather than bytes. **Share is hidden on such a face,
with one line saying the photo stays on the phone.**

Hosting user photos is explicitly out of scope: it is image hosting, storage
cost, and moderating arbitrary photographs with a queue of one person.

## 3. Where the code goes

`TextureStore` works but lives in `:workbench`, which is never shipped. The rule
about what a texture id IS belongs with the rule about what a face is.

- **`:appcore`** gets the store: id from bytes, the filename, save and load as
  files. Pure JVM, so it runs on the phone and in tests without an emulator —
  the same arrangement `FaceLibrary` already has.
- **Each platform decodes.** `ImageIO` on the desktop, `BitmapFactory` on
  Android. That is the `PatternEngines` split: one definition, two executions.

The workbench keeps working through the moved store rather than its own copy.

## 4. Order of work

1. `TextureStore` moves to `:appcore` with tests; `:workbench` uses it.
2. `AndroidDialRenderer.render` accepts an optional bitmap and draws it, mirroring
   `drawTexture` including the contrast fade.
3. `AndroidFacePreview` and `FaceBuilder` resolve and pass the image, so the
   preview and the shipped `dial_bg.png` agree.
4. The picker in Studio: `PickVisualMedia`, downscale, save, set `texture`.
5. Share hidden on a local-only face, with the reason.
6. `Engine.TEXTURE` leaves `Presentation.UNOFFERED` — **last**, so the chip never
   exists before the feature behind it does.

## 5. Limits, decided rather than discovered

- **Downscale on import.** A 12MP photo is pointless for a 456×456 dial and slow
  to decode. Cover-crop to the dial and store that.
- **The store is not a gallery.** No management screen at launch. An image is
  referenced by the face that uses it; orphans are a later problem and a small
  one.
- **One image per face.** `texture` is a single id.

## 6. What would make this wrong

If a photo face ever reaches the catalog, `isLocalOnly` has failed and somebody's
photograph has been published. That is the one failure here worth a test rather
than a review, and it belongs on the SUBMIT path, not the UI — hiding a button is
a courtesy, and refusing the submission is the guarantee.
