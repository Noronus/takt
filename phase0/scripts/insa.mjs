import {createClient} from 'hafas-client'
import {profile} from 'hafas-client/p/insa/index.js'
const c = createClient(profile, 'leipzig-oepnv-test-private')
const [loc] = await c.locations(process.argv[2] || 'Leipzig Hauptbahnhof', {results:1})
console.log(loc.id, loc.name)
const {departures} = await c.departures(loc.id, {duration: 20, results: 25})
for (const d of departures) console.log(d.plannedWhen?.slice(11,16), (d.delay??'-'), d.cancelled?'CANCELLED':'', d.line?.name, '→', d.direction, d.tripId?.slice(0,30))
