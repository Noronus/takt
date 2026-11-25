# Third-party notices

Takt's own code is MIT licensed (see `LICENSE`). It bundles or uses:

| Component | Where | License |
|---|---|---|
| Inter font (Rasmus Andersson) | `android/app/src/main/res/font/inter.ttf` | SIL Open Font License 1.1 |
| OpenFreeMap styles "Positron" and "Dark" (based on OpenMapTiles styles) | `android/app/src/main/assets/style-*.json` | BSD 3-Clause (code), CC BY 4.0 (design); map data © OpenStreetMap contributors, ODbL |
| MapLibre Native for Android | app dependency | BSD 2-Clause |
| Jetpack Compose, AndroidX, OkHttp, kotlinx | app dependencies | Apache 2.0 |
| MOTIS | separate container `ghcr.io/motis-project/motis` | MIT |
| Fastify, @fastify/compress | server dependencies | MIT |
| hafas-client | server dependency | ISC |
| gtfs-realtime-bindings | server dependency | Apache 2.0 |

## Data
- Timetable and realtime: DELFI e.V. via gtfs.de, CC BY 4.0 (realtime feed CC BY-SA 4.0). `android/app/src/main/assets/stations.json` is derived from it.
- Map tiles: OpenFreeMap, © OpenStreetMap contributors (ODbL).
- INSA HAFAS (Nahverkehr Sachsen-Anhalt) is an unofficial interface; use it privately and sparingly.

## Aerial images (map, optional)

Loaded at runtime from the open orthophoto services of the states, not bundled:

- Sachsen: © GeoSN, Datenlizenz Deutschland – Namensnennung – Version 2.0 (dl-de/by-2-0)
- Sachsen-Anhalt: © GeoBasis-DE / LVermGeo LSA, dl-de/by-2-0
- Thüringen: © GDI-Th, CC BY 4.0
