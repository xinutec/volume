# The Mac's audio — the thoth contract

The app's top card is not a headphone. It drives **thoth**, a small Swift service on
the Mac that owns the room's speakers, the default microphone and the arcade
cabinets' volume, and answers HTTP on the LAN. Measured against the live service on
2026-09-04; re-measure if that service changes.

## Why it is in this app

thoth already had a phone client: an Android **WebView wrapper** in `~/Code/thoth/android/`
whose entire job was to load `http://<mac>:8089/` full-screen. Two consequences made
folding it in here the right move rather than a merge for its own sake:

- **Two launcher icons, both labelled "Volume".** The wrapper's label and icon were
  already the same idea as this app's, because they are the same idea.
- **A wrapper can do nothing native.** The one feature it had beyond the page —
  hardware volume keys driving the speakers — worked by consuming the key event and
  calling `window.thothVolumeStep()` through `evaluateJavascript`. That is a native
  hook bolted to a web page. Here it is just a key handler.

⚠ **The web app is untouched and is not being replaced.** thoth still serves its
Angular UI from memory on the same port, so a browser, a Mac, or somebody else's
phone reaches it exactly as before. This is a *second client*, and the wrapper is the
thing that becomes redundant.

⚠ **This is not the speaker support that `README.md` puts out of scope.** That
decision is about *Bluetooth* speakers driven over a vendor protocol — a Revolve on
this phone's radio. These speakers are wired to a Mac, and nothing here touches a
Bluetooth stack: the app asks a service to move them.

## The surface

Seven paths, four of which this client uses. `GET` unless marked.

| path | what | used here |
| --- | --- | --- |
| `/api/pair` | the stereo pair's state; `POST` a partial change | yes |
| `/api/pair/recalibrate` | `POST` — drift-comp off→on, re-locking the two clocks | yes |
| `/api/devices` | output devices, for the left/right pickers | yes |
| `/api/input` | input devices + the live default + the pin; `POST` switches | yes |
| `/api/input/pin` | `POST` — move the pin, `""` clears it | yes |
| `/api/picades` | the arcade cabinets; `POST` sets one's volume | yes |
| `/api/telemetry` | `POST` — the shared fleet activity trace | no |

`/api/telemetry` is the web client's; a native card is not a page and has no
navigation or route changes to trace.

### The pair

```json
{"active":true,"balance":0,"ceiling":0.65,"left":"…","right":"…",
 "stereo":false,"volume":0.5199999809265137}
```

`volume` is the louder side's level, `balance` is the ratio between the two sides, and
both are **derived live from the two speakers' per-channel volumes on every read** —
the server stores neither. Only the left/right assignment persists. Two things follow,
and they shape the whole client:

- **Anything changed elsewhere exists only until re-read.** The Mac's own Sound
  settings, a browser, another phone. Hence a poll rather than a subscription.
- **A poll that overlaps a drag drags the thumb backwards**, because it answers with
  the level from before the write landed. Hence `ThothController.idle()`: no poll
  while a change is pending, in flight, or less than 2.5 s old.

### The cabinets

```json
{"cabinets":[{"ceiling":255,"host":"picade0","raw":0,"status":"online","volume":0},
             {"host":"picade3","status":"offline"}]}
```

⚠ **`ceiling` here is a scale, not a bound** — the ALSA step count `raw` is measured
against. It is parsed into `ThothCabinet.steps` for exactly that reason; the identically
named field on the pair is a hearing limit and they must never be confused.

⚠ **`raw` and `volume` are read through `has()`, not `optInt`/`optDouble`.** picade0 in
the capture above is online at raw 0 — a real reading of silence. `optInt` returns 0
for an absent key too, so the tolerant read cannot tell "silent" from "did not report",
and the honest reading would have been discarded as missing.

Cabinets that are off are **listed, not omitted**: the rows are the fleet, and a
missing row reads as a cabinet nobody knows about.

## The volume ceiling

thoth refuses any request above `VolumeCeiling.max`, currently `0.65`, and **refuses
rather than clamps** — a silent clamp makes every later read agree with the smaller
number, so nobody learns the bound exists. The refusal is a 400 whose body is a
sentence:

```
volume 0.9 exceeds the ceiling of 0.65 — thoth will not set a level that loud (see #787)
```

Three things this client does about it, and each is a decision in `:protocol`:

1. **It reads the ceiling; it does not carry one.** `ceiling` on the pair state is
   there so a client can bound its own controls. ⚠ A second written-down copy of a
   hearing limit is a copy that drifts out of step with the one actually enforced —
   the field was added to thoth for this, and the client has no fallback constant.
2. **A server that publishes no ceiling bounds the control at the level it is already
   at.** Not "unbounded", and not a guessed 0.65: it can be turned down and never up.
3. **A level already above the ceiling bounds the control at that level, not at the
   ceiling.** Clamping to 65 would make a press of volume-UP quietly turn the speakers
   *down* by fifteen points, and putting a level back where it already was is not
   raising it.

The 400's body reaches the card verbatim (`ThothScreen.refusal`). A client that
swallowed it would restore exactly the silence the refusal exists to break.

## Shape of the code

```
:protocol  Thoth.kt        the model, and every decision — what a cabinet's status
                           means, how far a control may travel, which call a pick has
                           to make, what an unreachable Mac's card says
           ThothClient.kt  the transport INTERFACE, the JSON, the request bodies
:app       ThothHttp.kt    HttpURLConnection, and nothing else
           ThothController.kt  the poll, the coalescing, the thread
           ThothCard.kt    the card, plus the displayed-value state a thumb needs
```

Same seam as the Bluetooth half: the interface is in `:protocol`, so every request and
every reply is exercised on a JVM with **no network at all** — `ThothTest` runs against
bodies captured from the live service with `curl`.

### Two gotchas that cost time

- **`org.json` is `compileOnly` in `:protocol`.** Android ships that package in the
  boot classpath, so packaging the Maven artifact would put a shadowed second copy of
  those classes in the APK. Compiling against it and letting the device supply it keeps
  `:app`'s runtime unchanged while the parsing still runs on a plain JVM under test.
  The artifact is on both modules' *test* classpaths, because the mockable `android.jar`
  throws on every `org.json` call.
- **A non-2xx body is on `errorStream`.** `inputStream` throws for it. Reading the
  wrong stream turns the ceiling's explanatory sentence into a bare status code.

### The thread

`ThothController` has **its own executor, not `Sessions.work`**. That one is
single-threaded because two Bluetooth connects contend for the radio, and the JBL's LE
scan can hold it for twenty-five seconds — a poll queued behind that would freeze this
card for the length of a headphone connect, and a connect queued behind a stalled HTTP
read would be worse.

## What is not here

- **Party Mode / Music Share and anything else on the Bluetooth speakers** — a
  different subject entirely; see `docs/bose-read-surface.md`.
- **Creating or destroying aggregate devices.** thoth deleted its generic device CRUD
  on purpose: the UI's needs define the surface. Left/right pickers imply the group.
- **The activity trace.** See above.
