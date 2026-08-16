# Does the screen follow the radio?

The one property of this app that cannot be established off the phone. `:protocol`
is tested without a device, and the list logic is reconciled in tested code — but
*which broadcast arrives when* is a fact about one Android build on one handset,
and it is the thing that was got wrong.

## The measurement

`scripts/watch-list.sh` reads it from outside (radio state and the semantics tree
on one clock). `adb logcat -s VolumeLive` reads it from inside: every broadcast the
app receives, and what the profile proxies said at that instant.

Pixel 9, Android 16, 2026-08-16, Sony WH-1000XM4, Volume in the foreground.

### Connecting — ACL is 1.24 s too early

    09:11:53.768  broadcast: ACL_CONNECTED
    09:11:53.775  refresh: bonded=13 connected=0 listed=0
    09:11:54.315  broadcast: CONNECTION_STATE_CHANGED
    09:11:54.329  refresh: bonded=13 connected=0 listed=0
    09:11:54.992  broadcast: CONNECTION_STATE_CHANGED
    09:11:55.010  refresh: bonded=13 connected=1 listed=1

⚠ **This is why `BluetoothA2dp`/`BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED`
are in the filter.** The ACL link comes up first; the A2DP and headset proxies —
the only public route to "is this BR/EDR device connected", since
`BluetoothManager.getConnectedDevices` answers for GATT alone — are still empty
1.24 s later. An ACL-only listener therefore queries once, finds nothing, and
never hears anything again. That is the four-minute disappearance.

### Disconnecting — the profile events LEAD ACL by 414 ms

    09:11:45.989  broadcast: CONNECTION_STATE_CHANGED
    09:11:46.031  refresh: connected=1          (proxies still stale)
    09:11:46.186  broadcast: CONNECTION_STATE_CHANGED
    09:11:46.197  refresh: connected=0
    09:11:46.403  broadcast: ACL_DISCONNECTED
    09:11:46.416  refresh: connected=0

So the profile events are the earlier signal in **both** directions. They are not a
late correction bolted onto ACL, which is how the code comment used to read.

## What each mechanism actually covers

Two separate things, and conflating them is what made the first explanation wrong:

| situation | what handles it |
|---|---|
| pair connects while the screen is in front | the profile broadcast receiver |
| pair already connected when the app launches | `onStart`'s `refresh()` — **no broadcast fires at all** |

⚠ A receiver cannot fix a cold start: nothing is broadcast for a device that was
already connected. The original note cited the cold-start failure as the reason for
the receiver, which was the right conclusion from the wrong evidence — the sort of
mistake that survives review and then blocks a later simplification.

## Measured end to end

| what | result |
|---|---|
| connects while foregrounded, untouched | card, model, session and chips within one ~3 s sample |
| cold start against a connected pair | populated in 5 s (was: invisible for four minutes) |
| selected chip matches the device | `checked="true"` on Noise cancelling, from a real read |
| disconnect | card gone, session dropped |

## Traps in measuring this

- ⚠ **`am start` on an already-resumed activity does not run `onStart`**, so no
  refresh happens and no line is logged. Twice this read as "the receiver never
  fired". `am force-stop` first, or the absence means nothing.
- ⚠ **`uiautomator dump` captures whatever is focused.** With the notification
  shade open, or in split screen, it returns another app's tree. Check
  `dumpsys window | grep mCurrentFocus` first — `watch-list.sh` refuses to run
  otherwise.
- ⚠ **`mCurrentFocus` naming another app does NOT mean this one is stopped.** In
  split screen both halves stay resumed and only one has focus, so `onStop` never
  fires and nothing is released. Reading focus as "backgrounded" produced a
  confident wrong diagnosis — the app was said to be holding nothing while it held
  an open GATT client, which was why a tile tap could not open one.
- ⚠ **Verify the APK actually carries the change** before believing a run:
  `strings` the installed dex for a string the change added. A successful
  `adb install` is not evidence.
- ⚠ **Reinstalling removes the app's Quick Settings tile** from `sysui_qs_tiles`,
  silently. Re-add with `adb shell cmd statusbar add-tile
  org.xinutec.volume/.AncTileService`, and note `click-tile` only reaches a bound
  service — i.e. with the shade open (`cmd statusbar expand-settings` first).
