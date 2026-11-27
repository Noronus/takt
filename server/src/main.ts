import Fastify from 'fastify'
import compress from '@fastify/compress'
import { readFile } from 'node:fs/promises'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { config } from './config.ts'
import { initData, onStaticChange, state, busy, paths } from './data.ts'
import { rt, startRt, loadTripIds, touchApp, pollNow, pluginInfo, isActive } from './rt.ts'
import { initAuth, authHook, headersHook, viaProxy, limited } from './security.ts'
import { loadPlugins, loaded as plugins } from './plugins.ts'
import { insaStationId, insaDepartures, insaAlerts, insaAlertsAge, insaHealth, loadInsaMap, normLine, type InsaDep } from './insa.ts'

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
  stopId: string | null   // MOTIS id of the platform it leaves from (matches the platform ids in stations.json)
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
    stopId: p.stopId || null,
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
      track: d.platform, plannedTrack: d.plannedPlatform, stop: st.name, src: d.delay !== null || d.cancelled ? 'insa' : null, notes: d.remarks, info: [], stopId: null,
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

// feed timestamp of the realtime data MOTIS last fetched, per dataset. MOTIS polls on its own
// schedule, so its data can be older than ours; departures and journeys come from MOTIS.
const motisFeed: Record<string, number> = {}
let motisFetchedAt = 0
app.get('/rt/:tag.pb', async (req, reply) => {
  const tag = (req.params as any).tag
  const pb = rt.pb[tag]
  if (!pb) return reply.code(404).send()
  motisFeed[tag] = rt.feedTimestamp
  motisFetchedAt = Date.now()
  return reply.type('application/x-protobuf').send(Buffer.from(pb))
})

const here = dirname(fileURLToPath(import.meta.url))
app.get('/icon.png', async (_req, reply) => reply.type('image/png').send(await readFile(join(here, 'icon.png'))))

const rtInfo = () => {
  // the oldest feed MOTIS works with; until MOTIS has fetched once after a restart, our own feed
  const ts = Object.values(motisFeed).filter(Boolean)
  const feed = ts.length ? Math.min(...ts) : rt.feedTimestamp
  return {
    feedAge: feed ? Math.round(Date.now() / 1000 - feed) : null,
    fetchedAt: rt.fetchedAt ? Math.round(rt.fetchedAt / 1000) : null,
  }
}

// only the age of the realtime data, for screens without live data of their own (cheap: no MOTIS call)
app.get('/api/v1/rt', async () => ({ rt: rtInfo() }))

// what the app's settings list: each source, what it is used for and how fresh it is.
// updated: unix s of the data itself; fetched: unix s of our last successful fetch; null = never
app.get('/api/v1/sources', async () => {
  const s = (ms: number) => ms ? Math.round(ms / 1000) : null
  const list: any[] = [{
    name: 'DELFI Echtzeit (gtfs.de)', role: 'Verspätungen und Ausfälle für Bus, Tram und Bahn',
    active: true, updated: rt.delfiTimestamp || null, fetched: s(rt.fetchedAt),
    info: `${rt.tripUpdates} Fahrten im Gebiet`, error: rt.error || null,
  }]
  for (const [name, p] of pluginInfo) {
    const o = rt.overlay[name]
    list.push({
      name: p.label, role: p.role || 'Aktuellere Verspätungen', active: !!o && o.trips > 0 && !p.error,
      updated: o?.ts || null, fetched: s(p.fetchedAt),
      info: o ? `${o.trips} Fahrten, davon ${o.replaced} statt DELFI` : null, error: p.error || null,
    })
  }
  const mf = Object.values(motisFeed).filter(Boolean)
  list.push({
    name: 'MOTIS', role: 'Abfahrten, Verbindungen und Fahrzeuge auf der Karte (aus den Quellen oben)',
    active: !!motisFetchedAt, updated: mf.length ? Math.min(...mf) : null, fetched: s(motisFetchedAt),
    info: `liest die Echtzeit alle ${Math.max(10, config.motisRtInterval)} s`, error: null,
  })
  if (config.insaEnabled) list.push({
    name: 'INSA Auskunft', role: 'Bahnsteige, Störungsmeldungen, Lücken in der Echtzeit (nur bei Bedarf gefragt)',
    active: !insaHealth.error, updated: null, fetched: s(insaHealth.okAt),
    info: insaHealth.okAt ? 'Störungsmeldungen alle 5 min, solange die App benutzt wird' : 'seit dem Serverstart noch nicht abgefragt', error: insaHealth.error || null,
  })
  // the date of the timetable itself: Last-Modified of the gtfs.de download it was built from
  const built = Date.parse((state.gtfsBuiltFrom || '').split('|').pop() || '')
  list.push({
    name: 'Fahrplan (DELFI über gtfs.de)', role: 'Soll-Fahrplan und Haltestellen, wöchentlich geprüft', active: !!state.motisVersion,
    updated: built ? Math.round(built / 1000) : null,
    fetched: state.gtfsCheckedAt ? Math.round(Date.parse(state.gtfsCheckedAt) / 1000) : null,
    info: state.trips ? `${state.trips} Fahrten, ${state.stations} Haltestellen` : null, error: state.lastError || null,
  })
  return { now: Math.round(Date.now() / 1000), sources: list }
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
  state, busy, stations: stations.size, plugins,
  rt: { ...rt, pb: undefined, pbBytes: Object.fromEntries(Object.entries(rt.pb).map(([k, v]) => [k, v.length])) },
  motis: motisState,
})

