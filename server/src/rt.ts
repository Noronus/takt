// Realtime: polls the Germany-wide DELFI GTFS-RT feed, keeps only trips of our area and
// serves the result to MOTIS. Polls fast only while the app is in use.
import { readFile } from 'node:fs/promises'
import http from 'node:http'
import https from 'node:https'
import { createGunzip } from 'node:zlib'
import GtfsRealtimeBindings from 'gtfs-realtime-bindings'
import { config } from './config.ts'
import { paths } from './data.ts'

const { transit_realtime: TR } = GtfsRealtimeBindings

let tripIds = new Map<string, Set<string>>()  // dataset tag -> trip ids
let lastAppUse = 0
let etag = ''
let timer: NodeJS.Timeout | undefined

export const rt = {
  pb: {} as Record<string, Uint8Array>,  // filtered feed per dataset
  feedTimestamp: 0,      // unix s, from the source feed
  fetchedAt: 0,          // unix ms
  entities: 0,
  tripUpdates: 0,
  cancelled: 0,
  bytes: 0,
  error: '',
}

export function touchApp() {
  const wasIdle = Date.now() - lastAppUse > config.activeWindow * 1000
  lastAppUse = Date.now()
  // coming back from idle: refresh now if the data is older than the active interval
  if (wasIdle && Date.now() - rt.fetchedAt > config.rtActiveInterval * 1000) schedule(0)
}

export async function loadTripIds() {
  const m = new Map<string, Set<string>>()
  for (const d of config.datasets) {
    try {
      const t = await readFile(paths.tripIds(d.tag), 'utf8')
      m.set(d.tag, new Set(t.split('\n').filter(Boolean)))
    } catch { m.set(d.tag, new Set()) }
    console.log(`[rt] ${d.tag}: ${m.get(d.tag)!.size} Fahrten im Filter`)
  }
  tripIds = m
}

// Plain node:http instead of fetch: aborting a stalled download with fetch (undici, Node 24)
// can crash the whole process with "assert(!this.paused)".
const MAX_FEED = 100 * 1024 * 1024
export function download(url: string, headers: Record<string, string>, timeoutMs: number): Promise<{ status: number, etag: string, body: Buffer }> {
  return new Promise((resolve, reject) => {
    let done = false
    const finish = (err: Error | null, v?: { status: number, etag: string, body: Buffer }) => {
      if (done) return
      done = true; clearTimeout(timer)
      if (err) { req.destroy(); reject(err) } else resolve(v!)
    }
    const req = (url.startsWith('https:') ? https : http).get(url, { headers }, res => {
      const status = res.statusCode || 0
      const tag = String(res.headers.etag || '')
      if (status !== 200) { res.resume(); return finish(null, { status, etag: tag, body: Buffer.alloc(0) }) }
      const stream = res.headers['content-encoding'] === 'gzip' ? res.pipe(createGunzip()) : res
      const chunks: Buffer[] = []
      let size = 0
      stream.on('data', (c: Buffer) => {
        size += c.length
        if (size > MAX_FEED) finish(new Error('Feed zu groß'))
        else chunks.push(c)
      })
      stream.on('end', () => finish(null, { status, etag: tag, body: Buffer.concat(chunks) }))
      stream.on('error', e => finish(e))
      res.on('error', e => finish(e))
      res.on('close', () => { if (!res.complete) finish(new Error('Verbindung abgebrochen')) })
    })
    req.on('error', e => finish(e))
    const timer = setTimeout(() => finish(new Error('Zeitüberschreitung')), timeoutMs)
  })
}

async function poll() {
  try {
    const headers: Record<string, string> = { 'user-agent': config.userAgent, 'accept-encoding': 'gzip' }
    if (etag) headers['if-none-match'] = etag
    const res = await download(config.rtUrl, headers, 60_000)
    if (res.status === 304) { rt.fetchedAt = Date.now(); return }
    if (res.status !== 200) throw new Error(`HTTP ${res.status}`)
    const buf = new Uint8Array(res.body)
    etag = res.etag
    const feed = TR.FeedMessage.decode(buf)
    let keepAll: typeof feed.entity = []
    for (const [tag, ids] of tripIds) {
      const keep = feed.entity.filter(e => {
        if (e.tripUpdate) return ids.has(e.tripUpdate.trip?.tripId || '')
        if (e.vehicle) return ids.has(e.vehicle.trip?.tripId || '')
        if (e.alert) return (e.alert.informedEntity || []).some(ie => ie.trip?.tripId && ids.has(ie.trip.tripId))
        return false
      })
      rt.pb[tag] = TR.FeedMessage.encode(TR.FeedMessage.create({ header: feed.header, entity: keep })).finish()
      keepAll = keepAll.concat(keep)
    }
    const keep = keepAll
    rt.feedTimestamp = Number(feed.header.timestamp || 0)
    rt.fetchedAt = Date.now()
    rt.entities = feed.entity.length
    rt.tripUpdates = keep.filter(e => e.tripUpdate).length
    rt.cancelled = keep.filter(e => e.tripUpdate?.trip?.scheduleRelationship === TR.TripDescriptor.ScheduleRelationship.CANCELED).length
    rt.bytes = buf.length
    rt.error = ''
  } catch (e: any) {
    rt.error = `${new Date().toISOString()} ${e.message}`
    console.log(`[rt] Fehler: ${e.message}`)
  }
}

function schedule(delayMs: number) {
  clearTimeout(timer)
  timer = setTimeout(async () => {
    await poll()
    const active = Date.now() - lastAppUse < config.activeWindow * 1000
    schedule((active ? config.rtActiveInterval : config.rtIdleInterval) * 1000)
  }, delayMs)
}

export function pollNow() { schedule(0) }

export async function startRt() {
  await loadTripIds()
  schedule(0)
}
