import Fastify from 'fastify'
import compress from '@fastify/compress'
import { readFile } from 'node:fs/promises'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { config } from './config.ts'
import { initData, onStaticChange, state, busy, paths } from './data.ts'
import { rt, startRt, loadTripIds, touchApp, pollNow } from './rt.ts'
import { initAuth, authHook, headersHook, viaProxy, limited } from './security.ts'
import { insaStationId, insaDepartures, insaAlerts, loadInsaMap, normLine, type InsaDep } from './insa.ts'

// ---------- stations ----------

interface Station { id: string, name: string, lat: number, lon: number, modes: number, stopIds: string[] }
let stations = new Map<string, Station>()
let stopToStation = new Map<string, Station>()
let stationsBody = ''
let stationsEtag = ''

async function loadStations() {
  try {
    stationsBody = await readFile(paths.stations, 'utf8')
    const j = JSON.parse(stationsBody)
    stationsEtag = `"${j.version}"`
    stations = new Map(); stopToStation = new Map()
    for (const [id, name, lat, lon, modes, ids] of j.stations) {
      const s: Station = { id, name, lat, lon, modes, stopIds: ids || [id] }
      stations.set(id, s)
      for (const x of s.stopIds) stopToStation.set(x, s)
    }
    console.log(`[api] ${stations.size} Haltestellen geladen`)
  } catch { stationsBody = '' }
}

// ---------- MOTIS ----------


async function motis(path: string, params: Record<string, string | number | boolean | undefined>, timeoutMs = 8000): Promise<any> {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') q.set(k, String(v))
  const res = await fetch(`${config.motisUrl}${path}?${q}`, { signal: AbortSignal.timeout(timeoutMs) })
  if (!res.ok) throw new Error(`MOTIS ${path}: HTTP ${res.status} ${(await res.text()).slice(0, 200)}`)
  return res.json()
}

type AppMode = 'tram' | 'bus' | 'sbahn' | 'train' | 'subway' | 'ferry' | 'other'
function appMode(m: string, line: string): AppMode {
  switch (m) {
    case 'TRAM': return 'tram'
    case 'BUS': case 'COACH': case 'ODM': return 'bus'
    case 'SUBURBAN': return 'sbahn'
    case 'SUBWAY': case 'METRO': return 'subway'
    case 'FERRY': return 'ferry'
    case 'RAIL': case 'REGIONAL_RAIL': case 'REGIONAL_FAST_RAIL': case 'HIGHSPEED_RAIL': case 'LONG_DISTANCE': case 'NIGHT_RAIL':
      return /^S\s?\d/.test(line) ? 'sbahn' : 'train'
    default: return 'other'
  }
}
const isRail = (m: AppMode) => m === 'sbahn' || m === 'train'
const sec = (iso?: string) => iso ? Math.round(Date.parse(iso) / 1000) : null

interface Dep {
  trip: string; line: string; mode: AppMode; color: string | null; textColor: string | null
  dir: string; t: number; rt: number | null; cancelled: boolean
  track: string | null; plannedTrack: string | null; stop: string; src: 'delfi' | 'insa' | null
  notes: string[]
  info: string[]
}

// the realtime feed attaches its attribution as an alert to every trip; that is not a disruption
const ATTRIBUTION = /gtfs\.de|bereitgestellt von DELFI/i
// short vehicle facts sent as alerts ("Niederflur") are info, not warnings
const INFO = /^(niederflur|hochflur|barrierefrei|fahrradmitnahme|klimatisiert|rollstuhl|wlan)/i
function allAlertTexts(alerts?: any[]): string[] {
  return [...new Set<string>((alerts || []).map(a => a.headerText || a.descriptionText).filter((t: string) => t && !ATTRIBUTION.test(t)))]
}
function alertTexts(alerts?: any[]): string[] { return allAlertTexts(alerts).filter(t => !(INFO.test(t) && t.length < 40)) }
function infoTexts(alerts?: any[]): string[] { return allAlertTexts(alerts).filter(t => INFO.test(t) && t.length < 40) }

