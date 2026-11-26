// Static data preparation: cuts the Germany-wide GTFS down to the configured area and
// builds the compact station list that the app keeps offline.
import { spawn } from 'node:child_process'
import { createInterface } from 'node:readline'
import { createWriteStream, type WriteStream } from 'node:fs'
import { mkdir, rm, writeFile, rename } from 'node:fs/promises'
import { join } from 'node:path'
import { createHash } from 'node:crypto'
import { config } from './config.ts'

// ---------- CSV helpers ----------

export function parseCsvLine(line: string): string[] {
  if (line.indexOf('"') === -1) return line.split(',')
  const out: string[] = []
  let cur = ''
  let q = false
  for (let i = 0; i < line.length; i++) {
    const c = line[i]
    if (q) {
      if (c === '"') {
        if (line[i + 1] === '"') { cur += '"'; i++ } else q = false
      } else cur += c
    } else if (c === '"') q = true
    else if (c === ',') { out.push(cur); cur = '' }
    else cur += c
  }
  out.push(cur)
  return out
}

function lines(zip: string, member: string): AsyncIterable<string> {
  const p = spawn('unzip', ['-p', zip, member], { stdio: ['ignore', 'pipe', 'inherit'] })
  return createInterface({ input: p.stdout, crlfDelay: Infinity })
}

async function* rows(zip: string, member: string): AsyncGenerator<{ h: Record<string, number>, r: string[], raw: string }> {
  let h: Record<string, number> | null = null
  for await (let line of lines(zip, member)) {
    if (!line) continue
    if (!h) {
      line = line.replace(/^﻿/, '')
      h = {}
      parseCsvLine(line).forEach((k, i) => { h![k.trim()] = i })
      yield { h, r: [], raw: line } // header row: r is empty
      continue
    }
    yield { h, r: parseCsvLine(line), raw: line }
  }
}

async function listMembers(zip: string): Promise<string[]> {
  const out: string[] = []
  const p = spawn('unzip', ['-Z1', zip], { stdio: ['ignore', 'pipe', 'inherit'] })
  for await (const l of createInterface({ input: p.stdout })) if (l.trim()) out.push(l.trim())
  return out
}

class Out {
  private s: WriteStream
  private buf: string[] = []
  private size = 0
  constructor(path: string) { this.s = createWriteStream(path) }
  async line(l: string) {
    this.buf.push(l)
    this.size += l.length + 1
    if (this.size > 1 << 20) await this.flush()
  }
  async flush() {
    if (!this.buf.length) return
    const ok = this.s.write(this.buf.join('\n') + '\n')
    this.buf = []; this.size = 0
    if (!ok) await new Promise<void>(r => this.s.once('drain', () => r()))
  }
  async close() { await this.flush(); await new Promise<void>(r => this.s.end(() => r())) }
}

// ---------- modes ----------

export const MODE = { tram: 1, subway: 2, rail: 4, bus: 8, ferry: 16, other: 32, suburban: 64 }

export function modeOf(routeType: number, shortName: string): number {
  const t = routeType
  if (t === 0 || (t >= 900 && t < 1000)) return MODE.tram
  if (t === 1 || (t >= 400 && t < 500)) return MODE.subway
  if (t === 2 || (t >= 100 && t < 200)) return /^S\s?\d/.test(shortName) || t === 109 ? MODE.suburban : MODE.rail
  if (t === 3 || (t >= 700 && t < 800) || (t >= 200 && t < 300)) return MODE.bus
  if (t === 4 || (t >= 1000 && t < 1300)) return MODE.ferry
  return MODE.other
}

// ---------- filter ----------

export interface Station {
  id: string            // MOTIS stop id of the main station (dataset prefix + GTFS id)
  name: string
  lat: number
  lon: number
  modes: number
  stopIds: string[]     // all MOTIS stop ids (merged duplicates, both datasets); children are resolved by MOTIS
  deps: number
  plats: Platform[]     // where the vehicles actually stop; the map shows them one by one when zoomed in
}

// one stopping point of a station: [lat, lon, platform code, modes, MOTIS stop ids]
export type Platform = [number, number, string, number, string[]]

export interface FilterResult { trips: number, stops: number, stations: Station[], tripIds: Set<string> }

const inBox = (lat: number, lon: number) => {
  const b = config.bbox
  return lat >= b.minLat && lat <= b.maxLat && lon >= b.minLon && lon <= b.maxLon
}

