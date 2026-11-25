// Access control for running the API on the public internet (behind the NetBird reverse proxy).
// - one token per person in /data/tokens.txt, reloaded on change, so access can be revoked without a restart
// - requests that came through a proxy may only reach /api/ and always need a token (fail closed)
// - rate limits per person and per IP for failed logins
import { createHash } from 'node:crypto'
import { readFile, stat } from 'node:fs/promises'
import type { FastifyReply, FastifyRequest } from 'fastify'
import { config } from './config.ts'

const MIN_TOKEN_LEN = 32
const sha = (s: string) => createHash('sha256').update(s).digest('hex')

// sha256(token) -> name. Lookup by hash: the request token is never compared byte by byte.
let tokens = new Map<string, string>()
let fileMtime = -1

function parseTokens(text: string, source: string): Map<string, string> {
  const m = new Map<string, string>()
  for (const [i, raw] of text.split('\n').entries()) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const [name, token, ...rest] = line.split(/\s+/)
    if (!token || rest.length) { console.log(`[auth] ${source}:${i + 1} ignoriert (Format: NAME TOKEN)`); continue }
    if (token.length < MIN_TOKEN_LEN) { console.log(`[auth] ${source}:${i + 1} (${name}) ignoriert: Token kürzer als ${MIN_TOKEN_LEN} Zeichen`); continue }
    m.set(sha(token), name)
  }
  return m
}

async function reloadTokens() {
  const m = new Map<string, string>()
  if (config.apiToken) {
    if (config.apiToken.length < MIN_TOKEN_LEN) console.log(`[auth] API_TOKEN ignoriert: kürzer als ${MIN_TOKEN_LEN} Zeichen`)
    else m.set(sha(config.apiToken), 'default')
  }
  let mtime = 0
  try {
    const s = await stat(config.tokensFile)
    mtime = s.mtimeMs
    if (mtime === fileMtime) return
    for (const [k, v] of parseTokens(await readFile(config.tokensFile, 'utf8'), config.tokensFile)) m.set(k, v)
  } catch {
    if (fileMtime === 0) return
  }
  fileMtime = mtime
  tokens = m
  console.log(`[auth] ${tokens.size} Token aktiv${tokens.size ? ': ' + [...new Set(tokens.values())].join(', ') : ''}`)
}

export async function initAuth() {
  await reloadTokens()
  setInterval(() => reloadTokens().catch(() => {}), 10_000).unref()
  if (config.requireToken && !tokens.size) {
    console.log(`[auth] REQUIRE_TOKEN ist gesetzt, aber kein gültiges Token in ${config.tokensFile} oder API_TOKEN. Abbruch.`)
    process.exit(1)
  }
}

// ---------- client identity ----------

// The NetBird proxy sets X-Real-IP / X-Forwarded-For. Direct LAN clients (the app in the VPN, MOTIS) do not.
export const viaProxy = (req: FastifyRequest) => !!(req.headers['x-real-ip'] || req.headers['x-forwarded-for'])

export function clientIp(req: FastifyRequest): string {
  const real = req.headers['x-real-ip']
  if (typeof real === 'string' && real) return real.trim()
  const xff = req.headers['x-forwarded-for']
  if (typeof xff === 'string' && xff) return xff.split(',').pop()!.trim()
  return req.socket.remoteAddress || '?'
}

// ---------- rate limits (in memory, fixed windows) ----------

class Limiter {
  private hits = new Map<string, { n: number, until: number }>()
  private max: number
  private windowMs: number
  constructor(max: number, windowMs: number) { this.max = max; this.windowMs = windowMs }
  // counts one hit; true if the key is over the limit
  hit(key: string): boolean {
    const now = Date.now()
    let e = this.hits.get(key)
    if (!e || e.until < now) { e = { n: 0, until: now + this.windowMs }; this.hits.set(key, e) }
    e.n++
    if (this.hits.size > 10_000) for (const [k, v] of this.hits) if (v.until < now) this.hits.delete(k)
    return e.n > this.max
  }
  blocked(key: string): boolean {
    const e = this.hits.get(key)
    return !!e && e.until >= Date.now() && e.n > this.max
  }
  retryAfter(key: string): number {
    const e = this.hits.get(key)
    return e ? Math.max(1, Math.ceil((e.until - Date.now()) / 1000)) : 60
  }
}