function fromStopTime(s: any): Dep {
  const line = s.displayName || s.routeShortName || s.tripShortName || ''
  const p = s.place || {}
  return {
    trip: s.tripId,
    line,
    mode: appMode(s.mode, line),
    color: s.routeColor ? `#${s.routeColor}` : null,
    textColor: s.routeTextColor ? `#${s.routeTextColor}` : null,
    dir: s.headsign || s.tripTo?.name || '',
    t: sec(p.scheduledDeparture)!,
    rt: s.realTime ? sec(p.departure) : null,
    cancelled: !!(s.cancelled || s.tripCancelled || p.cancelled),
    track: p.track || null,
    plannedTrack: p.scheduledTrack || null,
    stop: p.name || '',
    src: s.realTime ? 'delfi' : null,
    notes: alertTexts(p.alerts),
    info: infoTexts(p.alerts),
  }
}

function matchInsa(line: string, planned: number, list: InsaDep[]): InsaDep | undefined {
  const l = normLine(line)
  return list.find(d => normLine(d.line) === l && Math.abs(d.planned - planned) < 60)
}

// INSA for trains: platform numbers (missing in the GTFS) and realtime where DELFI has none
async function enrichRail(st: Station, deps: Dep[]) {
  const need = deps.filter(d => isRail(d.mode) && (d.src === null || !d.track))
  if (!need.length || !config.insaEnabled) return
  try {
    const id = await insaStationId(st, true)
    if (!id) return
    const from = Math.min(...need.map(d => d.t)) - 120
    const to = Math.max(...need.map(d => d.t)) + 120
    const list = await insaDepartures(id, new Date(from * 1000), Math.min(240, Math.ceil((to - from) / 60)), true)
    for (const d of need) {
      const m = matchInsa(d.line, d.t, list)
      if (!m) continue
      if (!d.track) { d.track = m.platform; d.plannedTrack = m.plannedPlatform }
      if (d.src === null) {
        d.src = 'insa'
        d.rt = m.when ?? (m.delay !== null ? d.t + m.delay : d.t)
        if (m.platform) d.track = m.platform
      }
      d.cancelled = d.cancelled || m.cancelled
      d.notes.push(...m.remarks.filter(r => !d.notes.includes(r)))
    }
  } catch (e: any) { console.log(`[insa] ${e.message}`) }
}

async function insaFallback(st: Station, from: Date, minutes: number): Promise<Dep[]> {
  const id = await insaStationId(st, false)
  if (!id) return []
  const list = await insaDepartures(id, from, minutes)
  return list.map(d => {
    const mode: AppMode = d.product === 'tram' ? 'tram' : d.product === 'bus' ? 'bus' : d.product === 'suburban' ? 'sbahn'
      : ['regional', 'national', 'nationalExpress'].includes(d.product) ? 'train' : 'other'
    return {
      trip: d.tripId, line: d.line.replace(/^(Str|Bus)\s+/, ''), mode, color: null, textColor: null, dir: d.direction,
      t: d.planned, rt: d.when ?? (d.delay !== null ? d.planned + d.delay : null), cancelled: d.cancelled,
      track: d.platform, plannedTrack: d.plannedPlatform, stop: st.name, src: d.delay !== null || d.cancelled ? 'insa' : null, notes: d.remarks, info: [],
    }
  })
}

// ---------- HTTP ----------

const app = Fastify({
  logger: false,
  bodyLimit: 1024,          // the API is GET only
  routerOptions: { maxParamLength: 64 },
  connectionTimeout: 30_000,
  requestTimeout: 30_000,
  return503OnClosing: true,
})
await app.register(compress, { threshold: 1024 })

app.addHook('onRequest', authHook)
// only real app use (after the token check) switches the realtime feed to fast polling
app.addHook('preHandler', async req => { if (req.user) touchApp() })
app.addHook('onSend', headersHook)
app.setNotFoundHandler((_req, reply) => { reply.code(404).send({ error: 'not found' }) })
app.setErrorHandler((e: any, _req, reply) => {
  console.log(`[api] ${e.message}`)
  const code = e.statusCode && e.statusCode < 500 ? e.statusCode : 500
  reply.code(code).send({ error: code < 500 ? 'ungültige Anfrage' : 'Serverfehler' })
})

// ---------- input checks ----------

const ID = /^[\w:.\-|]{1,120}$/
// the app sends full double precision ("51.33971234567891,12.37312345678901")
const COORD = /^(-?\d{1,3}(?:\.\d{1,20})?),(-?\d{1,3}(?:\.\d{1,20})?)$/
const badRequest = (reply: any, what: string) => reply.code(400).send({ error: `ungültig: ${what}` })
// unix seconds within ±60 days, or undefined; null if invalid
function unixTime(v: string | undefined): Date | undefined | null {
  if (v === undefined || v === '') return undefined
  if (!/^\d{9,11}$/.test(v)) return null
  const t = Number(v) * 1000
  return Math.abs(t - Date.now()) <= 60 * 86400_000 ? new Date(t) : null
}

