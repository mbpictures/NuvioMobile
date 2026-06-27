# Nuvio demo Stremio addon — Big Buck Bunny (HLS + MP4)

A tiny, dependency-free Stremio addon that serves **Big Buck Bunny** as HLS and MP4 streams. It exists
to test header-authenticated playback and casting (Chromecast / DLNA) end-to-end.

It advertises four streams on the Big Buck Bunny movie entry:

| Stream | URL | Headers |
| --- | --- | --- |
| **Demo MP4 (public)** | the upstream Blender MP4 directly | none |
| **Demo MP4 (auth)** | this addon's `/hls` reverse proxy | requires `Authorization: Bearer <token>` |
| **Demo HLS (auth)** | this addon's `/hls` reverse proxy | requires `Authorization: Bearer <token>` |
| **Demo HLS (public)** | the upstream Mux URL directly | none |

The **MP4** variants are a plain progressive H.264/AAC file (seekable, no playlist, TV-friendly) — use
them to isolate DLNA/cast issues that only appear with the heavier HLS or remote-MKV paths.

The **auth** stream is the useful one: every `/hls` request (master playlist, variant playlists, **and
segments**) returns `401` without the header. The addon hands the app the header via
`behaviorHints.proxyHeaders.request`, so it only plays if the header is forwarded to every request —
exactly what the cast proxy must do when casting to a receiver that fetches the URL itself.

## Run

```sh
node scripts/demo-stremio-addon/addon.js
# or
cd scripts/demo-stremio-addon && npm start
```

On startup it prints the install URL, e.g. `http://192.168.1.50:7000/manifest.json`. Add that URL as
an addon in the app. Requires internet access (it reverse-proxies the public Mux test stream).

> The addon binds to `0.0.0.0`. Stream and segment URLs are built from the `Host` the client used to
> reach the addon, so whatever address you install the manifest with is what playback uses — install
> via the machine's real LAN IP (e.g. `http://192.168.1.50:7000/manifest.json`) and phones/Chromecast
> on the same Wi-Fi will reach it. The auto-detected IP in the startup log is only a convenience hint;
> on machines with virtual adapters (WSL/Hyper-V/VirtualBox) it may be wrong — set `PUBLIC_HOST` to
> pin it if needed.

## Options (environment variables)

| Var | Default | Purpose |
| --- | --- | --- |
| `PORT` | `7000` | Listen port. |
| `TOKEN` | `demo-secret-token` | Bearer token required by the `/hls` proxy. |
| `PUBLIC_HOST` | auto-detected LAN IP | Host used in the manifest/stream URLs. Set this if the printed IP isn't reachable from your device. |
| `UPSTREAM_MASTER` | Mux Big Buck Bunny `.m3u8` | Source HLS master to proxy. |
| `UPSTREAM_MP4` | Blender Big Buck Bunny `.mp4` | Source progressive MP4 to serve/proxy. |

```sh
PORT=8080 PUBLIC_HOST=192.168.1.50 TOKEN=letmein node addon.js
```

## How to test the casting feature

1. Start the addon and install its manifest in the app.
2. Open **Big Buck Bunny**, pick **Demo HLS (auth)**, confirm it plays locally (the header is applied
   to every request).
3. Cast to a Chromecast or DLNA renderer. With the local cast proxy, playback should continue on the
   receiver. To prove the header is what matters, try the same against a build without the proxy — the
   receiver gets `401` and fails.

### Isolating a DLNA renderer problem (e.g. a TV that won't start)

Use **Demo MP4 (public)** — a plain H.264/AAC progressive file with a correct `video/mp4` Content-Type
and HTTP range support, played **from the start** (don't resume). If this casts but a large remote MKV
doesn't, the renderer is choking on the codec (e.g. DTS audio) or on seek-heavy/slow init, not on the
cast pipeline. Switch to **Demo MP4 (auth)** to confirm the same works when the body is fetched with a
forwarded `Authorization` header.

## Quick sanity checks (curl)

```sh
# 401 without the header:
curl -i "http://localhost:7000/hls/$(node -e "process.stdout.write(Buffer.from('https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8').toString('base64url'))")"

# 200 with it (returns a rewritten manifest whose child URLs point back at the addon):
curl -i -H "Authorization: Bearer demo-secret-token" \
  "http://localhost:7000/hls/$(node -e "process.stdout.write(Buffer.from('https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8').toString('base64url'))")"
```
