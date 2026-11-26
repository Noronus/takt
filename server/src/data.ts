// Keeps static inputs fresh: GTFS (weekly), OSM (monthly), writes the MOTIS input folder.
import { createWriteStream } from 'node:fs'
import { mkdir, readFile, writeFile, rename, stat, rm, copyFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createHash } from 'node:crypto'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { config } from './config.ts'
import { filterGtfs, stationsJson, mergeDuplicates, run, writeJson, type Station } from './gtfs.ts'
import { motisConfigYaml, motisRunScript } from './motis-files.ts'

const D = config.dataDir
export const paths = {
  cache: join(D, 'cache'),
  gtfsRaw: (tag: string) => join(D, 'cache', `gtfs-${tag}.zip`),
  osmDir: join(D, 'cache', 'osm'),
  static: join(D, 'static'),
  stations: join(D, 'static', 'stations.json'),
  tripIds: (tag: string) => join(D, 'static', `trip-ids-${tag}.txt`),
  state: join(D, 'state.json'),
  motis: join(D, 'motis'),
  motisInput: join(D, 'motis', 'input'),
}

export interface State {
  gtfsCheckedAt?: string
  gtfsBuiltFrom?: string   // etag/last-modified of the raw GTFS files the current build was made from
  osmBuiltAt?: string
  motisVersion?: string
  motisConfigHash?: string
  lastError?: string
  trips?: number
  stations?: number
  stationsVersion?: string
  stationsFormat?: number
}

// Version of the stations.json layout. Raise it when the server writes something new into it (2: platforms with
// their stop ids); a list in an older layout is then built again at the next start, without new downloads.
const STATIONS_FORMAT = 2

export let state: State = {}
export let busy = ''
export let ready = false
const listeners: Array<() => void> = []
export const onStaticChange = (f: () => void) => listeners.push(f)

const log = (s: string) => console.log(`[daten] ${s}`)

async function loadState() {
  try { state = JSON.parse(await readFile(paths.state, 'utf8')) } catch { state = {} }
}
async function saveState() { await writeJson(paths.state, state) }

async function exists(p: string) { try { await stat(p); return true } catch { return false } }

// Download with conditional request. Returns true if the file changed.
async function download(url: string, dest: string): Promise<boolean> {
  const meta = dest + '.meta'
  let prev: { etag?: string, lm?: string } = {}
  try { prev = JSON.parse(await readFile(meta, 'utf8')) } catch {}
  const headers: Record<string, string> = { 'user-agent': config.userAgent }
  if (await exists(dest)) {
    if (prev.etag) headers['if-none-match'] = prev.etag
    if (prev.lm) headers['if-modified-since'] = prev.lm
  }
  const res = await fetch(url, { headers, signal: AbortSignal.timeout(30 * 60_000) })
  if (res.status === 304) { log(`unverändert: ${url}`); return false }
  if (!res.ok || !res.body) throw new Error(`${url}: HTTP ${res.status}`)
  log(`lade ${url}`)
  await pipeline(Readable.fromWeb(res.body as any), createWriteStream(dest + '.part'))
  await rename(dest + '.part', dest)
  await writeFile(meta, JSON.stringify({ etag: res.headers.get('etag') || undefined, lm: res.headers.get('last-modified') || undefined }))
  return true
}

async function rawId(dest: string) {
  try { const m = JSON.parse(await readFile(dest + '.meta', 'utf8')); return m.etag || m.lm || 'x' } catch { return 'x' }
}

async function buildOsm() {
  await mkdir(paths.osmDir, { recursive: true })
  const files: string[] = []
  for (const [i, url] of config.osmUrls.entries()) {
    const f = join(paths.osmDir, `part${i}.osm.pbf`)
    await download(url, f)
    files.push(f)
  }
  const b = config.bbox, pad = 0.15
  const box = [b.minLon - pad, b.minLat - pad, b.maxLon + pad, b.maxLat + pad].join(',')
  const merged = join(paths.osmDir, 'merged.osm.pbf')
  log('OSM zusammenführen und zuschneiden')
  if (files.length > 1) await run('osmium', ['merge', ...files, '-o', merged, '--overwrite', '--no-progress'])
  else await copyFile(files[0], merged)
  await run('osmium', ['extract', '-b', box, merged, '-o', join(paths.motisInput, 'mdv.osm.pbf.tmp'), '-f', 'pbf', '--overwrite', '--no-progress'])
  await rename(join(paths.motisInput, 'mdv.osm.pbf.tmp'), join(paths.motisInput, 'mdv.osm.pbf'))
  await rm(merged, { force: true })
  state.osmBuiltAt = new Date().toISOString()
}

