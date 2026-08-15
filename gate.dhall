{-
volume/gate.dhall — this repository's commit gate.

Short, because the repository is short: one Android module, no frontend, no
backend. What it does have is the piece worth gating hardest — `Channels.kt`,
whose tests are the real SDP records read off the phone. The trap those encode
(most vendor-looking UUIDs are shared between vendors, and the one task #783
labels "Bose proprietary" is on the Sony too) is the kind a later edit
re-introduces because keying on it looks reasonable.

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
        , argv = G.inShell "../recall#android" [ "ktlint", "app/src/**/*.kt" ]
        , timeout_s = 900
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
      , G.checkTable "../dev-lint"
      ]
    }