const failed = new Limiter(10, 15 * 60_000)              // wrong tokens per IP
const perUser = new Limiter(config.rateLimit, 60_000)    // requests per person per minute

// ---------- hooks ----------

declare module 'fastify' {
  interface FastifyRequest { user?: string }
}

function deny(reply: FastifyReply, code: number, error: string, retryAfter?: number) {
  if (retryAfter) reply.header('retry-after', String(retryAfter))
  return reply.code(code).send({ error })
}

const lastSeen = new Map<string, number>()
// "1.2.3.4 via proxy 10.0.3.5" or "10.0.1.136 direkt"
const where = (req: FastifyRequest) => viaProxy(req) ? `${clientIp(req)} via proxy ${req.socket.remoteAddress}` : `${clientIp(req)} direkt`

export async function authHook(req: FastifyRequest, reply: FastifyReply) {
  const proxied = viaProxy(req)
  const isApi = req.url.startsWith('/api/')
  // from the internet only the app API exists; /rt/*.pb and /icon.png are for MOTIS and Unraid in the LAN
  if (proxied && !isApi) {
    console.log(`[proxy] 404 ${where(req)} ${req.method} ${req.url.split('?')[0].slice(0, 80)}`)
    return deny(reply, 404, 'not found')
  }
  if (!isApi) return

  // no tokens configured: open in the LAN only, never through the proxy
  if (!tokens.size) {
    if (proxied) return deny(reply, 403, 'Server nicht freigegeben')
    req.user = 'lan'
    return
  }

  const ip = clientIp(req)
  const h = req.headers.authorization
  const name = typeof h === 'string' && h.startsWith('Bearer ') ? tokens.get(sha(h.slice(7).trim())) : undefined
  if (!name) {
    // a blocked IP gets 429 for wrong tokens; a correct token (unguessable, >= 32 chars) always passes,
    // so someone who first tried without a token is not locked out after entering it
    if (failed.blocked(ip)) return deny(reply, 429, 'zu viele Fehlversuche', failed.retryAfter(ip))
    failed.hit(ip)
    console.log(`[auth] abgelehnt: ${where(req)} ${req.method} ${req.url.split('?')[0].slice(0, 80)}`)
    return deny(reply, 401, 'token')
  }
  if (perUser.hit(name)) return deny(reply, 429, 'zu viele Anfragen', perUser.retryAfter(name))
  req.user = name
  // one line per person and hour, so it is visible who uses it and by which path
  if ((lastSeen.get(name) ?? 0) < Date.now() - 3600_000) {
    lastSeen.set(name, Date.now())
    console.log(`[auth] aktiv: ${name} von ${where(req)}`)
  }
}

export async function headersHook(req: FastifyRequest, reply: FastifyReply) {
  // reached over HTTPS through the proxy: browsers must never fall back to plain HTTP for this host
  if (viaProxy(req)) reply.header('strict-transport-security', 'max-age=31536000')
  reply.header('x-content-type-options', 'nosniff')
  reply.header('x-frame-options', 'DENY')
  reply.header('referrer-policy', 'no-referrer')
  reply.header('content-security-policy', "default-src 'none'; frame-ancestors 'none'")
  if (!reply.hasHeader('cache-control')) reply.header('cache-control', 'no-store')
}

// ---------- concurrency cap for expensive MOTIS calls ----------

let inFlight = 0
export async function limited<T>(max: number, f: () => Promise<T>): Promise<T | 'busy'> {
  if (inFlight >= max) return 'busy'
  inFlight++
  try { return await f() } finally { inFlight-- }
}
