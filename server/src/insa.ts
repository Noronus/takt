// INSA HAFAS (unofficial): realtime for S-Bahn/regional trains (missing in DELFI),
// disruption messages, and a fallback for departures when MOTIS is down.
// Used sparingly: every result is cached.
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createClient } from 'hafas-client'
import { profile } from 'hafas-client/p/insa/index.js'
import { config } from './config.ts'
import { writeJson } from './gtfs.ts'

const client = createClient(profile, config.userAgent)
const mapFile = join(config.dataDir, 'static', 'insa-map.json')
let stationMap: Record<string, string | null> = {}
const cache = new Map<string, { at: number, p: Promise<any> }>()

async function cached<T>(key: string, ttlMs: number, f: () => Promise<T>): Promise<T> {
  const c = cache.get(key)
  if (c && Date.now() - c.at < ttlMs) return c.p
  const p = f()
  cache.set(key, { at: Date.now(), p })
  p.catch(() => cache.delete(key))
  if (cache.size > 2000) for (const [k, v] of cache) if (Date.now() - v.at > 600_000) cache.delete(k)
  return p
}

const withTimeout = <T>(p: Promise<T>, ms: number) =>
  Promise.race([p, new Promise<never>((_, rej) => setTimeout(() => rej(new Error('INSA timeout')), ms))])

export async function loadInsaMap() {
  try { stationMap = JSON.parse(await readFile(mapFile, 'utf8')) } catch { stationMap = {} }
}

// Finds the INSA stop for one of our stations (by position). Result is stored permanently.
export async function insaStationId(st: { id: string, name: string, lat: number, lon: number }, rail: boolean): Promise<string | null> {
  const key = `${st.id}:${rail ? 'r' : 'a'}`
  if (key in stationMap) return stationMap[key]
  const res: any[] = await withTimeout(client.nearby({ type: 'location', latitude: st.lat, longitude: st.lon }, { distance: 500, results: 8, poi: false, stops: true }), 6000)
  const cand = res.filter(s => s.type === 'stop' || s.type === 'station')
    .filter(s => !rail || s.products?.suburban || s.products?.regional || s.products?.national || s.products?.nationalExpress)
  const best = cand.sort((a, b) => (a.distance ?? 1e9) - (b.distance ?? 1e9))[0]
  stationMap[key] = best ? best.id : null
  writeJson(mapFile, stationMap).catch(() => {})
  return stationMap[key]
}

export interface InsaDep {
  line: string; direction: string; planned: number; when: number | null; delay: number | null
  cancelled: boolean; platform: string | null; plannedPlatform: string | null; product: string; tripId: string
  remarks: string[]
}

export const normLine = (s: string) => (s || '').replace(/\s+/g, '').replace(/^(STR|BUS)/i, '').toUpperCase()

export async function insaDepartures(insaId: string, from: Date, minutes: number, railOnly = false): Promise<InsaDep[]> {
  const slot = Math.floor(from.getTime() / 60_000)
  return cached(`dep:${insaId}:${slot}:${minutes}:${railOnly}`, 45_000, async () => {
    const products = railOnly ? { tram: false, bus: false, tourismTrain: false } : undefined
    const r: any = await withTimeout(client.departures(insaId, { when: from, duration: minutes, results: 150, remarks: true, products }), 7000)
    const list: any[] = r.departures || r
    return list.map(d => ({
      line: d.line?.name || '',
      direction: d.direction || '',
      planned: Date.parse(d.plannedWhen) / 1000,
      when: d.when ? Date.parse(d.when) / 1000 : null,
      delay: typeof d.delay === 'number' ? d.delay : null,
      cancelled: !!d.cancelled,
      // INSA sometimes reports section-joined platforms like "23231"; fall back to the planned one then
      platform: (d.platform && /^\d{4,}$/.test(d.platform) ? d.plannedPlatform : d.platform) || null,
      plannedPlatform: d.plannedPlatform || null,
      product: d.line?.product || '',
      tripId: d.tripId || '',
      remarks: (d.remarks || []).filter((x: any) => x.type === 'warning').map((x: any) => x.summary || x.text).filter(Boolean),
    }))
  })
}

export interface Alert {
  id: string; title: string; text: string; company: string; lines: string[]
  validFrom: string | null; validUntil: string | null; products: string[]; stops: string[]
}

const inBox = (lat: number, lon: number) => {
  const b = config.bbox
  return lat >= b.minLat && lat <= b.maxLat && lon >= b.minLon && lon <= b.maxLon
}

/** Seconds since the alerts in the cache were fetched from INSA (0 if there are none yet). */
export function insaAlertsAge(): number {
  const c = cache.get('alerts')
  return c ? Math.max(0, Math.round((Date.now() - c.at) / 1000)) : 0
}

export async function insaAlerts(): Promise<Alert[]> {
  return cached('alerts', 5 * 60_000, async () => {
    const r: any = await withTimeout(client.remarks({ results: 500 }), 15_000)
    const all: any[] = r.remarks || r
    const out: Alert[] = []
    for (const x of all) {
      const locs = [...(x.edges || []), ...(x.events || [])].flatMap((e: any) => [e.fromLocation, e.toLocation]).filter(Boolean)
      const coords = [...(x.edges || []).map((e: any) => e.icoCrd && { latitude: e.icoCrd.y / 1e6, longitude: e.icoCrd.x / 1e6 }), ...locs.map((l: any) => l.location)].filter(Boolean)
      const here = coords.some((c: any) => inBox(c.latitude, c.longitude))
      if (!here && !/leipzig|LVB/i.test(`${x.company} ${x.summary}`)) continue
      out.push({
        id: x.id,
        title: x.summary || '',
        text: (x.text || '').trim(),
        company: x.company || '',
        lines: [...new Set<string>((x.affectedLines || []).map((l: any) => l.name).filter(Boolean))],
        validFrom: x.validFrom || null,
        validUntil: x.validUntil || null,
        products: Object.entries(x.products || {}).filter(([, v]) => v).map(([k]) => k),
        stops: [...new Set<string>(locs.map((l: any) => l.name).filter(Boolean))].slice(0, 12),
      })
    }
    const now = Date.now()
    return out.filter(a => !a.validUntil || Date.parse(a.validUntil) > now)
      .sort((a, b) => Date.parse(b.validFrom || '0') - Date.parse(a.validFrom || '0'))
  })
}