async function rawIds() {
  return (await Promise.all(config.datasets.map(d => rawId(paths.gtfsRaw(d.tag))))).join('|')
}

async function buildGtfs() {
  const all: Station[] = []
  let trips = 0
  for (const d of config.datasets) {
    const res = await filterGtfs(paths.gtfsRaw(d.tag), join(paths.motisInput, `${d.tag}.gtfs.zip`), d.tag, log)
    all.push(...res.stations)
    trips += res.trips
    await writeFile(paths.tripIds(d.tag) + '.tmp', [...res.tripIds].join('\n'))
    await rename(paths.tripIds(d.tag) + '.tmp', paths.tripIds(d.tag))
  }
  const stations = mergeDuplicates(all)
  const sj = stationsJson(stations)
  await writeFile(paths.stations + '.tmp', sj.json)
  await rename(paths.stations + '.tmp', paths.stations)
  state.trips = trips
  state.stations = stations.length
  state.stationsVersion = sj.version
  state.stationsFormat = STATIONS_FORMAT
  state.gtfsBuiltFrom = await rawIds()
  log(`${stations.length} Haltestellen gesamt`)
}

async function writeMotisInput(version: string) {
  await writeFile(join(paths.motisInput, 'config.yml'), motisConfigYaml())
  await writeFile(join(paths.motis, 'run.sh'), motisRunScript(), { mode: 0o755 })
  // written last: the MOTIS container starts an import when this changes
  await writeFile(join(paths.motisInput, 'version.tmp'), version)
  await rename(join(paths.motisInput, 'version.tmp'), join(paths.motisInput, 'version'))
  state.motisVersion = version
}

const daysSince = (iso?: string) => iso ? (Date.now() - Date.parse(iso)) / 86_400_000 : Infinity

export async function refresh(force = false) {
  if (busy) return
  busy = 'prüfe'
  try {
    for (const d of [paths.cache, paths.static, paths.motisInput]) await mkdir(d, { recursive: true })
    let changed = false

    const osmMissing = !(await exists(join(paths.motisInput, 'mdv.osm.pbf')))
    if (force || osmMissing || daysSince(state.osmBuiltAt) >= config.osmRefreshDays - 0.5) {
      busy = 'OSM'
      await buildOsm(); changed = true; await saveState()
    }

    let gtfsMissing = !(await exists(paths.stations))
    if (!gtfsMissing && state.stationsFormat !== STATIONS_FORMAT) {
      log(`Haltestellenliste im alten Format (${state.stationsFormat ?? 1}), wird neu gebaut`)
      gtfsMissing = true
    }
    for (const d of config.datasets) if (!(await exists(join(paths.motisInput, `${d.tag}.gtfs.zip`)))) gtfsMissing = true
    if (force || gtfsMissing || daysSince(state.gtfsCheckedAt) >= config.gtfsRefreshDays - 0.5) {
      busy = 'GTFS'
      let dl = false
      for (const d of config.datasets) if (await download(d.url, paths.gtfsRaw(d.tag))) dl = true
      state.gtfsCheckedAt = new Date().toISOString()
      if (dl || gtfsMissing || force || state.gtfsBuiltFrom !== await rawIds()) {
        await buildGtfs(); changed = true
      }
      await saveState()
    }

    // always keep run.sh/config current (they ship with this image)
    const cfgHash = createHash('sha1').update(motisConfigYaml()).digest('hex').slice(0, 10)
    if (state.motisConfigHash !== cfgHash) { changed = true; state.motisConfigHash = cfgHash }
    const version = changed || !state.motisVersion
      ? new Date().toISOString().replace(/[-:T]/g, '').slice(0, 12)
      : state.motisVersion
    await writeMotisInput(version)
    state.lastError = undefined
    await saveState()
    if (changed) listeners.forEach(f => f())
    ready = true
  } catch (e: any) {
    state.lastError = `${new Date().toISOString()} ${e.message}`
    log(`Fehler: ${e.message}`)
    await saveState().catch(() => {})
  } finally {
    busy = ''
  }
}

export async function initData() {
  await loadState()
  refresh().catch(() => {})
  // check once per hour; real work only happens at REFRESH_HOUR when something is due
  setInterval(() => {
    const h = new Date().getHours()
    const due = daysSince(state.gtfsCheckedAt) >= config.gtfsRefreshDays - 0.5 || daysSince(state.osmBuiltAt) >= config.osmRefreshDays - 0.5
    if (h === config.refreshHour && due) refresh().catch(() => {})
  }, 3600_000)
}
