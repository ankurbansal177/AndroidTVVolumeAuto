# AndroidTVVolume

A phone app that auto-adjusts an Android TV's volume based on content loudness
(music vs. dialogue), without root and without anything installed on the TV beyond
what ships on stock Android TV / Google TV.

It works in two parts:

- **Actuator** — pairs with the TV and sends volume up/down/mute commands, by
  reimplementing the same pairing/remote-control protocol the Google Home app uses
  (the "Android TV Remote Service v2" protocol, TCP ports 6467/6466).
- **Sensing** — listens to the room via the phone's mic, estimates loudness, and
  runs a control loop that nudges the TV's volume to hold a level you calibrate once.

Both are implemented and have been tested end-to-end against a real Sony BRAVIA TV.

## Setup

1. **Pair with the TV.**
   - On the TV, open the settings path where it lists paired remotes/accessories
     (the same flow the Google Home app uses to add the TV — wording varies by
     brand, e.g. **Settings → Remotes & Accessories → Add accessory**, or
     **Settings → Network & Internet → pair**). Trigger "add a new remote" so the TV
     shows a 6-digit PIN.
   - In the app, enter the TV's local IP address and tap **Pair**.
   - Enter the PIN shown on the TV and submit.
   - The TV and paired state are remembered, so relaunching the app reconnects
     automatically — no PIN needed again unless you tap **Forget pairing**.
2. **Grant microphone access** when prompted (needed for the loudness meter and
   auto-correction; happens automatically once paired).
3. **Measure room noise.** Mute the TV (or turn it all the way down) so only ambient
   noise (fan hum, AC, etc.) is present, then tap **Measure room noise**. This gets
   subtracted from every reading from then on, so a constant background hum doesn't
   get mistaken for content or drown out quiet dialogue. Skip this if your room is
   quiet enough that it doesn't matter.
4. **Calibrate.** Unmute/restore volume to a level you're happy with, then tap
   **Calibrate** — it captures the current loudness as the target baseline.
5. **Toggle Auto Mode on.** The app now runs a foreground service that watches the
   mic level and sends volume up/down commands to pull it back toward the baseline
   whenever it drifts outside a ±3dB tolerance. The on-screen meter shows the live
   level, the baseline marker, and whether it's actively correcting. Manual
   Vol +/−/Mute buttons keep working as an override at any time.

## How the protocol works

Pairing drives the standard handshake on TCP port 6467 (mutual TLS, self-signed
certs, PIN-derived shared secret), then the remote-control session on port 6466 —
matching the public, documented protocol used by `androidtvremote2` and the
Google Home / Android TV Remote apps. The TV also pushes its actual volume
level/mute state back over that session (`RemoteSetVolumeLevel`), which the app
displays, so you can see the real device volume rather than just an assumption.

## Building

```
git clone https://github.com/ankurbansal177/AndroidTVVolumeAuto.git
cd AndroidTVVolumeAuto
```

Either open the project root in Android Studio and let it sync (uses the checked-in
Gradle wrapper, no separate install needed), or build/install from the command line
if you have a JDK and the Android SDK platform-tools available:

```
./gradlew installDebug   # builds and installs the debug APK on a connected device
```

## Known limitations

- Auto Mode opens a second, independent remote-control connection to the TV
  (separate from the one the manual buttons use) — not confirmed whether every TV
  tolerates two concurrent sessions gracefully.
- The measured "dB per volume step" only tests an up-step and assumes down-steps
  are symmetric.
- Turning Auto Mode off stops corrections but leaves mic capture running in the
  background service until the app is fully closed.
- Only verified against a Sony BRAVIA TV so far — other brands' embedded TLS/pairing
  stacks may need small tweaks (this one needed several: forcing TLS 1.2, enabling
  the full cipher suite list, and using a software rather than hardware-backed
  client certificate).