app.get('/rt/:tag.pb', async (req, reply) => {
  const pb = rt.pb[(req.params as any).tag]
  if (!pb) return reply.code(404).send()
  return reply.type('application/x-protobuf').send(Buffer.from(pb))
})

const here = dirname(fileURLToPath(import.meta.url))
app.get('/icon.png', async (_req, reply) => reply.type('image/png').send(await readFile(join(here, 'icon.png'))))

const rtInfo = () => ({
  feedAge: rt.feedTimestamp ? Math.round(Date.now() / 1000 - rt.feedTimestamp) : null,
  fetchedAt: rt.fetchedAt ? Math.round(rt.fetchedAt / 1000) : null,
})

app.get('/api/v1/status', async req => {
  const motisState = await motis('/api/v1/health', {}, 3000).then(() => 'ok', (e: any) => e.message)
  // through the proxy: only what the app's connection test shows, no internal hosts or errors
  if (viaProxy(req)) return {
    stations: stations.size, motis: motisState === 'ok' ? 'ok' : 'Fehler',
    rt: { tripUpdates: rt.tripUpdates, ...rtInfo() },
  }
  return statusFull(motisState)
})

const statusFull = (motisState: string) => ({
  state, busy, stations: stations.size,
  rt: { ...rt, pb: undefined, pbBytes: Object.fromEntries(Object.entries(rt.pb).map(([k, v]) => [k, v.length])) },
  motis: motisState,
})

app.get('/api/v1/stations', async (req, reply) => {
  if (!stationsBody) return reply.code(503).send({ error: 'Haltestellen werden noch aufbereitet' })
  reply.header('etag', stationsEtag).header('cache-control', 'no-cache')
  if (req.headers['if-none-match'] === stationsEtag) return reply.code(304).send()
  return reply.type('application/json').send(stationsBody)
})

app.get('/api/v1/departures', async (req, reply) => {
  const q = req.query as Record<string, string>
  const st = typeof q.station === 'string' ? stations.get(q.station) : undefined
  if (!st) return reply.code(404).send({ error: 'unbekannte Haltestelle' })
  const t = unixTime(q.time)
  if (t === null) return badRequest(reply, 'time')
  const time = t ?? new Date()
  const n = Math.max(1, Math.min(80, Math.floor(Number(q.n)) || 40))
  const now = Math.round(Date.now() / 1000)
  try {
    const seen = new Set<string>()
    const deps: Dep[] = []
    const results = await Promise.all(st.stopIds.map(id => motis('/api/v6/stoptimes', {
      stopId: id, time: time.toISOString(), n, window: 5400, language: 'de',
    })))
    for (const r of results) for (const s of r.stopTimes || []) {
      const d = fromStopTime(s)
      const k = `${d.trip}|${d.t}`
      if (seen.has(k)) continue
      seen.add(k); deps.push(d)
    }
    await enrichRail(st, deps)
    deps.sort((a, b) => (a.rt ?? a.t) - (b.rt ?? b.t))
    return { station: { id: st.id, name: st.name }, now, source: 'motis', rt: rtInfo(), departures: deps.slice(0, n) }
  } catch (e: any) {
    console.log(`[api] departures: ${e.message}`)
    if (!config.insaEnabled) return reply.code(502).send({ error: 'MOTIS nicht erreichbar' })
    try {
      const deps = await insaFallback(st, time, 90)
      return { station: { id: st.id, name: st.name }, now, source: 'insa', rt: rtInfo(), departures: deps.slice(0, n) }
    } catch (e2: any) {
      console.log(`[api] departures insa: ${e2.message}`)
      return reply.code(502).send({ error: 'keine Quelle erreichbar' })
    }
  }
})

// a known station id or "lat,lon"; anything else is rejected instead of being passed to MOTIS
function place(p: unknown): string | null {
  if (typeof p !== 'string') return null
  const c = COORD.exec(p)
  if (c) {
    const lat = Number(c[1]), lon = Number(c[2])
    if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null
    // ~10 cm is plenty for routing
    return `${lat.toFixed(6)},${lon.toFixed(6)}`
  }
  return stations.get(p)?.stopIds[0] ?? null
}

