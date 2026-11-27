// Private plug-ins: modules in DATA_DIR/private/*.ts are loaded at start. They are not part of this
// repository or the image (sources whose terms allow only private use). A plug-in exports
// `default async function (ctx: PluginCtx)` and can feed fresher realtime into the feed that MOTIS reads.
// Optional exports `label` and `role` name it in the app's list of data sources.
import { readdir } from 'node:fs/promises'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import GtfsRealtimeBindings from 'gtfs-realtime-bindings'
import { config } from './config.ts'
import { paths, onStaticChange } from './data.ts'
import { setOverlay, isActive, pluginInfo, type Entity } from './rt.ts'

export interface PluginCtx {
  config: typeof config
  paths: typeof paths
  TR: typeof GtfsRealtimeBindings.transit_realtime
  /** imports a package of the server (hafas-client, …): plug-ins have no node_modules of their own */
  load: (spec: string) => Promise<any>
  log: (msg: string) => void
  /** true while the app is in use: poll fast then, slowly otherwise */
  isActive: () => boolean
  /** called after new timetables were published (trip ids may change) */
  onStaticChange: (f: () => Promise<void> | void) => void
  /** replaces this plug-in's TripUpdates (per dataset tag). They win over DELFI for the same trip,
   *  except where DELFI has the trip cancelled. sourceTs: unix s of the source data. */
  setOverlay: (byTag: Record<string, Entity[]>, sourceTs: number) => void
  /** shown in the app's settings until the next successful setOverlay */
  setError: (msg: string) => void
}

export const loaded: string[] = []

export async function loadPlugins() {
  const dir = join(config.dataDir, 'private')
  let files: string[] = []
  try { files = (await readdir(dir)).filter(f => /\.(ts|js|mjs)$/.test(f)).sort() } catch { return }
  for (const f of files) {
    const name = f.replace(/\.\w+$/, '')
    const log = (msg: string) => console.log(`[${name}] ${msg}`)
    try {
      const mod = await import(pathToFileURL(join(dir, f)).href)
      if (typeof mod.default !== 'function') { log('kein default export, übersprungen'); continue }
      const ctx: PluginCtx = {
        config, paths, TR: GtfsRealtimeBindings.transit_realtime, log, isActive,
        load: spec => import(spec),
        onStaticChange: fn => onStaticChange(async () => { try { await fn() } catch (e: any) { log(e.message) } }),
        setOverlay: (byTag, ts) => setOverlay(name, byTag, ts),
        setError: msg => { const i = pluginInfo.get(name); if (i) i.error = msg },
      }
      pluginInfo.set(name, { label: String(mod.label || name), role: String(mod.role || ''), fetchedAt: 0, error: '' })
      await mod.default(ctx)
      loaded.push(name)
      log('geladen')
    } catch (e: any) {
      log(`nicht geladen: ${e.message}`)
      const i = pluginInfo.get(name); if (i) i.error = `nicht geladen: ${e.message}`
    }
  }
}
