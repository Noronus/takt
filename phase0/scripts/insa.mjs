// Departures of a station from INSA HAFAS (unofficial), to compare with the DELFI realtime feed.
// Usage: node insa.mjs ["Station name"]   (needs hafas-client, e.g. from server/node_modules)
import { createClient } from 'hafas-client'
import { profile } from 'hafas-client/p/insa/index.js'

const client = createClient(profile, 'takt-phase0-check')
const [station] = await client.locations(process.argv[2] || 'Leipzig Hauptbahnhof', { results: 1 })
console.log(station.id, station.name)

const { departures } = await client.departures(station.id, { duration: 20, results: 25 })
for (const d of departures) {
  const time = d.plannedWhen?.slice(11, 16)
  console.log(time, d.delay ?? '-', d.cancelled ? 'CANCELLED' : '', d.line?.name, '→', d.direction, d.tripId?.slice(0, 30))
}
