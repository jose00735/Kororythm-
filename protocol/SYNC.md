# Kororythm sync protocol, version 1

The PC (Windows app, `desktop/internal/syncer`) is the server. The phone (Android app,
`app/.../sync`) is the client. Both ends must give the same results for the fixtures in
`protocol/fixtures`, and the tests on both sides check this:

- `desktop/internal/syncer/fingerprint_test.go`
- `app/src/test/.../sync/FingerprintTest.kt`
- `SyncClientInteropTest.kt` runs the Kotlin client against the real Go server.

## Finding the PC

| How | What happens |
|---|---|
| Wi-Fi or USB tethering | The phone sends the UDP datagram `KORORYTHM-DISCOVER 1` to port **47801**. It sends to 255.255.255.255 and to the broadcast address of each of its network interfaces. The PC answers to the sender with a JSON `Hello`: `{"app":"kororythm","v":1,"name":"JOSE-PC","port":47800,"fp":"<sha256 of its certificate>"}`. |
| USB cable with USB debugging on | When the user turns this on in the PC's Sync window, the PC keeps `adb reverse tcp:47800 tcp:47800` set on every phone that adb sees. The phone tries `https://127.0.0.1:47800/v1/hello` when no broadcast reply comes back. |
| Typed address | The user types the IP shown in the PC's Sync window, and the phone calls `GET /v1/hello` on it. |

## Transport

- HTTPS on TCP port **47800**.
- The PC's certificate is self-signed: ECDSA P-256, valid for 20 years. It is stored in
  `%APPDATA%\Kororythm\sync`.
- The phone does not check the certificate against a certificate authority. Instead it pins
  the certificate: it compares the SHA-256 of the certificate's DER bytes with the
  fingerprint it saved when it paired.
- Hostname checks are skipped, because the pin replaces them.

## Pairing

1. The PC's Sync window shows a 6-digit code.
   - The code is valid for 5 minutes and accepts 5 attempts.
   - Once one phone pairs with it, the code is used up.
2. The phone sends `POST /v1/pair` with this body:
   `{device, name, nonce, proof = hex(HMAC-SHA256(code, "kororythm-pair|" + nonce + "|" + certFP))}`
   - `certFP` is the certificate the phone actually received.
   - If a man in the middle presents its own certificate, the proof no longer matches.
3. The PC checks the proof. It answers with
   `{token, name, proof = hex(HMAC-SHA256(code, "kororythm-server|" + nonce + "|" + certFP))}`.
4. The phone checks the PC's proof, then saves `{host, port, fp, token}`.
5. Every later call sends the header `Authorization: Bearer <token>`.

Known limit: an attacker who is actively in the middle during pairing can capture one proof
and try all 10⁶ codes offline, then pair before the code expires. To close this, a later
protocol version should use a PAKE (SPAKE2).

## Identifying a song: the fingerprint

The fingerprint identifies a song by its audio, not its tags, so editing tags on one side does
not make the song look new.

- **FLAC with a STREAMINFO MD5:** `flac-<md5 hex>`.
- **Anything else (MP3, or FLAC without an MD5):**
  1. Skip the ID3v2 tag. Its size is a syncsafe number, plus 10 bytes for the header, plus
     10 more if there is a footer.
  2. For FLAC without an MD5, skip the metadata blocks instead.
  3. At the end of the file, drop an ID3v1 tag (`TAG`, 128 bytes).
  4. Drop an APEv2 tag, using the size in its footer, plus 32 bytes if it also has a header.
  5. Hash up to 4 MiB of what is left with SHA-256.
  6. The fingerprint is `sha-<first 32 hex digits>-<length of the audio part>`.

When the PC sends a 16-bit converted copy, the phone keeps the PC's fingerprint for that
file, so the next sync still sees it as the same song.

## A sync

### 1. Start

The phone sends `POST /v1/sync/start` with
`{device, tracks:[ManifestTrack], playlists:[ManifestPlaylist], options}`.

- `ManifestTrack` is `{fp, name, size, title, artist, album, durationMs, plays, lastPlayed}`.
- `ManifestPlaylist` is `{name, items:[fp], modified}`.
- `options` is `{noDownload, noUpload, only:[playlist names], budget:bytes, maxBits}`.

The PC answers with a `Plan`: `{session, pcName, toPhone:[Transfer], toPc:[Transfer], playlists:[ManifestPlaylist], skipped}`.

The PC builds the plan like this:

- **`toPhone`:** PC songs that the phone does not have.
  - With `only`, just the songs in those PC playlists.
  - Songs are taken in artist and album order until `budget` runs out. Songs left out are
    counted in `skipped`.
  - With `maxBits` set, a song above that bit depth is marked `convert`. The PC then sends a
    16-bit FLAC made with ffmpeg and triangular dither.
- **`toPc`:** phone songs that the PC does not have.
- **`playlists`:** the merged playlists the phone should apply. They are matched by name,
  ignoring case:
  - A playlist that only one side has goes to the other side.
  - When both sides have it, the newer `modified` wins. A tie goes to the PC.
  - Deleting a playlist on one side does not delete it on the other.
  - Each playlist keeps only the songs the phone will have.

### 2. Move the files

- **Download:** `GET /v1/files/{fp}?s=<session>`. Byte ranges are supported (`Range`), so a
  cut-off download can resume.
- **Upload:** first `GET /v1/upload/{fp}` returns `{offset}`. Then
  `PUT /v1/upload/{fp}?s=<session>&offset=<n>` sends the bytes from that offset on.
  - A wrong offset gets a `409` response.
  - When the file is complete, the PC checks that its size and fingerprint match. If they do,
    it saves the file as `Music\Kororythm Sync\<Artist>\<Album>\<name>` and adds it to the
    library. If they don't, it answers `422`, deletes the partial file, and the next sync
    sends it again.
- **Cover art for the animation:** `GET /v1/art/{artId}`.

### 3. Finish

The phone sends `POST /v1/sync/finish` with `{session, plays:{fp:{plays, lastPlayed}}}`.

- For each song, the PC combines the counts as `merged = pc + phone − base`, where `base` is
  the count saved at the last sync with this phone. This way, plays made on both sides since
  the last sync are added together.
- The PC applies the phone's playlists that won the merge, then answers with
  `{plays:{fp:{plays,lastPlayed}}, received, sent}`.
- The phone stores the merged counts.

## Events, for the animations

The PC sends `Event{kind, device, dir, title, artist, artId, done, total, bytes, totalBytes, message}`
to its Sync window. `kind` is one of `code`, `paired`, `start`, `plan`, `file`, `progress`,
`received`, `done`, `error`.

The phone builds the same picture from its own `SyncBus`.