export async function filterGtfs(srcZip: string, outZip: string, tag: string, log: (s: string) => void): Promise<FilterResult> {
  const work = outZip + '.work'
  await rm(work, { recursive: true, force: true })
  await mkdir(work, { recursive: true })
  const members = new Set(await listMembers(srcZip))

  // stops: which are in the area
  log('stops.txt lesen')
  type S = { name: string, lat: number, lon: number, parent: string, code: string, raw: string }
  const stops = new Map<string, S>()
  const boxStops = new Set<string>()
  let stopsHeader = ''
  for await (const { h, r, raw } of rows(srcZip, 'stops.txt')) {
    if (!r.length) { stopsHeader = raw; continue }
    const id = r[h.stop_id], lat = Number(r[h.stop_lat]), lon = Number(r[h.stop_lon])
    stops.set(id, { name: r[h.stop_name], lat, lon, parent: r[h.parent_station] || '', code: r[h.platform_code] || '', raw })
    if (inBox(lat, lon)) boxStops.add(id)
  }

  log('routes.txt + trips.txt lesen')
  const routeMode = new Map<string, number>()
  const routeRaw = new Map<string, string>()
  const routeAgency = new Map<string, string>()
  let routesHeader = ''
  for await (const { h, r, raw } of rows(srcZip, 'routes.txt')) {
    if (!r.length) { routesHeader = raw; continue }
    routeMode.set(r[h.route_id], modeOf(Number(r[h.route_type]), r[h.route_short_name] || ''))
    routeRaw.set(r[h.route_id], raw)
    routeAgency.set(r[h.route_id], r[h.agency_id] || '')
  }
  const tripRoute = new Map<string, string>()
  for await (const { h, r } of rows(srcZip, 'trips.txt')) {
    if (r.length) tripRoute.set(r[h.trip_id], r[h.route_id])
  }

  log('stop_times.txt filtern (dauert ein paar Minuten)')
  const st = new Out(join(work, 'stop_times.txt'))
  const keptTrips = new Set<string>()
  const usedStops = new Set<string>()
  const stopDeps = new Map<string, number>()
  const stopModes = new Map<string, number>()
  let cur = ''
  let buf: string[] = []
  let bufStops: string[] = []
  let hit = false
  let ti = 0, si = 3
  const flushTrip = async () => {
    if (hit && buf.length) {
      keptTrips.add(cur)
      const m = routeMode.get(tripRoute.get(cur) || '') || MODE.other
      for (const l of buf) await st.line(l)
      for (const s of bufStops) {
        usedStops.add(s)
        stopDeps.set(s, (stopDeps.get(s) || 0) + 1)
        stopModes.set(s, (stopModes.get(s) || 0) | m)
      }
    }
    buf = []; bufStops = []; hit = false
  }
  let n = 0
  for await (const { h, r, raw } of rows(srcZip, 'stop_times.txt')) {
    if (!r.length) { await st.line(raw); ti = h.trip_id; si = h.stop_id; continue }
    const t = r[ti]
    if (t !== cur) { await flushTrip(); cur = t }
    const s = r[si]
    buf.push(raw); bufStops.push(s)
    if (!hit && boxStops.has(s)) hit = true
    if (++n % 10_000_000 === 0) log(`  ${n / 1e6} Mio. Zeilen`)
  }
  await flushTrip()
  await st.close()

  log(`${keptTrips.size} Fahrten im Gebiet, Rest der Dateien schreiben`)
  const usedRoutes = new Set<string>(), usedServices = new Set<string>(), usedAgencies = new Set<string>()
  {
    const o = new Out(join(work, 'trips.txt'))
    for await (const { h, r, raw } of rows(srcZip, 'trips.txt')) {
      if (!r.length) { await o.line(raw); continue }
      if (!keptTrips.has(r[h.trip_id])) continue
      usedRoutes.add(r[h.route_id]); usedServices.add(r[h.service_id])
      await o.line(raw)
    }
    await o.close()
  }
  {
    const o = new Out(join(work, 'routes.txt'))
    await o.line(routesHeader)
    for (const id of usedRoutes) { await o.line(routeRaw.get(id)!); usedAgencies.add(routeAgency.get(id)!) }
    await o.close()
  }
  // stops: used ones plus their parents
  for (const s of [...usedStops]) { const p = stops.get(s)?.parent; if (p) usedStops.add(p) }
  {
    const o = new Out(join(work, 'stops.txt'))
    await o.line(stopsHeader)
    for (const id of usedStops) { const s = stops.get(id); if (s) await o.line(s.raw) }
    await o.close()
  }
  const filterBy = async (member: string, col: string, keep: Set<string>) => {
    if (!members.has(member)) return
    const o = new Out(join(work, member))
    for await (const { h, r, raw } of rows(srcZip, member)) {
      if (!r.length) { await o.line(raw); continue }
      if (keep.has(r[h[col]])) await o.line(raw)
    }
    await o.close()
  }
  await filterBy('calendar.txt', 'service_id', usedServices)
  await filterBy('calendar_dates.txt', 'service_id', usedServices)
  await filterBy('agency.txt', 'agency_id', usedAgencies)
  for (const m of ['feed_info.txt', 'attributions.txt']) {
    if (!members.has(m)) continue
    const o = new Out(join(work, m))
    for await (const l of lines(srcZip, m)) if (l) await o.line(l)
    await o.close()
  }

  log('ZIP für MOTIS packen')
  await rm(outZip + '.tmp', { force: true })
  await run('sh', ['-c', `cd "${work}" && zip -q -1 "${outZip}.tmp" *.txt`])
  await rename(outZip + '.tmp', outZip)
  await rm(work, { recursive: true, force: true })

  // ---------- stations ----------
  log('Haltestellenliste bauen')
  const st2 = new Map<string, Station>()
  const plats = new Map<string, Map<string, Platform>>()   // station key -> stop id -> platform
  for (const id of usedStops) {
    const s = stops.get(id)
    if (!s) continue
    const key = s.parent && stops.has(s.parent) ? s.parent : id
    const base = stops.get(key)!
    let x = st2.get(key)
    if (!x) {
      x = { id: `${tag}_${key}`, name: base.name, lat: base.lat, lon: base.lon, modes: 0, stopIds: [`${tag}_${key}`], deps: 0, plats: [] }
      st2.set(key, x)
      plats.set(key, new Map())
    }
    x.modes |= stopModes.get(id) || 0
    x.deps += stopDeps.get(id) || 0
    // only stops with departures are platforms (the parent itself has none when it has children)
    if (stopDeps.get(id)) plats.get(key)!.set(id, [s.lat, s.lon, s.code, stopModes.get(id) || 0, [`${tag}_${id}`]])
  }
  for (const [key, x] of st2) x.plats = [...plats.get(key)!.values()]
  const stations = [...st2.values()].filter(s => s.deps > 0 && inBox(s.lat, s.lon))
  log(`${stations.length} Haltestellen in ${tag}`)
  return { trips: keptTrips.size, stops: usedStops.size, stations, tripIds: keptTrips }
}

