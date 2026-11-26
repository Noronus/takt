"""Departures of one LVB stop in the next minutes with their realtime delay, to compare with another source."""

import argparse
from pathlib import Path

from gtfs import (
    LVB,
    STOP_SKIPPED,
    TRIP_CANCELED,
    active_services,
    now_local,
    realtime,
    rows,
    seconds,
    seconds_of_day,
    stop_times,
)

BEFORE, AFTER = 30 * 60, 20 * 60  # window around now


def main() -> None:
    """Print the departures for the feed folder and stop given on the command line."""
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("folder", type=Path, help="gtfs.de feed folder with gtfs.zip and rt.pb")
    p.add_argument("stop", help="stop_id of the stop or of its parent station")
    args = p.parse_args()
    folder: Path = args.folder

    stops = {s["stop_id"] for s in rows(folder, "stops.txt") if args.stop in (s["stop_id"], s["parent_station"])}
    print("stop ids:", len(stops))
    routes = {r["route_id"]: r for r in rows(folder, "routes.txt")}
    trips = {t["trip_id"]: t for t in rows(folder, "trips.txt") if routes[t["route_id"]]["agency_id"] == LVB}
    now = now_local()
    active = active_services(folder, now.date())
    ns = seconds_of_day(now)

    departures: list[tuple[int, str, str, int]] = []
    for trip, stop, dep, seq in stop_times(folder, "trip_id", "stop_id", "departure_time", "stop_sequence"):
        if stop in stops and trip in trips and trips[trip]["service_id"] in active:
            x = seconds(dep)
            if ns - BEFORE <= x <= ns + AFTER:
                departures.append((x, trip, stop, int(seq)))

    updates = {e.trip_update.trip.trip_id: e.trip_update for e in realtime(folder).entity if e.HasField("trip_update")}
    for x, trip, stop, seq in sorted(departures):
        delay, state = "-", ""
        tu = updates.get(trip)
        if tu is not None:
            if tu.trip.schedule_relationship == TRIP_CANCELED:
                state = "CANCELED"
            # the last update at or before this stop applies (delays carry forward)
            best = None
            for stu in tu.stop_time_update:
                if stu.stop_sequence <= seq or stu.stop_id == stop:
                    best = stu
            if best is not None:
                ev = best.departure if best.HasField("departure") else best.arrival
                delay = str(ev.delay) if ev.HasField("delay") else "time only"
                if best.schedule_relationship == STOP_SKIPPED and best.stop_id == stop:
                    state = "SKIPPED"
        line = routes[trips[trip]["route_id"]]["route_short_name"]
        print(f"{x // 3600:02d}:{x % 3600 // 60:02d}", delay, state, line)


if __name__ == "__main__":
    main()
