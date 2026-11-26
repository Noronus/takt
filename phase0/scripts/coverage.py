"""Share of LVB trips running right now that have realtime in the snapshot, and which lines lack it."""

import argparse
import collections
from pathlib import Path

from gtfs import LVB, active_services, now_local, realtime, rows, seconds, seconds_of_day, stop_times

LEAD = 30 * 60  # trips starting within the next 30 min count as running


def main() -> None:
    """Print the coverage for the feed folder given on the command line."""
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("folder", type=Path, help="gtfs.de feed folder with gtfs.zip and rt.pb")
    folder = p.parse_args().folder

    routes = {r["route_id"]: r for r in rows(folder, "routes.txt")}
    trips = {t["trip_id"]: t for t in rows(folder, "trips.txt") if routes[t["route_id"]]["agency_id"] == LVB}
    now = now_local()
    active = active_services(folder, now.date())
    today = {t for t, v in trips.items() if v["service_id"] in active}
    print("LVB trips total", len(trips), "today", len(today))

    # first and last time of each of today's trips
    span: dict[str, tuple[int, int]] = {}
    for trip, dep, arr in stop_times(folder, "trip_id", "departure_time", "arrival_time"):
        if trip in today:
            x = seconds(dep or arr)
            lo, hi = span.get(trip, (x, x))
            span[trip] = (min(lo, x), max(hi, x))

    ns = seconds_of_day(now)
    running = {t for t, (lo, hi) in span.items() if lo - LEAD <= ns <= hi}
    with_rt = {e.trip_update.trip.trip_id for e in realtime(folder).entity if e.HasField("trip_update")}
    covered = running & with_rt
    share = 100 * len(covered) / max(1, len(running))
    print(f"running now (incl. starting within 30 min): {len(running)}, with realtime: {len(covered)} ({share:.0f} %)")
    missing = collections.Counter(routes[trips[t]["route_id"]]["route_short_name"] for t in running - with_rt)
    print("missing by line:", missing.most_common(15))


if __name__ == "__main__":
    main()