// gtfs.de contains many stations twice ("Leipzig, Hauptbahnhof" / "Hauptbahnhof, Leipzig"),
// from different source systems. Merge those that have the same name tokens and are close.
function normName(n: string): string {
  return n.toLowerCase()
    .replace(/str\./g, 'straße').replace(/strasse/g, 'straße')
    .replace(/[(),./-]/g, ' ')
    .split(/\s+/).filter(Boolean).sort().join(' ')
}

function dist(a: { lat: number, lon: number }, b: { lat: number, lon: number }) {
  const dLat = (a.lat - b.lat) * 111_320
  const dLon = (a.lon - b.lon) * 111_320 * Math.cos(a.lat * Math.PI / 180)
  return Math.hypot(dLat, dLon)
}

export function mergeDuplicates(list: Station[]): Station[] {
  const groups = new Map<string, Station[]>()
  for (const s of list) {
    const k = normName(s.name)
    const g = groups.get(k)
    if (g) g.push(s); else groups.set(k, [s])
  }
  const out: Station[] = []
  for (const g of groups.values()) {
    g.sort((a, b) => b.deps - a.deps)
    const done: Station[] = []
    for (const s of g) {
      const m = done.find(d => dist(d, s) < 300)
      if (m) {
        m.stopIds.push(...s.stopIds)
        m.modes |= s.modes
        m.deps += s.deps
        m.plats.push(...s.plats)
      } else done.push({ ...s, stopIds: [...s.stopIds], plats: [...s.plats] })
    }
    out.push(...done)
  }
  return out
}

// platforms closer than this are drawn as one point (e.g. a tram and a bus stop on the same island)
const PLATFORM_MERGE_M = 8

function platforms(list: Platform[]): Platform[] {
  const out: Platform[] = []
  for (const [lat, lon, code, modes, ids] of list) {
    const near = out.find(o => dist({ lat: o[0], lon: o[1] }, { lat, lon }) < PLATFORM_MERGE_M)
    if (near) {
      near[3] |= modes
      near[4].push(...ids)
      if (code && !near[2].split('/').includes(code)) near[2] = near[2] ? `${near[2]}/${code}` : code
    } else out.push([+lat.toFixed(5), +lon.toFixed(5), code, modes, [...ids]])
  }
  return out
}

export function stationsJson(stations: Station[]) {
  const list = stations
    .sort((a, b) => b.deps - a.deps)
    .map(s => {
      const row: unknown[] = [s.id, s.name, +s.lat.toFixed(5), +s.lon.toFixed(5), s.modes, s.stopIds.length > 1 ? s.stopIds : null, s.deps]
      // platforms only where there is more than one place to stand (old apps ignore this field)
      const p = platforms(s.plats)
      if (p.length > 1) row.push(p)
      return row
    })
  const body = JSON.stringify(list)
  const version = createHash('sha1').update(body).digest('hex').slice(0, 12)
  return { version, json: `{"version":"${version}","generated":"${new Date().toISOString()}","stations":${body}}` }
}

export function run(cmd: string, args: string[]): Promise<void> {
  return new Promise((res, rej) => {
    const p = spawn(cmd, args, { stdio: 'inherit' })
    p.on('error', rej)
    p.on('exit', c => c === 0 ? res() : rej(new Error(`${cmd} exit ${c}`)))
  })
}

export async function writeJson(path: string, data: unknown) {
  await writeFile(path + '.tmp', JSON.stringify(data))
  await rename(path + '.tmp', path)
}
