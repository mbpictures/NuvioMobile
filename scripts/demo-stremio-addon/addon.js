#!/usr/bin/env node
/*
 * Nuvio demo Stremio addon — serves Big Buck Bunny as HLS and MP4 streams for testing.
 *
 * It exposes Big Buck Bunny four ways, each as "auth" or "public":
 *   - HLS (Mux multi-variant .m3u8) — exercises playlist rewriting + per-segment auth.
 *   - MP4 (single progressive H.264/AAC file) — a plain, seekable, TV-friendly container, handy for
 *     isolating DLNA/cast problems that only show up with the heavier HLS/MKV paths.
 *   "auth"   — through this addon's own /hls reverse proxy, gated behind an Authorization header.
 *   "public" — the upstream URL directly, no headers.
 *
 * The auth variants are the interesting ones for testing header-authenticated casting: the addon
 * advertises the header via behaviorHints.proxyHeaders.request, and every /hls request (master &
 * variant playlists, segments AND the MP4 body/ranges) returns 401 without it. So it only plays —
 * locally or cast — if the player/cast-proxy forwards the header to every request.
 *
 * No dependencies; Node 18+. Run: `node addon.js` (see README.md for options).
 */
'use strict'

const http = require('http')
const https = require('https')
const os = require('os')

const PORT = Number(process.env.PORT) || 7000
const TOKEN = process.env.TOKEN || 'demo-secret-token'
const PUBLIC_HOST = process.env.PUBLIC_HOST || lanIp() || '127.0.0.1'

// Public Big Buck Bunny multi-variant HLS test stream (requires internet access).
const UPSTREAM_MASTER = process.env.UPSTREAM_MASTER || 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8'
// Public Big Buck Bunny progressive MP4 (H.264 + AAC, 320x180, ~10 min, ~64 MB, HTTP range
// supported). Served by Blender's official download host. Low-res on purpose: a TV-trivial codec, so
// a casting failure points at the pipeline/renderer rather than at decode support.
const UPSTREAM_MP4 = process.env.UPSTREAM_MP4 ||
  'https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4'
// Big Buck Bunny's real IMDb id, so it attaches to the Cinemeta movie entry too.
const MOVIE_ID = 'tt1254207'
const POSTER = 'https://upload.wikimedia.org/wikipedia/commons/c/c5/Big_buck_bunny_poster_big.jpg'

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': '*',
  'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || `${PUBLIC_HOST}:${PORT}`}`)
  const path = decodeURIComponent(url.pathname)

  if (req.method === 'OPTIONS') return end(res, 204, CORS)
  log(`${req.method} ${url.pathname}`)

  try {
    if (path === '/' ) return sendHtml(res)
    if (path === '/manifest.json') return sendJson(res, manifest())
    if (path.startsWith('/catalog/movie/')) return sendJson(res, catalog())
    if (path.startsWith('/meta/movie/')) return sendJson(res, { meta: meta() })
    if (path.startsWith('/stream/movie/')) return sendJson(res, { streams: streams(req) })
    if (path.startsWith('/hls/')) return handleHls(req, res, path)
    return end(res, 404, CORS, 'not found')
  } catch (err) {
    log(`error: ${err && err.message}`)
    return end(res, 500, CORS, 'internal error')
  }
})

server.listen(PORT, '0.0.0.0', () => {
  const base = publicBase()
  console.log('\nNuvio demo Stremio addon running')
  console.log('  manifest:   ' + base + '/manifest.json')
  console.log('  install:    stremio://' + PUBLIC_HOST + ':' + PORT + '/manifest.json')
  console.log('  auth token: ' + TOKEN + '  (sent as "Authorization: Bearer …")')
  console.log('  upstream:   ' + UPSTREAM_MASTER + '  (HLS)')
  console.log('              ' + UPSTREAM_MP4 + '  (MP4)')
  console.log('\nAdd the manifest URL in the app to test. Ctrl+C to stop.\n')
})

// ---- Stremio resources ----

function manifest() {
  return {
    id: 'org.nuvio.demo.bigbuckbunny',
    version: '1.1.0',
    name: 'Nuvio Demo — Big Buck Bunny (HLS + MP4)',
    description: 'Local test addon serving Big Buck Bunny as auth-gated and public HLS and MP4 streams.',
    resources: ['catalog', 'meta', 'stream'],
    types: ['movie'],
    idPrefixes: ['tt'],
    catalogs: [{ type: 'movie', id: 'bbb-demo', name: 'Demo — Big Buck Bunny' }],
    behaviorHints: { configurable: false },
  }
}

function catalog() {
  return {
    metas: [{
      id: MOVIE_ID,
      type: 'movie',
      name: 'Big Buck Bunny',
      poster: POSTER,
      posterShape: 'poster',
      description: 'Open-source animated short — used here as an HLS casting test fixture.',
      releaseInfo: '2008',
    }],
  }
}

