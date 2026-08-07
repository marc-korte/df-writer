# Slate

A distraction-free Markdown writer for the Supernote Manta, built for a
Bluetooth keyboard and a device turned sideways in its folio.

It follows Typora's model: there is no preview pane and no split screen. You
type Markdown and it formats itself in place. The syntax markers vanish once the
caret leaves their line and reappear in grey when you move back onto it, so the
page reads like finished prose while you are still writing it.

**Download:** the signed APK is attached to the
[latest release](https://github.com/marc-korte/df-writer/releases/latest)
(684 KB). You can open that page in the Manta's own browser and download it
straight to the device.

---

## Why this one is not tiny on the Manta

Most sideloaded apps come out half-size on E Ink tablets, which is the usual
reason they are unusable. The cause is that Android sizes everything in `dp`,
which is derived from `densityDpi` — a number the ROM *reports*. E Ink ROMs
routinely report something unrelated to the actual glass. The Manta is a 300 PPI
panel; if Chauvet claims 160, then every `16dp` control is drawn 16 pixels tall,
about half the size its designer intended.

Slate never uses `dp`. Every dimension in the app — text, padding, panels,
buttons, rules — is a physical measurement in points or millimetres, converted
through a density derived from two independent signals, of which the larger
wins:

- what the display metrics claim, when the claim is plausible at all;
- what the panel's own pixel count implies, which no ROM can misreport.

Because it takes the larger, a lying density can only ever be corrected upward.
The worst case is text slightly too big, never text you cannot read. On a Manta
reporting a false 160 dpi, Slate still sizes everything for 300.

On top of that, **Ctrl =** and **Ctrl -** scale the entire interface, text and
panels together, and the setting is remembered. If it is still not right for
your eyes, two keystrokes fix it permanently.

## Other things done for E Ink specifically

- **No blinking caret.** The framework caret blinks twice a second, which on
  E Ink is a partial screen refresh twice a second, forever. Slate paints its
  own steady caret instead.
- **No animations, no ripples, no shadows.** All switched off in the theme
  rather than fought at runtime.
- **Pure black on pure white.** Greys are used only where they survive
  dithering.
- **Ctrl R flashes the panel** black and back, which is the only reliable way to
  make an E Ink controller do a full update and clear accumulated ghosting.
  There is also an automatic flash after a configurable number of edits, when
  you pause typing.
- **Rotation follows the device.** The Manta has a working accelerometer and
  auto-rotate, so Slate leaves rotation alone by default and turning the tablet
  turns the page. Pin it with **Ctrl Shift R** or the palette when you want it
  fixed in the folio-as-stand position.
- **The Bluetooth keyboard actually works.** The Manta ships exactly one IME,
  PinyinIME, and it consumes every hardware key it is offered — letters,
  Backspace, even bare function keys — without committing anything, so a
  Bluetooth keyboard looks dead in any app. Slate sets `FLAG_ALT_FOCUSABLE_IM`
  while a hardware keyboard is attached, which keeps the window focusable but
  stops it being an input-method target, so keys go straight to the editor.
  Note that neither declining the input connection nor hiding the IME is enough:
  Android decides this from window flags, in
  `WindowManager.LayoutParams.mayUseInputMethod`, not from anything the focused
  view does. The same flag keeps the on-screen keyboard from covering half the
  panel, and it is cleared automatically when no hardware keyboard is present.
- **Left- or right-handed.** The device's own handedness setting is not readable
  by third-party apps, so Slate has its own; controls sit on the side your
  writing hand is already on. Defaults to left-handed.
- **Not keyboard-only.** Tapping the status bar opens the command palette and
  tapping outside a panel closes it, so everything is reachable when the
  keyboard is not paired — which is exactly when you need it most.
- **A long piece looks after itself.** Android re-measures the whole text
  buffer on every change, so one enormous file gets slower to open and to type
  in the longer it grows — six seconds to open a hundred thousand words, on the
  device. A document that passes about twenty-five thousand words is therefore
  divided into parts in a folder of its own, without being asked, and you carry
  on in the part you were in with the caret where you left it. The cut falls on
  a blank line and on a chapter heading wherever the book allows one, never
  inside a paragraph; a piece with nowhere safe to cut is left whole. The
  original is kept beside the folder as a `.bak`, so nothing here can lose a
  manuscript. The contents drawer and the word count then span every part, so it
  still reads as one book. Every document remembers where its caret was, so
  moving between chapters — or coming back tomorrow — lands on the sentence
  being worked on rather than the top of the page. The threshold is a setting,
  and so is whether it happens at all, for a library synced somewhere that
  expects one file to stay one file.
- **Built for long pieces, not just notes.** The editor holds spans only for a
  window around the page rather than for the whole buffer, because every span
  operation on a buffer gets slower as its span count grows — which is what
  makes a naive Markdown editor bog down on a manuscript. Opening a document and
  toggling a mode cost the same on a hundred thousand words as on ten thousand,
  and an edit costs the edit rather than the document. The window widens as you
  scroll, just after the scroll rather than during it, and is rebuilt only on a
  jump. There is a benchmark in the test suite that fails if any of that stops
  being true.
- **Zero third-party dependencies.** No AndroidX, no Compose, no Play Services.
  Framework views start faster and repaint more predictably, and the APK is
  683 KB.

---

## Installing it on the Manta

**1. Turn on sideloading**

On the Manta: `Settings → Security & Privacy → Sideload`, and switch it on.

**2. Get the APK onto the device**

Open the [latest release](https://github.com/marc-korte/df-writer/releases/latest)
in the Manta's browser and download `Slate.apk`, or connect by USB and copy it
into any folder on the device.

**3. Install**

Open the Manta's file manager, tap the APK, confirm.

Alternatively, over USB with debugging enabled:

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

**4. First run**

Grant storage access when asked. Slate opens a welcome document that lists every
shortcut; delete it and start writing. Documents are ordinary `.md` files in
`/sdcard/Documents/Slate`, so the Manta's own file browser and a USB cable can
both reach them.

> Released APKs are signed with a self-signed key that is **not** in this
> repository, so that rebuilds install over the top of an existing copy. See
> `keystore.properties.example` to set up your own. Without one the build falls
> back to the debug key, which is fine for a first install but will not install
> over a copy signed with a different key.

---

## Keys

Everything is on the keyboard, because tapping a slow panel accurately is worse
than typing on the keyboard already in your hands. **Ctrl P** opens a command
palette listing every command with its shortcut, so none of this needs
memorising.

| | |
|---|---|
| **Ctrl P** | command palette |
| **Ctrl N / Ctrl O / Ctrl S** | new, open, save |
| **Ctrl Z / Ctrl Shift Z** | undo, redo |
| **Ctrl F / Ctrl H / Ctrl G** | find, replace, find next |
| **Ctrl T** | table of contents — a drawer that stays open |
| **Ctrl Shift O** | outline, type to jump to a heading |
| **Ctrl ,** | settings |
| **Ctrl = / Ctrl -** | interface bigger, smaller |
| **Ctrl B / Ctrl I / Ctrl E** | bold, italic, inline code |
| **Ctrl K** | link |
| **Ctrl 1**…**Ctrl 6**, **Ctrl 0** | heading levels, back to paragraph |
| **Ctrl Shift L / N / T** | bullet, numbered, task list |
| **Ctrl Shift Q / K / H / B** | blockquote, code block, rule, table |
| **Enter** in a list | next item; on an empty item, ends the list |
| **Tab / Shift Tab** | indent, outdent |
| **F8** | focus mode — dim every paragraph but this one |
| **F9** | typewriter mode — hold the caret at mid-screen |
| **Ctrl /** | show the raw Markdown |
| **Ctrl R** | flash the panel to clear ghosting |
| **Ctrl W** | word count and reading time |
| **Ctrl Shift M / Ctrl Shift P** | export HTML, export PDF |
| **Esc**, or a tap outside | close any panel |

## What it supports

Headings, bold, italic, bold-italic, strikethrough, highlight, inline code,
fenced code blocks with language tags, blockquotes, nested bullet and numbered
lists, task lists with real checkboxes, links, horizontal rules, and — rendered
live in the editor rather than only on export — **tables and images**.

A table is drawn as a real grid: columns sized to their contents and then scaled
to the text measure, header in bold, alignment taken from the `:---` markers,
and the `| --- |` row collapsed into the rule beneath the header. Put the caret
on any row and that row alone turns back into pipes so you can edit it, exactly
as the syntax markers do everywhere else. The file on disk is never anything but
plain Markdown.

Images are drawn in place of their `![alt](path)` source, at their own size up
to the width of the text column. Paths resolve relative to the document's own
folder. Decoding happens on a background thread into a size-bounded cache, so
neither styling nor drawing ever touches the disk; until a picture is ready, and
if it turns out to be missing, its place is held by a labelled frame rather than
a gap. Remote URLs are deliberately not fetched — the app holds no network
permission and a writing tool should not stall on a download.

Files autosave every few seconds and again whenever the app is backgrounded,
written via a temporary file and a rename so a crash mid-write cannot truncate a
draft. A shadow copy is also kept in the app's private storage, and if a run ends
without its text reaching disk — a crash, or a save that failed for want of a
permission — the next start offers that text back rather than discarding it. The
offer is silent in the normal case, because after a clean exit the copy and the
file agree.

Undo survives a change of interface scale or handedness, both of which rebuild
the whole view tree.

Export produces a self-contained styled HTML file, or a paginated A4 PDF
rendered directly from the same engine that draws the screen. Local images are
folded into the HTML itself, so the page holds together wherever the file is
sent. Both land in an `Exports` folder beside your documents.

---

## Building from source

Requires a JDK (17 or newer) and the Android SDK. Create `local.properties` with
`sdk.dir=/path/to/Android/Sdk`.

```sh
./gradlew :app:assembleRelease          # build the APK
./gradlew :app:testReleaseUnitTest      # run the test suite
```

The build works with no further setup and signs with the debug key. To sign with
your own key instead, copy `keystore.properties.example` to
`keystore.properties` and fill it in; both it and `keystore/` are gitignored.

### Tests

271 tests, all passing. They cover the parts that would otherwise fail silently:

- **`PureLogicTest`** — the density decision against a truthful Manta, a Manta
  reporting a false 160 dpi, garbage metrics and an ordinary high-density phone;
  the HTML renderer; outline extraction; word counting; file naming.
- **`StylerTest`** (Robolectric, real Android text buffers) — that markers hide
  and reveal correctly around the caret, that emphasis is applied while the
  buffer itself is never modified, that code fences suppress Markdown inside
  them, that focus mode dims the right paragraphs, and that styling every caret
  position in a document containing every construct never throws. It also
  asserts that incremental restyling while typing converges on the same result
  as a full restyle, and that a single edit in a 300-section document restyles
  in under 60 ms.
- **`EditorTest`** — wrapping and unwrapping, heading levels, list prefixes,
  Enter continuing and ending lists, Tab indentation, and the undo history
  including coalescing, redo, and discarding an abandoned redo branch.
- **`ShortcutTest`** — the real activity driven by real key events, because a
  chord mapped to a command name that no longer exists still compiles and then
  silently does nothing. Every formatting chord, undo and redo, each panel
  opening and closing on Escape, the mode toggles, and that a scale change
  preserves the document and the caret through the view rebuild.
- **`DocStoreTest`** — the file layer against a real filesystem, since this is
  where a bug costs words rather than pixels: that a save leaves no temporary
  file behind, that a failed save leaves the previous contents intact, that
  rename refuses to clobber an existing file, and that the recovery copy is
  offered only when it belongs to the open document and differs from it.
- **`RecoveryTest`** — starting up after text failed to reach disk, driven from
  the outside: arrange the leftovers, start the app, check what it offers and
  that restoring writes through.
- **`TableImageTest`** — table detection, column arithmetic, alignment markers,
  empty cells, pipes that are not a table, the caret revealing one row while the
  rest stay a grid, and every caret position in a table; plus image path
  resolution, a real decode off the main thread, and a missing file being
  reported rather than going blank.
- **`ActivityStartupTest`** — that the app actually starts and survives a full
  lifecycle round trip.

The emulator in this environment segfaults on launch, so the app has not been
run on a live screen; the Robolectric suite covers startup and the text engine
in its place.
