"""Overview of a realtime snapshot: matched trips, delays, cancellations and alerts for Leipzig."""

import argparse
import collections
import statistics
import time
from dataclasses import dataclass, field
from pathlib import Path

from google.transit import gtfs_realtime_pb2
from gtfs import STOP_SKIPPED, TRIP_CANCELED, realtime, rows

AGENCIES = {"84": "LVB", "46": "Regionalbus Leipzig"}


@dataclass
class Stats:
    """Everything counted while going through the snapshot."""

    counts: collections.Counter[str] = field(default_factory=collections.Counter)
    delays: dict[str, list[int]] = field(default_factory=lambda: collections.defaultdict(list))
    per_agency: collections.Counter[str] = field(default_factory=collections.Counter)
    lines: collections.Counter[tuple[str, str, str]] = field(default_factory=collections.Counter)
    ts_ages: list[float] = field(default_factory=list)
    alerts: list[str] = field(default_factory=list)
    unmatched: int = 0


def count_trip(tu: gtfs_realtime_pb2.TripUpdate, route: dict[str, str], now: float, s: Stats) -> None:
    """Count one trip update of a known route."""
    s.per_agency[route["agency_id"]] += 1
    agency = AGENCIES.get(route["agency_id"])
    if agency is None:
        return
    s.counts[f"{agency} trips"] += 1
    s.lines[agency, route["route_short_name"], route["route_type"]] += 1
    if tu.trip.schedule_relationship == TRIP_CANCELED:
        s.counts[f"{agency} canceled"] += 1
    if tu.timestamp:
        s.ts_ages.append(now - tu.timestamp)
    for stu in tu.stop_time_update:
        if stu.schedule_relationship == STOP_SKIPPED:
            s.counts[f"{agency} skipped stops"] += 1
        ev = stu.departure if stu.HasField("departure") else stu.arrival
        if ev.HasField("delay"):
            s.delays[agency].append(ev.delay)


def count_alert(a: gtfs_realtime_pb2.Alert, routes: dict[str, dict[str, str]], s: Stats) -> None:
    """Count one alert and keep its text if it concerns Leipzig."""
    text = " ".join(t.text for t in [*a.header_text.translation, *a.description_text.translation])
    agencies = {ie.agency_id for ie in a.informed_entity}
    agencies |= {routes.get(ie.route_id, {}).get("agency_id", "") for ie in a.informed_entity}
    if "Leipzig" in text or agencies & AGENCIES.keys():
        s.alerts.append(text[:160])
    s.counts["alerts total"] += 1


def report(s: Stats) -> None:
    """Print the counted numbers."""
    print("unmatched trip_updates", s.unmatched)
    print("top agencies by trip updates:", s.per_agency.most_common(8))
    for k, v in sorted(s.counts.items()):
        print(k, v)
    for agency, values in s.delays.items():
        nonzero = sum(1 for x in values if x)
        print(agency, "delay values", len(values), "nonzero", nonzero, "max", max(values), "min", min(values))
    if s.ts_ages:
        print("trip update age median s", int(statistics.median(s.ts_ages)))
    print("lines:", s.lines.most_common(40))
    print("leipzig alerts:", len(s.alerts))
    for text in s.alerts[:10]:
        print(" -", text)


def main() -> None:
    """Print the overview for the feed folder given on the command line."""
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("folder", type=Path, help="gtfs.de feed folder with rt.pb")
    folder = p.parse_args().folder

    routes = {r["route_id"]: r for r in rows(folder, "routes.txt")}
    trip_route = {t["trip_id"]: t["route_id"] for t in rows(folder, "trips.txt")}
    feed = realtime(folder)
    now = time.time()
    print("feed ts", feed.header.timestamp, "age s", int(now - feed.header.timestamp), "entities", len(feed.entity))

    s = Stats()
    for e in feed.entity:
        if e.HasField("trip_update"):
            route = routes.get(trip_route.get(e.trip_update.trip.trip_id) or e.trip_update.trip.route_id)
            if route is None:
                s.unmatched += 1
            else:
                count_trip(e.trip_update, route, now, s)
        if e.HasField("alert"):
            count_alert(e.alert, routes, s)
    report(s)


if __name__ == "__main__":
    main()