function meta() {
  return {
    id: MOVIE_ID,
    type: 'movie',
    name: 'Big Buck Bunny',
    poster: POSTER,
    background: POSTER,
    description: 'Open-source animated short by the Blender Foundation, served as an HLS stream for testing header-authenticated playback and casting.',
    releaseInfo: '2008',
    runtime: '10 min',
    genres: ['Animation', 'Short', 'Comedy'],
  }
}

function streams(req) {
  const base = baseFrom(req)
  return [
    {
      name: 'Demo MP4 (public)',
      title: 'Big Buck Bunny\nMP4 · H.264/AAC · public, no headers',
      url: UPSTREAM_MP4,
      behaviorHints: { bingeGroup: 'nuvio-demo-bbb', filename: 'big-buck-bunny.mp4' },
    },
    {
      name: 'Demo MP4 (auth)',
      title: 'Big Buck Bunny\nMP4 · H.264/AAC · requires Authorization header',
      // The /hls reverse proxy is content-agnostic: it pipes the MP4 through with range support and
      // the same auth gate. A .mp4 suffix keeps the type hint correct (the proxy URL carries no other).
      url: `${base}/hls/${b64UrlEncode(UPSTREAM_MP4)}.mp4`,
      behaviorHints: {
        bingeGroup: 'nuvio-demo-bbb',
        filename: 'big-buck-bunny.mp4',
        proxyHeaders: { request: { Authorization: `Bearer ${TOKEN}` } },
      },
    },
    {
      name: 'Demo HLS (auth)',
      title: 'Big Buck Bunny\nHLS · requires Authorization header',
      // Keep a .m3u8 suffix so the player detects HLS (the proxy URL has no other type hint).
      url: `${base}/hls/${b64UrlEncode(UPSTREAM_MASTER)}.m3u8`,
      behaviorHints: {
        notWebReady: true,
        bingeGroup: 'nuvio-demo-bbb',
        filename: 'big-buck-bunny.m3u8',
        // The app forwards these to every playback request; the cast proxy forwards them to the receiver.
        proxyHeaders: { request: { Authorization: `Bearer ${TOKEN}` } },
      },
    },
    {
      name: 'Demo HLS (public)',
      title: 'Big Buck Bunny\nHLS · public, no headers',
      url: UPSTREAM_MASTER,
      behaviorHints: { notWebReady: true, bingeGroup: 'nuvio-demo-bbb', filename: 'big-buck-bunny.m3u8' },
    },
  ]
}

// ---- Auth-gated HLS reverse proxy ----

function handleHls(req, res, path) {
  const auth = req.headers['authorization'] || ''
  const ua = (req.headers['user-agent'] || '-').slice(0, 50)
  const authState = auth ? (auth === `Bearer ${TOKEN}` ? 'ok' : 'wrong') : 'absent'
  log(`  HLS ${req.method} auth=${authState} ua="${ua}"`)
  if (auth !== `Bearer ${TOKEN}`) {
    log('  -> 401 (missing/invalid Authorization)')
    return end(res, 401, CORS, 'unauthorized')
  }
  // Path is /hls/<base64url>[.ext]; the optional extension is only a type hint for the player.
  const encoded = path.slice('/hls/'.length).split('.')[0]
  const target = b64UrlDecode(encoded)
  if (!target) return end(res, 400, CORS, 'bad target')
  proxyUpstream(target, req, res, baseFrom(req))
}

function proxyUpstream(target, req, res, base) {
  let parsed
  try { parsed = new URL(target) } catch { return end(res, 400, CORS, 'bad url') }
  const mod = parsed.protocol === 'https:' ? https : http

  const headers = { 'user-agent': 'NuvioDemoAddon/1.0' }
  if (req.headers.range) headers.range = req.headers.range

  const upstream = mod.get(target, { headers }, (up) => {
    const contentType = up.headers['content-type'] || ''
    const isPlaylist = contentType.includes('mpegurl') ||
      target.split('?')[0].split('#')[0].toLowerCase().endsWith('.m3u8')

    if (isPlaylist) {
      const chunks = []
      up.on('data', (d) => chunks.push(d))
      up.on('end', () => {
        const rewritten = rewritePlaylist(Buffer.concat(chunks).toString('utf8'), target, base)
        const body = Buffer.from(rewritten, 'utf8')
        res.writeHead(200, {
          ...CORS,
          'Content-Type': 'application/vnd.apple.mpegurl',
          'Content-Length': body.length,
          'Cache-Control': 'no-cache',
        })
        res.end(body)
      })
      up.on('error', () => end(res, 502, CORS, 'upstream error'))
      return
    }

    const out = { ...CORS, 'Accept-Ranges': up.headers['accept-ranges'] || 'bytes' }
    if (up.headers['content-type']) out['Content-Type'] = up.headers['content-type']
    if (up.headers['content-length']) out['Content-Length'] = up.headers['content-length']
    if (up.headers['content-range']) out['Content-Range'] = up.headers['content-range']
    res.writeHead(up.statusCode || 200, out)
    up.pipe(res)
  })
  upstream.on('error', () => end(res, 502, CORS, 'upstream error'))
}

