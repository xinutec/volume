{-
volume/gate.dhall — this repository's commit gate.

Two modules, and the split is what makes the gate worth having. `:protocol` is
every byte of the five wire formats with no Android dependency, so its tests are
plain JVM tests: they run here, in seconds, with no phone, no pairing and no
headphones switched on. `:app` is transports and screen.

The rows worth gating hardest are in `:protocol` — `Channels.kt`, whose tests are
the real SDP records read off the phone, and `DriversTest`, which replays recorded
transcripts through the real driver code. Both encode traps a later edit
re-introduces because the wrong thing looks reasonable: most vendor-looking UUIDs
are shared between vendors, the one task #783 labels "Bose proprietary" is on the
Sony too, and a device's reply to a write says nothing about whether it took.

**No flake of its own.** The Android SDK comes from recall's `#android` devshell,
the same borrowing `xinutec-infra/govee-android` does — a second unfree SDK
composition per repository buys nothing. The path is relative, so this works in
any clone that sits beside its siblings.

**`assembleDebug` and not `assemble`.** Release is signed with the debug key here
and nothing consumes it; building both would double the slowest row to gate an
artifact no one installs.

There is no instrumented-test row. Everything this repository knows how to check
without a phone is a unit test, and everything else needs headphones that are
powered on and in range — which is a measurement, not a gate.
-}

let G = ../dev-lint/gate/schema.dhall

in  { name = "volume"
    , checks =
      [ G.devLint "../"
      , {-  ktlint expands its own globs, so they are passed through literally — a
            shell glob would resolve against the working directory and match a
            different set.
        -}
        G.Check::{
        , name = "ktlint"
        , argv =
            G.inShell
              "../recall#android"
              [ "ktlint", "app/src/**/*.kt", "protocol/src/**/*.kt" ]
        , timeout_s = 900
        }
      , {-  The device-independent suite, and the reason the module split exists:
            no emulator, no SDK, no phone. Runs first because it is the fastest row
            and the one most likely to catch a real mistake.
        -}
        G.Check::{
        , name = "protocol tests (no device)"
        , argv =
            G.inShell
              "../recall#android"
              [ "./gradlew", "--console=plain", ":protocol:test" ]
        , timeout_s = 1800
        }
      , {-  The whole unit suite. `testDebugUnitTest` and not a filtered variant:
            a narrowed filter compiles the classes it skips, so a broken one stays
            green until something else runs it.
        -}
        G.Check::{
        , name = "unit tests"
        , argv =
            G.inShell
              "../recall#android"
              [ "./gradlew", "--console=plain", ":app:testDebugUnitTest" ]
        , timeout_s = 1800
        }
      , {-  Kotlin that only the device compiles is where a probe rots: the unit
            tests never touch MainActivity or Probe, so without this row an
            android.bluetooth API change would surface only at install time.
        -}
        G.Check::{
        , name = "assembleDebug"
        , argv =
            G.inShell
              "../recall#android"
              [ "./gradlew", "--console=plain", ":app:assembleDebug" ]
        , timeout_s = 1800
        }
      , {-  ⚠ **The capture driver is typed and gated, and that is a correction.**
            It was shell, and grew a state machine, retry loops, restore tracking
            and an embedded Python XML parser — at which point FOUR preconditions
            were missing at once and nothing anywhere went red, because shell has
            no gate and a heredoc'd language is invisible to every engine here.
            The worst of the four: it never checked which app was in front, and
            drove a whole run into the agent console.

            `--strict` rather than plain mypy: the faults were all "this value can
            be absent and nobody said so", which is exactly what the optional-type
            rules catch and what a permissive run would let through again.
        -}
        G.Check::{
        , name = "scripts type-check"
        , argv = [ "nix", "shell", "nixpkgs#mypy", "-c", "mypy", "--strict", "scripts/" ]
        , timeout_s = 900
        }
      , G.checkTable "../dev-lint"
      ]
    }
