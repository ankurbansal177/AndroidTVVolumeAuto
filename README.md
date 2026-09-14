# AndroidTVVolume

A phone app that controls an Android TV's volume over the local network, by
reimplementing the same pairing/remote-control protocol the Google Home app uses
(the "Android TV Remote Service v2" protocol). No root, no app on the TV side beyond
what ships on stock Android TV.

## Status: phase 1 (actuator only)

This is the first of two planned phases, scoped deliberately:

- **Phase 1 (this app today):** pair with the TV and send volume up / down / mute
  commands manually, from buttons in the app. This is the "how do I change the volume
  without root" half of the problem, proven end-to-end.
- **Phase 2 (not implemented yet):** listen to the TV's actual audio output via the
  phone's microphone, estimate loudness, and automatically send the volume commands
  above to keep perceived loudness roughly constant across content (e.g. dialogue vs.
  music). This is the "auto-leveling" half — it builds on top of phase 1's actuator
  once that's proven solid.

## How pairing works

1. On the TV, open the settings path where it lists paired remotes/accessories (this is
   the same flow the Google Home app uses to add the TV — the exact menu wording varies
   by brand/launcher, e.g. **Settings → Remotes & Accessories → Add accessory**, or
   **Settings → Network & Internet → pair**). Trigger "add a new remote" so the TV shows
   a 6-digit PIN on screen.
2. In this app, enter the TV's local IP address and tap **Pair**.
3. When the app prompts for the PIN, type in the 6 digits shown on the TV and submit.
4. Once paired, the app connects to the TV's remote-control port and the volume
   buttons become active. The TV IP and paired state are remembered, so relaunching the
   app reconnects automatically without repeating the PIN step.

Under the hood this drives the standard pairing handshake on TCP port 6467 (mutual TLS,
self-signed certs, PIN-derived shared secret) and then the remote-control session on
port 6466, matching the public, documented protocol used by `androidtvremote2` and the
Google Home / Android TV Remote apps.

## Building

Open the project root in Android Studio (Iguana or newer) and let it sync — it uses the
checked-in Gradle wrapper, so no separate Gradle install is needed. This was scaffolded
without a local JDK/Android SDK available, so the first sync in Android Studio is the
first real compiler pass; check the Build output for anything that needs a small fix
(most likely candidates: Compose compiler / Kotlin / AGP version pins in the root
`build.gradle.kts` and `app/build.gradle.kts` needing to match whatever Android Studio's
bundled toolchain expects).

## Known limitations

- Only tested against the protocol spec, not a physical TV — pairing/handshake edge
  cases on specific brands (Sony/TCL/Hisense/etc.) may need small tweaks.
- No mic-based sensing loop yet (phase 2).
- The remote session's background reader is tied to the Compose screen's coroutine
  scope, so backgrounding/rotating the app can drop the connection; reconnect by
  reopening the app (it auto-reconnects using the saved pairing, no PIN needed again).