// Rewrite every child URI in an HLS manifest back through this addon's auth-gated /hls proxy.
function rewritePlaylist(body, baseUrl, base) {
  const proxied = (ref) => {
    const abs = resolve(baseUrl, ref)
    const ext = extOf(abs)
    return `${base}/hls/${b64UrlEncode(abs)}${ext ? '.' + ext : ''}`
  }
  return body.split('\n').map((raw) => {
    const line = raw.replace(/\r$/, '')
    if (line.startsWith('#')) {
      // Tag lines: rewrite any embedded URI="..." (EXT-X-KEY, EXT-X-MAP, EXT-X-MEDIA, ...).
      return line.replace(/URI="([^"]*)"/g, (_, ref) => `URI="${proxied(ref)}"`)
    }
    if (line.trim() === '') return line
    // Bare URI lines: variant playlists and media segments.
    return proxied(line.trim())
  }).join('\n')
}

// ---- helpers ----

function resolve(base, ref) {
  if (/^https?:\/\//i.test(ref)) return ref
  try { return new URL(ref, base).href } catch { return ref }
}

// Build absolute addon URLs from the host the client actually used to reach us — robust against
// machines with multiple/virtual network adapters where a guessed LAN IP may be unreachable.
function baseFrom(req) {
  if (process.env.PUBLIC_HOST) return `http://${process.env.PUBLIC_HOST}:${PORT}`
  return `http://${req.headers.host || `${PUBLIC_HOST}:${PORT}`}`
}

// Lowercase file extension of a URL's last path segment (e.g. "m3u8", "ts"), or "" if none.
function extOf(u) {
  const seg = u.split('?')[0].split('#')[0].split('/').pop() || ''
  const dot = seg.lastIndexOf('.')
  const ext = dot >= 0 ? seg.slice(dot + 1).toLowerCase() : ''
  return /^[a-z0-9]{1,5}$/.test(ext) ? ext : ''
}

function b64UrlEncode(s) {
  return Buffer.from(s, 'utf8').toString('base64')
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function b64UrlDecode(s) {
  try {
    return Buffer.from(s.replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8')
  } catch { return null }
}

function publicBase() {
  return `http://${PUBLIC_HOST}:${PORT}`
}

function lanIp() {
  const ifaces = os.networkInterfaces()
  let fallback = null
  for (const name of Object.keys(ifaces)) {
    for (const ni of ifaces[name] || []) {
      if (ni.family !== 'IPv4' || ni.internal) continue
      const a = ni.address
      if (a.startsWith('192.168.') || a.startsWith('10.') ||
        (a.startsWith('172.') && Number(a.split('.')[1]) >= 16 && Number(a.split('.')[1]) <= 31)) {
        if (name.toLowerCase().includes('wi') || name.toLowerCase().startsWith('en')) return a
        fallback = fallback || a
      } else {
        fallback = fallback || a
      }
    }
  }
  return fallback
}

function sendJson(res, obj) {
  const body = Buffer.from(JSON.stringify(obj), 'utf8')
  res.writeHead(200, { ...CORS, 'Content-Type': 'application/json', 'Content-Length': body.length })
  res.end(body)
}

function sendHtml(res) {
  const base = publicBase()
  const body = Buffer.from(
    `<!doctype html><meta charset=utf-8><title>Nuvio demo addon</title>` +
    `<body style="font-family:system-ui;max-width:42rem;margin:3rem auto;line-height:1.5">` +
    `<h1>Nuvio demo Stremio addon</h1>` +
    `<p>Big Buck Bunny as auth-gated and public HLS and MP4 streams, for testing.</p>` +
    `<p>Install URL: <code>${base}/manifest.json</code></p>` +
    `<p><a href="/manifest.json">manifest.json</a></p></body>`, 'utf8')
  res.writeHead(200, { ...CORS, 'Content-Type': 'text/html; charset=utf-8', 'Content-Length': body.length })
  res.end(body)
}

function end(res, code, headers, text = '') {
  const body = Buffer.from(text, 'utf8')
  res.writeHead(code, { ...headers, 'Content-Length': body.length })
  res.end(body)
}

function log(msg) {
  console.log(`[${new Date().toISOString()}] ${msg}`)
}