app.get('/api/v1/stations', async (req, reply) => {
  if (!stationsBody) return reply.code(503).send({ error: 'Haltestellen werden noch aufbereitet' })
  reply.header('etag', stationsEtag).header('cache-control', 'no-cache')
  if (req.headers['if-none-match'] === stationsEtag) return reply.code(304).send()
  return reply.type('application/json').send(stationsBody)
})

async function stationDeps(st: Station, time: Date, n: number): Promise<Dep[]> {
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
  return deps
}

// next line per platform, for the platform dots on the map: { stationId: { platformStopId: {line, mode, color, ...} } }
const nextCache = new Map<string, { at: number, body: Record<string, any> }>()
app.get('/api/v1/next', async (req, reply) => {
  const q = req.query as Record<string, string>
  const ids = typeof q.stations === 'string' ? q.stations.split(',') : []
  if (!ids.length || ids.length > 40 || !ids.every(x => ID.test(x))) return badRequest(reply, 'stations')
  const now = Math.round(Date.now() / 1000)
  const out: Record<string, Record<string, any>> = {}
  await Promise.all(ids.map(async id => {
    const st = stations.get(id)
    if (!st) return
    const c = nextCache.get(id)
    if (c && Date.now() - c.at < 20_000) { out[id] = c.body; return }
    try {
      const deps = (await stationDeps(st, new Date((now - 60) * 1000), 40))
        .filter(d => !d.cancelled && (d.rt ?? d.t) >= now - 30)
        .sort((a, b) => (a.rt ?? a.t) - (b.rt ?? b.t))
      const body: Record<string, any> = {}
      for (const d of deps) if (d.stopId && !body[d.stopId])
        body[d.stopId] = { line: d.line, mode: d.mode, color: d.color, textColor: d.textColor, t: d.t, rt: d.rt }
      nextCache.set(id, { at: Date.now(), body })
      out[id] = body
    } catch (e: any) {
      console.log(`[api] next ${id}: ${e.message}`)
    }
  }))
  if (nextCache.size > 3000) for (const [k, v] of nextCache) if (Date.now() - v.at > 60_000) nextCache.delete(k)
  return { now, stations: out }
})

