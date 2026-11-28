// Category (city bus, regional bus, long-distance bus, …) per trip, for the map's live vehicles: MOTIS's map
// trips carry no operator, so the category comes from the GTFS files MOTIS reads.
import { join } from 'node:path'
import { config } from './config.ts'
import { paths } from './data.ts'
import { rows, catOf, catName, type CatName } from './gtfs.ts'

let tripCat = new Map<string, number>()   // "<tag>_<GTFS trip id>" -> CAT bit

export async function loadTripCats() {
  const next = new Map<string, number>()
  try {
    for (const d of config.datasets) {
      const zip = join(paths.motisInput, `${d.tag}.gtfs.zip`)
      const agency = new Map<string, string>()
      for await (const { h, r } of rows(zip, 'agency.txt')) if (r.length) agency.set(r[h.agency_id] || '', r[h.agency_name] || '')
      const route = new Map<string, number>()
      for await (const { h, r } of rows(zip, 'routes.txt')) {
        if (r.length) route.set(r[h.route_id], catOf(Number(r[h.route_type]), r[h.route_short_name] || '', agency.get(r[h.agency_id] || '') || ''))
      }
      for await (const { h, r } of rows(zip, 'trips.txt')) {
        const c = r.length ? route.get(r[h.route_id]) : undefined
        if (c) next.set(`${d.tag}_${r[h.trip_id]}`, c)
      }
    }
    tripCat = next
    console.log(`[api] Kategorien für ${next.size} Fahrten geladen`)
  } catch (e: any) { console.log(`[api] Kategorien nicht geladen: ${e.message}`) }
}

/** MOTIS trip ids look like "20261128_01:45_nv_oimo" (date, start time, dataset trip id). */
export function tripCatOf(motisTripId: string): CatName | null {
  const m = /^\d{8}_\d{1,2}:\d{2}_(.+)$/.exec(motisTripId || '')
  const c = m ? tripCat.get(m[1]) : undefined
  return c ? catName(c) : null
}

/** From a MOTIS stop time or leg: routeType and agency are given there. */
export function catOfMotis(x: any, line: string): CatName {
  if (typeof x.routeType === 'number') return catName(catOf(x.routeType, line, x.agencyName || ''))
  return tripCatOf(x.tripId) || 'other'
}
