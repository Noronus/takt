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
export type Entity = GtfsRealtimeBindings.transit_realtime.IFeedEntity

let tripIds = new Map<string, Set<string>>()  // dataset tag -> trip ids
let lastAppUse = 0
let etag = ''
let timer: NodeJS.Timeout | undefined
let delfi: { header: GtfsRealtimeBindings.transit_realtime.IFeedHeader | null, byTag: Record<string, Entity[]> } = { header: null, byTag: {} }
// fresher TripUpdates from private plug-ins (see plugins.ts), per plug-in and dataset
const overlays = new Map<string, { ts: number, byTag: Record<string, Entity[]> }>()
// what the settings screen shows per plug-in
export const pluginInfo = new Map<string, { label: string, role: string, fetchedAt: number, error: string }>()

export const rt = {
  pb: {} as Record<string, Uint8Array>,  // filtered feed per dataset
  feedTimestamp: 0,      // unix s, the freshest source (DELFI or a plug-in)
  delfiTimestamp: 0,     // unix s, header of the DELFI feed
  fetchedAt: 0,          // unix ms
  entities: 0,
  tripUpdates: 0,
  cancelled: 0,
  bytes: 0,
  error: '',
  overlay: {} as Record<string, { ts: number, trips: number, replaced: number }>,
}

export const isActive = () => Date.now() - lastAppUse < config.activeWindow * 1000

export function setOverlay(name: string, byTag: Record<string, Entity[]>, ts: number) {
  overlays.set(name, { ts, byTag })
  const i = pluginInfo.get(name); if (i) { i.fetchedAt = Date.now(); i.error = '' }
  encode()
}

const CANCELED = TR.TripDescriptor.ScheduleRelationship.CANCELED
const SKIPPED = TR.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED

// An overlay is fresher from its first stop on. Before that DELFI keeps its (often actual) times,
// and stops DELFI marks as skipped (detours) stay skipped.
function merge(over: Entity, base: Entity): Entity {
  const own = (over.tripUpdate!.stopTimeUpdate || []).filter(u => u.stopSequence != null)
  if (!own.length) return over
  const from = Math.min(...own.map(u => u.stopSequence!))
  const seen = new Set(own.map(u => u.stopSequence))
  const keep = (base.tripUpdate?.stopTimeUpdate || []).filter(u => u.stopSequence != null && !seen.has(u.stopSequence) &&
    (u.stopSequence! < from || u.scheduleRelationship === SKIPPED))
  if (!keep.length) return over
  const all = [...own, ...keep].sort((a, b) => a.stopSequence! - b.stopSequence!)
  return { ...over, tripUpdate: { ...over.tripUpdate!, stopTimeUpdate: all } }
}

// DELFI plus the plug-in overlays -> one feed per dataset for MOTIS
function encode() {
  if (!delfi.header) return
  // the data age the app shows: the freshest source that covers trips
  let ts = Number(delfi.header.timestamp || 0)
  for (const o of overlays.values()) if (o.ts > ts && Object.values(o.byTag).some(l => l.length)) ts = o.ts
  const stats: typeof rt.overlay = {}
  for (const [tag, base] of Object.entries(delfi.byTag)) {
    const over = new Map<string, { e: Entity, name: string }>()
    for (const [name, o] of overlays) {
      stats[name] ??= { ts: o.ts, trips: 0, replaced: 0 }
      for (const e of o.byTag[tag] || []) {
        const id = e.tripUpdate?.trip?.tripId
        if (id && !over.has(id)) { over.set(id, { e, name }); stats[name].trips++ }
      }
    }
    const out: Entity[] = []
    for (const e of base) {
      const id = e.tripUpdate?.trip?.tripId
      const o = id ? over.get(id) : undefined
      if (!o) { out.push(e); continue }
      over.delete(id!)
      if (e.tripUpdate!.trip!.scheduleRelationship === CANCELED) { out.push(e); continue }
      out.push(merge(o.e, e)); stats[o.name].replaced++
    }
    for (const o of over.values()) out.push(o.e)
    rt.pb[tag] = TR.FeedMessage.encode(TR.FeedMessage.create({ header: { ...delfi.header, timestamp: ts }, entity: out })).finish()
  }
  rt.feedTimestamp = ts
  rt.overlay = stats
}

export function touchApp() {
  const wasIdle = !isActive()
  lastAppUse = Date.now()
  // coming back from idle: switch the pending slow timer to the active interval (now, if the data is older)
  if (wasIdle) schedule(Math.max(0, rt.fetchedAt + config.rtActiveInterval * 1000 - Date.now()))
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
    const byTag: Record<string, Entity[]> = {}
    for (const [tag, ids] of tripIds) {
      const keep = feed.entity.filter(e => {
        if (e.tripUpdate) return ids.has(e.tripUpdate.trip?.tripId || '')
        if (e.vehicle) return ids.has(e.vehicle.trip?.tripId || '')
        if (e.alert) return (e.alert.informedEntity || []).some(ie => ie.trip?.tripId && ids.has(ie.trip.tripId))
        return false
      })
      byTag[tag] = keep
      keepAll = keepAll.concat(keep)
    }
    const keep = keepAll
    delfi = { header: feed.header, byTag }
    rt.delfiTimestamp = Number(feed.header.timestamp || 0)
    encode()
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
    schedule((isActive() ? config.rtActiveInterval : config.rtIdleInterval) * 1000)
  }, delayMs)
}

export function pollNow() { schedule(0) }

export async function startRt() {
  await loadTripIds()
  schedule(0)
}