function mapPlace(p: any, dep: boolean) {
  const st = stopToStation.get(p.parentId) || stopToStation.get(p.stopId)
  return {
    // MOTIS names coordinate places "START"/"END"; the app shows its own name for those
    name: p.name === 'START' || p.name === 'END' ? '' : p.name, station: st?.id || null, lat: p.lat, lon: p.lon,
    t: sec(dep ? p.scheduledDeparture : p.scheduledArrival),
    rt: sec(dep ? p.departure : p.arrival),
    track: p.track || null, plannedTrack: p.scheduledTrack || null,
    cancelled: !!p.cancelled,
  }
}

function mapLeg(l: any) {
  const line = l.displayName || l.routeShortName || l.tripShortName || ''
  const transit = !['WALK', 'BIKE', 'CAR', 'RENTAL', 'FLEX'].includes(l.mode)
  return {
    mode: transit ? appMode(l.mode, line) : 'walk',
    line, trip: l.tripId || null,
    color: l.routeColor ? `#${l.routeColor}` : null, textColor: l.routeTextColor ? `#${l.routeTextColor}` : null,
    dir: l.headsign || '',
    // walking legs follow the realtime of the legs around them, so their actual times are always valid
    from: { ...mapPlace(l.from, true), t: sec(l.scheduledStartTime), rt: l.realTime || !transit ? sec(l.startTime) : null },
    to: { ...mapPlace(l.to, false), t: sec(l.scheduledEndTime), rt: l.realTime || !transit ? sec(l.endTime) : null },
    realtime: !!l.realTime, src: l.realTime ? 'delfi' : null as null | 'delfi' | 'insa',
    cancelled: !!l.cancelled, duration: l.duration, distance: l.distance ? Math.round(l.distance) : null,
    stops: (l.intermediateStops || []).length,
    // encoded polyline (Google format) of the leg: real paths for walking, OSM-matched shapes for transit
    geometry: l.legGeometry?.points ? { points: l.legGeometry.points, precision: l.legGeometry.precision ?? 6 } : null,
    notes: alertTexts(l.alerts),
    info: infoTexts(l.alerts),
  }
}

app.get('/api/v1/plan', async (req, reply) => {
  const q = req.query as Record<string, string>
  if (!q.from || !q.to) return reply.code(400).send({ error: 'from und to fehlen' })
  const fromPlace = place(q.from), toPlace = place(q.to)
  if (!fromPlace) return badRequest(reply, 'from')
  if (!toPlace) return badRequest(reply, 'to')
  const time = unixTime(q.time)
  if (time === null) return badRequest(reply, 'time')
  if (q.cursor !== undefined && (typeof q.cursor !== 'string' || q.cursor.length > 600)) return badRequest(reply, 'cursor')
  try {
    const r = await limited(config.maxPlans, () => motis('/api/v6/plan', {
      fromPlace, toPlace,
      time: time?.toISOString(),
      arriveBy: q.arriveBy === '1' || q.arriveBy === 'true',
      pageCursor: q.cursor, detailedTransfers: true, joinInterlinedLegs: true,
      maxMatchingDistance: 250, language: 'de', numItineraries: 6,
    }, 15000))
    if (r === 'busy') return reply.code(503).header('retry-after', '3').send({ error: 'Server ausgelastet, gleich nochmal versuchen' })
    const its = (r.itineraries || []).map((it: any) => ({
      id: it.id, transfers: it.transfers, duration: it.duration,
      legs: it.legs.map(mapLeg),
    }))
    // INSA realtime for rail legs (DELFI has none for DB Regio/S-Bahn)
    if (config.insaEnabled) {
      const jobs: Promise<void>[] = []
      for (const it of its) for (const l of it.legs) {
        if (!isRail(l.mode) || l.realtime || !l.from.station) continue
        const st = stations.get(l.from.station)
        if (!st) continue
        jobs.push((async () => {
          try {
            const id = await insaStationId(st, true)
            if (!id) return
            const list = await insaDepartures(id, new Date((l.from.t! - 600) * 1000), 60, true)
            const m = matchInsa(l.line, l.from.t!, list)
            if (!m) return
            const delay = m.when ? m.when - m.planned : (m.delay ?? 0)
            l.src = 'insa'; l.realtime = true
            l.from.rt = l.from.t! + delay
            l.to.rt = l.to.t! + delay // estimate: same delay at arrival
            l.cancelled = l.cancelled || m.cancelled
            if (m.platform) l.from.track = m.platform
            l.notes.push(...m.remarks)
          } catch {}
        })())
      }
      await Promise.race([Promise.all(jobs), new Promise(r => setTimeout(r, 5000))])
    }
    return { now: Math.round(Date.now() / 1000), rt: rtInfo(), prev: r.previousPageCursor || null, next: r.nextPageCursor || null, itineraries: its }
  } catch (e: any) {
    console.log(`[api] plan: ${e.message}`)
    return reply.code(502).send({ error: 'Verbindungssuche nicht erreichbar' })
  }
})

