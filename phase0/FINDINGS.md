# Phase 0 – data check (Monday 2026-11-23, evening)

Goal: find out whether the open DELFI realtime feed covers Leipzig (LVB) well enough to build on it.

## DELFI GTFS-RT (realtime.gtfs.de/realtime-free.pb) + gtfs.de static
- Feed: ~16.7 MB (6 MB gzip), ~78,000 entities, about one minute old when fetched.
- Trip ids match gtfs.de static data. LVB = agency_id 84, Regionalbus Leipzig = 46.
- **LVB is included**: ~1,070 TripUpdates covering all tram lines (1–16), city buses and rail replacement buses.
- **Coverage**: 316 of 349 LVB trips running at that time had realtime = **91 %**. Missing: single trips of bus lines 61, 62, 84, 91.
- Delays: 5,597 values, 4,185 non-zero, to the second.
- Cancellations: no `CANCELED` trips at that moment; 431 skipped stops.
- ServiceAlerts: 418 in total, none for Leipzig/LVB.
- `nv_free` contains bus/tram only; S-Bahn and regional trains are in `rv_free`. Trip ids are unique across both and match the same realtime feed; route/agency/service ids collide, so they are separate MOTIS datasets.
- **S-Bahn Mitteldeutschland (S1–S11) and DB Regio RE/RB have realtime** in the feed as well.

## Cross-check with INSA HAFAS (hafas-client, profile `insa`)
- Delays at Leipzig Hbf matched DELFI (INSA to the minute, DELFI to the second).
- INSA adds platform numbers for trains and disruption messages (remarks).

## Pitfall
- gtfs.de lists many stations twice under two naming schemes (e.g. `Leipzig, Hauptbahnhof (Tram/Bus)` and `Hauptbahnhof (Tram/Bus), Leipzig`). They are merged by normalized name within 300 m.

## Decision
App + self-hosted backend: the backend filters the Germany-wide realtime feed to the area, MOTIS does departures and routing on the filtered GTFS + realtime, INSA is used sparingly for platforms, gaps and disruption texts.
