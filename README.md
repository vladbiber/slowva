# Slowva

An unofficial Android Spotify client for playing music slower, with adjustable
reverb, bass boost and frequency-tuning presets. Audio is decoded and processed
locally on the phone.

> **Unofficial project.** Slowva is not affiliated with, sponsored by or
> endorsed by Spotify. Spotify is a trademark of Spotify AB. The app uses an
> unofficial librespot-based playback path and may stop working if Spotify
> changes its services. Use it only with music and accounts you are authorized
> to access and review the applicable Spotify terms before use.

## Features

- Spotify library, search, playlists, albums and playback controls
- playback speed from 0.50x to 1.25x with matching pitch shift
- adjustable Freeverb and 0–12 dB bass boost
- optional 432 Hz, 528 Hz and other frequency-tuning presets
- album artwork in the full player and Android media controls
- local librespot playback at 320 kbps
- Spotify Authorization Code + PKCE; no client secret is stored or requested
- **Slowva FX** home-screen widget for slow, reverb and local-player controls
- optional **Slowva CAVA** home-screen widget; no visualizer covers the album in
  the app itself
- five interactive player looks: **Vinyl** (tonearm, scratch to seek), **CD**,
  **Tape** (drag to shuttle), **TP-7** (jog wheel, slow fader, reverb knob) and
  **Cover**; design inspired by [Spun](https://github.com/yappologistic/Spun)
  by [@yappologistic](https://github.com/yappologistic)
- **Real vinyl sound**: crackle, pops, scratches, wow & flutter, warmth, hiss
- **Reverse mode**: whole tracks played from the last second to the first
- **Local files**: add folders or single files from the phone, played through the same effects
- calm **rain** sounds (drizzle, rain, storm) mixed on top of the music
- **8D rotation**, stereo width and an "another room" muffle effect
- one-tap presets: Slowed + reverb, Daycore, Nightcore, Lo-fi record and more
- arm64 phone and x86_64 emulator builds

Spotify Premium is required for Development Mode and local playback.

## Install the APK

Download the ready-made Android 8.0+ arm64 APK from the
[Slowva 2.0.1 release](https://github.com/vladbiber/slowva/releases/tag/2.0.1),
or use the direct link:

[**Download Slowva-arm64-v8a.apk**](https://github.com/vladbiber/slowva/releases/download/2.0.1/Slowva-arm64-v8a.apk)

This build is intended for modern arm64 Android phones, including Samsung
devices. Android may ask you to allow installation from your browser or file
manager. SHA-256: `d6fba21ba7cbf4796b9de141e736eb2d55ad0159464f2c9b517812a789cf6ff5`.

## One-time Spotify setup

Public builds intentionally do **not** contain the maintainer's Spotify Client
ID. On first launch, Slowva guides every user through these steps:

1. Open the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard).
2. Create an app with Web API access.
3. Add this exact Redirect URI in the app settings:

   ```text
   http://127.0.0.1:5588/login
   ```

4. Copy the 32-character Client ID into Slowva and tap **Save & sign in**.

Do not paste a Client Secret. Mobile authentication uses Spotify's recommended
[Authorization Code with PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow),
for which the Client ID and exact allowlisted Redirect URI are required.

### First-time player pairing

Signing in unlocks the library, but the local player must also be paired once:

1. Open the official Spotify app with the same Premium account and start a song.
2. Open **Devices** / **Devices available**.
3. Select the device named **slowva** and wait for it to connect.
4. Return to Slowva. The player credentials are now saved on this phone.

After this first pairing, use **Connect to this phone** inside Slowva. Pair again
only after signing out, clearing the app's data or reinstalling the app.

### Why does every public user need a Client ID?

Spotify Development Mode is intended for personal and hobby use. Its current
rules require the app owner to have Premium and limit an app to five authorized
users. Every user creating their own app avoids sharing the maintainer's quota
and five-user allowlist. See Spotify's
[quota mode documentation](https://developer.spotify.com/documentation/web-api/concepts/quota-modes)
and [2026 migration guide](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide).

An organization with approved Extended Quota access can make a preconfigured
build instead; see `SPOTIFY_CLIENT_ID` under Building.

## Effects

- **Slow** uses a four-point cubic Hermite resampler. Pitch drops or rises with
  playback speed instead of being corrected back to the original pitch.
- **Reverb** is a Freeverb implementation with eight comb filters, four all-pass
  filters per channel and stereo spread.
- **Bass boost** uses an RBJ low-shelf filter with adjustable gain and corner
  frequency.
- **Frequency presets** retune the nearest note to the selected frequency. The
  healing or biological claims often attached to “solfeggio” frequencies are
  not supported by research; the presets are included only as sound effects.
- **Vinyl** adds wow and flutter locked to a 33⅓ rpm platter (slower when the
  track is slowed), groove rolloff with soft saturation and mono bass, surface
  hiss, motor rumble, random crackle and two scratches that tick once per
  revolution. Presets: Mint, Played, Dusty, Lo-fi.
- **Reverse** decodes the complete track once (`libslowvareverse.so`, a small
  librespot decoder that registers no Connect device), then plays the PCM from
  the end through the same effect chain and continues with the next track.
  Decoded tracks take about 10 MB per minute; the last four are kept.
- **Space**: stereo width, an equal-power "8D" auto-pan and a low-pass
  "another room" effect.

Slider changes are smoothed in the DSP to prevent abrupt speed jumps and
reverb clicks.

## Privacy and credentials

- Client IDs are public identifiers, not passwords.
- OAuth uses PKCE and a loopback redirect.
- Access and refresh tokens stay in Android private app storage.
- Slowva has no custom backend and sends no credentials to a custom server.
- Signing out deletes OAuth data and cached librespot credentials while keeping
  the selected Client ID for the next login.

## Building

Requirements:

- JDK 17
- Android SDK 35
- Android NDK 28.2
- Rust with `aarch64-linux-android`

```sh
git clone https://github.com/vladbiber/slowva.git
cd slowva

rustup target add aarch64-linux-android
./native/build-librespot.sh

./gradlew testDebugUnitTest assembleDebug
```

The arm64 phone APK is written to:

```text
app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Build the phone and x86_64 emulator native binaries with:

```sh
./native/build-librespot.sh --all
```

Maintainers with an approved shared Spotify app may optionally bundle its
public Client ID. Ordinary public builds should leave this unset:

```sh
./gradlew assembleRelease -PSPOTIFY_CLIENT_ID=0123456789abcdef0123456789abcdef
```

Never pass a Client Secret to Gradle or commit one. Production release signing
is a maintainer responsibility; when a local Android debug key exists, the
development release task uses it to create installable test APKs.

## Audio architecture

```text
Spotify Connect
  -> embedded librespot 0.8.0 (`--backend pipe`)
  -> PCM s16le, 44.1 kHz stereo
  -> Hermite slow/pitch resampler
  -> Freeverb + low-shelf filter
  -> Android AudioTrack
```

Android 10 and newer cannot execute arbitrary files from the app data
directory. The native binaries are therefore packaged as `lib*.so` files and
extracted into Android's executable `nativeLibraryDir`.

## Known limitations

- Pairing must be repeated after signing out, clearing app data or reinstalling.
- Spotify Web API quotas and supported endpoints can change.
- While slowed, Spotify Connect can report a position ahead of the sound because
  the PCM pipe is deliberately consumed below real time.
- Effects apply only while playback is on the local Slowva device.
- The app supports Android 8.0+ and arm64/x86_64.

## Credits

The player looks (vinyl, CD, cassette and TP-7 style) are inspired by
[Spun](https://github.com/yappologistic/Spun), a CD-shaped music player for
Linux by [@yappologistic](https://github.com/yappologistic). Slowva's versions
are an independent Jetpack Compose reimplementation; no Spun code or assets are
included. Thank you for the beautiful designs.

## License

Slowva source code is available under the [MIT License](LICENSE). See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party software and
trademark notices.