app.get('/api/v1/trip', async (req, reply) => {
  const q = req.query as Record<string, string>
  if (!q.id) return reply.code(400).send({ error: 'id fehlt' })
  if (typeof q.id !== 'string' || !ID.test(q.id)) return badRequest(reply, 'id')
  try {
    const r = await motis('/api/v6/trip', { tripId: q.id, language: 'de' })
    const leg = r.legs?.[0]
    if (!leg) return reply.code(404).send({ error: 'Fahrt nicht gefunden' })
    const stops = [leg.from, ...(leg.intermediateStops || []), leg.to].map((p: any, i: number, a: any[]) => ({
      name: p.name, station: (stopToStation.get(p.parentId) || stopToStation.get(p.stopId))?.id || null, lat: p.lat, lon: p.lon,
      arr: i > 0 ? { t: sec(p.scheduledArrival), rt: leg.realTime ? sec(p.arrival) : null } : null,
      dep: i < a.length - 1 ? { t: sec(p.scheduledDeparture), rt: leg.realTime ? sec(p.departure) : null } : null,
      track: p.track || null, cancelled: !!p.cancelled,
    }))
    const tline = leg.displayName || leg.routeShortName || ''
    return { line: tline, mode: appMode(leg.mode, tline), color: leg.routeColor ? `#${leg.routeColor}` : null, dir: leg.headsign || '', realtime: !!leg.realTime, cancelled: !!leg.cancelled, rt: rtInfo(), stops }
  } catch (e: any) {
    console.log(`[api] trip: ${e.message}`)
    return reply.code(502).send({ error: 'Fahrt nicht abrufbar' })
  }
})

app.get('/api/v1/geocode', async (req, reply) => {
  const q = req.query as Record<string, string>
  if (typeof q.text !== 'string' || q.text.length < 3) return []
  if (q.text.length > 100) return badRequest(reply, 'text')
  if (q.type !== undefined && !['STOP', 'ADDRESS', 'PLACE'].includes(q.type)) return badRequest(reply, 'type')
  try {
    const b = config.bbox
    const r = await motis('/api/v1/geocode', { text: q.text, language: 'de', type: q.type, place: `${(b.minLat + b.maxLat) / 2},${(b.minLon + b.maxLon) / 2}`, placeBias: 2 }, 5000)
    const seen: Array<{ name: string, lat: number, lon: number }> = []
    const near = (a: any, b: any) => Math.abs(a.lat - b.lat) < 0.003 && Math.abs(a.lon - b.lon) < 0.005
    const uniq = (r || []).filter((m: any) => {
      if (seen.some(x => x.name === m.name && near(x, m))) return false
      seen.push(m); return true
    })
    return uniq.slice(0, 15).map((m: any) => ({
      type: m.type, name: m.name, lat: m.lat, lon: m.lon,
      area: (m.areas || []).filter((a: any) => a.matched || a.default).map((a: any) => a.name).slice(0, 2).join(', '),
      station: m.type === 'STOP' ? stopToStation.get(m.id)?.id || null : null,
    }))
  } catch (e: any) {
    console.log(`[api] geocode: ${e.message}`)
    return reply.code(502).send({ error: 'Adresssuche nicht erreichbar' })
  }
})

app.get('/api/v1/alerts', async (_req, reply) => {
  if (!config.insaEnabled) return { alerts: [] }
  try { return { alerts: await insaAlerts() } } catch (e: any) {
    console.log(`[api] alerts: ${e.message}`)
    return reply.code(502).send({ error: 'Störungsmeldungen nicht abrufbar' })
  }
})

// ---------- start ----------

await initAuth()
onStaticChange(async () => { await loadTripIds(); pollNow(); await loadStations() })
await loadStations()
await loadInsaMap()
await initData()
await startRt()
await app.listen({ host: '0.0.0.0', port: config.port })
console.log(`Takt läuft auf :${config.port}`)
