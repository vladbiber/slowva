# Slowva

An unofficial Android music player for Spotify and your own files. It plays
music slower, with reverb, bass, "magic Hz" tuning, vinyl sound and reverse.
Audio is decoded and processed on the phone.

> **Unofficial project.** Slowva is not affiliated with, sponsored by or
> endorsed by Spotify. Spotify is a trademark of Spotify AB. The app uses an
> unofficial librespot-based playback path and may stop working if Spotify
> changes its services. Use it only with music and accounts you are authorized
> to access and review the applicable Spotify terms before use.

## Features

- Spotify search, liked songs, playlists and saved albums, played inside Slowva
  through the same effects, queue and lyrics as your own files
- Your local music, playlists, Playlist Studio, a full queue with drag to reorder
- Hand-drawn player looks: vinyl (scratch to seek), CD, tape, TP-7, MiniDisc,
  boombox, car radio, cover
- Sound: EQ with genre presets, speed and pitch, reverb, bass, "magic Hz" tuning,
  8D, vinyl crackle, rain, reverse, hi-fi options
- Modes: Evil, Godly and Good & Evil, each with its own sound, colors and icon
- Synced lyrics (LRCLIB), visualizers, sleep timer, spin-the-wheel song picker
- Editable Home with shelves from what you play, Last.fm genres, artists and
  charts, MusicBrainz album info, in-app and home screen widgets
- ListenBrainz scrobbling, star ratings with RateYourMusic CSV import
- Tie-dye backgrounds in your theme colors, a rainbow or the album cover's colors
- Android Auto, media notification and lock-screen controls, keyboard-phone shortcuts

Spotify Premium is required for Spotify playback.

## Install the APK

Download `Slowva-arm64-v8a.apk` from the
[latest release](https://github.com/vladbiber/slowva/releases/latest)
(Android 8.0+, arm64). It installs over Slowva 2.x and keeps your sign-in.
Android may ask you to allow installation from your browser or file manager.

## One-time Spotify setup

Everything is in **Settings › Connections › Spotify**.

1. Open the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard)
   and create an app with Web API access.
2. Add this exact Redirect URI in the app settings:

   ```text
   http://127.0.0.1:5588/login
   ```

3. Paste the 32-character Client ID into Slowva and tap **Save**.
4. Tap **Sign in**. Spotify asks you to approve twice: once for playback, once
   for your own app. If the browser never comes back, use **Sign in inside the app**.

Do not paste a Client Secret. Sign-in uses
[Authorization Code with PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow).
There is no Spotify Connect pairing any more.

### Why does every public user need a Client ID?

Spotify Development Mode is intended for personal and hobby use. Its current
rules require the app owner to have Premium and limit an app to five authorized
users. Every user creating their own app avoids sharing the maintainer's quota
and five-user allowlist. See Spotify's
[quota mode documentation](https://developer.spotify.com/documentation/web-api/concepts/quota-modes)
and [2026 migration guide](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide).

An organization with approved Extended Quota access can make a preconfigured
build instead; see `SPOTIFY_CLIENT_ID` under Building.

## How Spotify playback works

```text
Spotify track
  -> libslowvadecode (librespot 0.8.0, no Connect device)
  -> PCM s16le, 44.1 kHz stereo, served to ExoPlayer as a WAV stream
  -> speed and pitch, then Slowva's effect chain
  -> Android audio output
```

Seeking restarts the decoder at the new position. Reverse decodes the whole
track once into the cache (about 10 MB a minute; the last four are kept).
Android 10 and newer only run binaries from the app's `nativeLibraryDir`, so the
helpers ship as `lib*.so` files.

The frequency presets retune the nearest note to the chosen frequency. The
healing claims often attached to "solfeggio" frequencies are not supported by
research; the presets are sound effects only.

## Privacy and credentials

- Client IDs are public identifiers, not passwords.
- OAuth uses PKCE and a loopback redirect.
- Access and refresh tokens stay in Android private app storage.
- Slowva has no custom backend and sends no credentials to a custom server.
- Signing out deletes OAuth data and cached librespot credentials while keeping
  the selected Client ID for the next login.

## Building

Requirements: JDK 17, Android SDK (compileSdk 37), Android NDK 28.2, Rust with
the `aarch64-linux-android` target (and `x86_64-linux-android` for the emulator).

```sh
git clone https://github.com/vladbiber/slowva.git
cd slowva
rustup target add aarch64-linux-android x86_64-linux-android
./native/build-librespot.sh --all
./gradlew testDebugUnitTest assembleRelease
# APK -> app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

`native/token-helper/vendor/librespot-core` is librespot-core 0.8.0 with one
change: it reports Linux on Android, because Spotify refuses the Android
client identity for these credentials.

Maintainers with an approved Spotify app may bundle its public Client ID with
`-PSPOTIFY_CLIENT_ID=...`. Never pass a Client Secret to Gradle or commit one.

## Known limitations

- Spotify Web API quotas and endpoints can change. Without your own Client ID,
  search and the library are mostly refused as busy.
- Every Spotify song opens a fresh librespot session, so a song takes about a
  second to start and there is no gapless playback between Spotify songs.
- Slowva is not a Spotify Connect device; other Spotify apps can't control it.

## Credits

- [Guava Player](https://github.com/vladbiber/guava-player): Slowva 3.0 is built on
  its code, without YouTube and radio
- [librespot](https://github.com/librespot-org/librespot) (MIT): Spotify playback
- [Levyra](https://github.com/LUC4N3X/Levyra-deepsound) (GPL-3.0): the Material 3
  Expressive shape style (cookie, clover and sunny shapes)
- [LRCLIB](https://lrclib.net): synced lyrics
- [Last.fm](https://www.last.fm): genres, artists and charts
- [MusicBrainz](https://musicbrainz.org): album info
- [ListenBrainz](https://listenbrainz.org): scrobbling
- [Spun](https://github.com/yappologistic/Spun) by
  [@yappologistic](https://github.com/yappologistic): the first player looks
  (vinyl, CD, cassette, TP-7) were inspired by it; an independent Compose
  reimplementation, no Spun code or assets

## License

GPL-3.0 since 3.0 (2.x releases were MIT). See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party software and
trademark notices.