app.get('/api/v1/departures', async (req, reply) => {
  const q = req.query as Record<string, string>
  const st = typeof q.station === 'string' ? stations.get(q.station) : undefined
  if (!st) return reply.code(404).send({ error: 'unbekannte Haltestelle' })
  const t = unixTime(q.time)
  if (t === null) return badRequest(reply, 'time')
  const time = t ?? new Date()
  const n = Math.max(1, Math.min(80, Math.floor(Number(q.n)) || 40))
  // optional: only the departures of some platforms of this station (MOTIS ids, comma separated)
  let only: Set<string> | null = null
  if (q.stops !== undefined) {
    const ids = typeof q.stops === 'string' ? q.stops.split(',') : []
    if (!ids.length || ids.length > 20 || !ids.every(x => ID.test(x))) return badRequest(reply, 'stops')
    only = new Set(ids)
  }
  const now = Math.round(Date.now() / 1000)
  try {
    // MOTIS always answers for the whole station, so a platform filter needs more rows to keep n
    let deps = await stationDeps(st, time, only ? 80 : n)
    if (only) deps = deps.filter(d => d.stopId && only.has(d.stopId))
    await enrichRail(st, deps)
    deps.sort((a, b) => (a.rt ?? a.t) - (b.rt ?? b.t))
    return { station: { id: st.id, name: st.name }, now, source: 'motis', rt: rtInfo(), departures: deps.slice(0, n) }
  } catch (e: any) {
    console.log(`[api] departures: ${e.message}`)
    if (!config.insaEnabled) return reply.code(502).send({ error: 'MOTIS nicht erreichbar' })
    try {
      // INSA does not know the platforms: with a platform filter it cannot answer
      const deps = only ? [] : await insaFallback(st, time, 90)
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
    // the stops in between (for the map: small dots along the line)
    via: transit ? (l.intermediateStops || []).map((p: any) => ({
      name: p.name, station: (stopToStation.get(p.parentId) || stopToStation.get(p.stopId))?.id || null, lat: p.lat, lon: p.lon,
    })) : [],
    // encoded polyline (Google format) of the leg: real paths for walking, OSM-matched shapes for transit
    geometry: l.legGeometry?.points ? { points: l.legGeometry.points, precision: l.legGeometry.precision ?? 6 } : null,
    notes: alertTexts(l.alerts),
    info: infoTexts(l.alerts),
  }
}

type Itin = { id: string, transfers: number, duration: number, legs: ReturnType<typeof mapLeg>[], via?: string }

function toItinerary(it: any): Itin {
  const legs = it.legs.map(mapLeg)
  let fixed = false
  for (const l of legs) {
    // the feed sometimes has a large delay at one stop and a small one at the next, which makes
    // a 10 min ride take 0 min; keep the departure delay for the whole leg instead
    const plannedRide = (l.to.t ?? 0) - (l.from.t ?? 0)
    if (l.mode !== 'walk' && l.from.rt != null && l.to.rt != null && plannedRide > 0 && l.to.rt - l.from.rt < plannedRide / 2) {
      l.to.rt = l.from.rt + plannedRide
      fixed = true
    }
  }
  const first = legs[0], last = legs[legs.length - 1]
  const duration = fixed && first && last ? (last.to.rt ?? last.to.t)! - (first.from.rt ?? first.from.t)! : it.duration
  return { id: it.id, transfers: it.transfers, duration, legs }
}

const itStart = (it: Itin) => { const l = it.legs[0]; return (l?.from.rt ?? l?.from.t) ?? 0 }
const itEnd = (it: Itin) => { const l = it.legs[it.legs.length - 1]; return (l?.to.rt ?? l?.to.t) ?? 0 }
const itSig = (it: Itin) => it.legs.map(l => `${l.mode}:${l.trip}:${l.from.t}`).join('|')
// stations where this journey changes vehicles
function transferStations(it: Itin): Set<string> {
  const t = it.legs.filter(l => l.mode !== 'walk')
  const s = new Set<string>()
  t.forEach((l, i) => { if (i > 0 && l.from.station) s.add(l.from.station); if (i < t.length - 1 && l.to.station) s.add(l.to.station) })
  return s
}

// "Bevorzugte Umstiege": a journey that has to change is replaced by one changing at a preferred stop,
// if that has no more changes and costs at most `slack` minutes (later arrival + earlier departure).
async function preferTransfers(its: Itin[], q: Record<string, string>, fromPlace: string, toPlace: string, time: Date | undefined): Promise<Itin[]> {
  const prefer = (typeof q.prefer === 'string' ? q.prefer.split(',') : []).map(id => stations.get(id)).filter((s): s is Station => !!s).slice(0, 3)
  if (!prefer.length || !its.some(it => it.transfers > 0)) return its
  const slack = Math.min(30, Math.max(0, Number(q.slack) || 5)) * 60
  const preferIds = new Set(prefer.map(s => s.id))
  const arriveBy = q.arriveBy === '1' || q.arriveBy === 'true'
  // the window of this page: from its first departure (or up to its last arrival)
  const t0 = arriveBy ? Math.max(...its.map(itEnd)) : Math.min(...its.map(itStart))
  const candidates: Itin[] = []
  for (const st of prefer) {
    if ([fromPlace, toPlace].includes(st.stopIds[0])) continue
    const r = await limited(config.maxPlans, () => motis('/api/v6/plan', {
      fromPlace, toPlace, via: st.stopIds[0], time: new Date((t0 || (time ? time.getTime() / 1000 : Date.now() / 1000)) * 1000).toISOString(),
      arriveBy, detailedTransfers: true, joinInterlinedLegs: true, maxMatchingDistance: 250, language: 'de', numItineraries: 6,
    }, 10000)).catch(() => null)
    if (!r || r === 'busy') continue
    for (const v of (r.itineraries || []).map(toItinerary) as Itin[]) {
      if (v.legs.some(l => l.cancelled) || ![...transferStations(v)].some(id => preferIds.has(id))) continue
      candidates.push({ ...v, via: st.name })
    }
  }
  const out: Itin[] = []
  const seen = new Set<string>()
  for (const n of its) {
    const already = [...transferStations(n)].find(id => preferIds.has(id))
    let pick: Itin = already ? { ...n, via: stations.get(already)?.name } : n
    if (!already && n.transfers > 0) {
      let best: Itin | undefined, bestCost = Infinity
      for (const v of candidates) {
        if (v.transfers > n.transfers) continue
        const cost = Math.max(0, itEnd(v) - itEnd(n)) + Math.max(0, itStart(n) - itStart(v))
        if (cost <= slack && cost < bestCost) { best = v; bestCost = cost }
      }
      if (best) pick = best
    }
    const k = itSig(pick)
    if (!seen.has(k)) { seen.add(k); out.push(pick) }
  }
  return out
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
    let its = (r.itineraries || []).map(toItinerary)
    its = await preferTransfers(its, q, fromPlace, toPlace, time)
    // short distances: also offer walking the whole way (MOTIS sends it apart from the transit results);
    // first page only, earlier/later pages are about departures
    if (!q.cursor) {
      const walk = (r.direct || []).find((d: any) => d.legs?.length && d.legs.every((l: any) => l.mode === 'WALK') && d.duration <= 40 * 60)
      if (walk) its = [toItinerary(walk), ...its]
    }
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

// ---------- live vehicles ----------
// MOTIS "railviz": trip segments (stop to stop, with realtime times and the path) in a box and a time
// window. The app moves each vehicle along its segment by time, like geOps; there are no GPS positions.
const vehCache = new Map<string, { at: number, body: any }>()
app.get('/api/v1/vehicles', async (req, reply) => {
  const q = req.query as Record<string, string>
  const a = COORD.exec(q.min ?? ''), b = COORD.exec(q.max ?? '')
  const zoom = Number(q.zoom)
  if (!a || !b) return badRequest(reply, 'min/max')
  if (!(zoom >= 11 && zoom <= 22)) return badRequest(reply, 'zoom')
  let [lat1, lon1, lat2, lon2] = [Number(a[1]), Number(a[2]), Number(b[1]), Number(b[2])]
  if (lat1 > lat2) [lat1, lat2] = [lat2, lat1]
  if (lon1 > lon2) [lon1, lon2] = [lon2, lon1]
  // a city at most: keeps answers small and MOTIS fast
  if (lat2 - lat1 > 0.35 || lon2 - lon1 > 0.55) return reply.code(400).send({ error: 'Ausschnitt zu groß, näher heranzoomen' })
  const r3 = (x: number) => Math.round(x * 500) / 500   // ~200 m grid: nearby requests share the cache
  const key = [r3(lat1), r3(lon1), r3(lat2), r3(lon2), Math.round(zoom)].join(',')
  const now = Math.round(Date.now() / 1000)
  const c = vehCache.get(key)
  if (c && Date.now() - c.at < 5000) return c.body
  try {
    const segs = await motis('/api/v1/map/trips', {
      min: `${r3(lat1)},${r3(lon1)}`, max: `${r3(lat2)},${r3(lon2)}`, zoom: Math.round(zoom),
      startTime: new Date((now - 60) * 1000).toISOString(), endTime: new Date((now + 150) * 1000).toISOString(),
    }, 6000)
    const vehicles = (Array.isArray(segs) ? segs : []).flatMap((sg: any) => (sg.trips || []).slice(0, 1).map((t: any) => {
      const line = t.displayName || t.routeShortName || ''
      return {
        trip: t.tripId, line, mode: appMode(sg.mode, line),
        color: sg.routeColor ? `#${sg.routeColor}` : null,
        dep: sec(sg.departure), arr: sec(sg.arrival), rt: !!sg.realTime,
        to: (typeof sg.to === 'object' ? sg.to?.name : null) || null,
        poly: typeof sg.polyline === 'string' ? sg.polyline : sg.polyline?.points ?? null,
        precision: typeof sg.polyline === 'object' ? sg.polyline?.precision ?? 5 : 5,
      }
    })).filter((v: any) => v.trip && v.poly && v.dep != null && v.arr != null)
    const body = { now, rt: rtInfo(), vehicles }
    vehCache.set(key, { at: Date.now(), body })
    if (vehCache.size > 500) for (const [k, v] of vehCache) if (Date.now() - v.at > 30_000) vehCache.delete(k)
    return body
  } catch (e: any) {
    console.log(`[api] vehicles: ${e.message}`)
    return reply.code(502).send({ error: 'Fahrzeugpositionen nicht abrufbar' })
  }
})

app.get('/api/v1/alerts', async (_req, reply) => {
  if (!config.insaEnabled) return { alerts: [] }
  // age: how old the cached INSA messages are, so the app shows their real age and not the time since its request
  try { const alerts = await insaAlerts(); return { alerts, age: insaAlertsAge() } } catch (e: any) {
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
await loadPlugins()
// while the app is in use, keep the disruption messages warm (INSA, cached 5 min): the alerts tab opens
// at once and the data sources list shows whether INSA answers
if (config.insaEnabled) setInterval(() => { if (isActive()) insaAlerts().catch(() => {}) }, 60_000)
await app.listen({ host: '0.0.0.0', port: config.port })
console.log(`Takt läuft auf :${config.port}`)
