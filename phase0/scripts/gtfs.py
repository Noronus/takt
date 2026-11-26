"""Shared helpers: read a gtfs.de feed folder and the realtime snapshot next to it.

A feed folder holds the unpacked text files (routes.txt, trips.txt, stops.txt, calendar*.txt),
the original gtfs.zip (stop_times.txt is read from it, streamed) and rt.pb, a saved GTFS-RT snapshot.
"""

import csv
import datetime as dt
import io
import zipfile
from collections.abc import Iterator
from pathlib import Path
from zoneinfo import ZoneInfo

from google.transit import gtfs_realtime_pb2

TZ = ZoneInfo("Europe/Berlin")
LVB = "84"  # agency_id of Leipziger Verkehrsbetriebe in gtfs.de

# GTFS-RT enum values used here
TRIP_CANCELED = 3
STOP_SKIPPED = 1


def rows(folder: Path, name: str) -> Iterator[dict[str, str]]:
    """Yield the rows of one of the unpacked text files."""
    with (folder / name).open(encoding="utf-8-sig", newline="") as f:
        yield from csv.DictReader(f)


def stop_times(folder: Path, *columns: str) -> Iterator[list[str]]:
    """Yield the given columns of stop_times.txt, streamed from gtfs.zip (it is several GB unpacked)."""
    with zipfile.ZipFile(folder / "gtfs.zip") as z, z.open("stop_times.txt") as raw:
        reader = csv.reader(io.TextIOWrapper(raw, encoding="utf-8-sig", newline=""))
        header = next(reader)
        idx = [header.index(c) for c in columns]
        for row in reader:
            yield [row[i] for i in idx]


def active_services(folder: Path, day: dt.date) -> set[str]:
    """Return the service_ids running on [day]: calendar.txt plus the exceptions in calendar_dates.txt."""
    ds = day.strftime("%Y%m%d")
    weekday = day.strftime("%A").lower()
    active = {
        c["service_id"]
        for c in rows(folder, "calendar.txt")
        if c["start_date"] <= ds <= c["end_date"] and c[weekday] == "1"
    }
    for c in rows(folder, "calendar_dates.txt"):
        if c["date"] == ds:
            if c["exception_type"] == "1":
                active.add(c["service_id"])
            else:
                active.discard(c["service_id"])
    return active


def seconds(hms: str) -> int:
    """Convert a GTFS time ("25:10:00" is allowed) to seconds after midnight."""
    h, m, s = (int(x) for x in hms.split(":"))
    return h * 3600 + m * 60 + s


def now_local() -> dt.datetime:
    """Return the current time in the timetable's time zone."""
    return dt.datetime.now(TZ)


def seconds_of_day(t: dt.datetime) -> int:
    """Return the seconds after local midnight."""
    return t.hour * 3600 + t.minute * 60 + t.second


def realtime(folder: Path) -> gtfs_realtime_pb2.FeedMessage:
    """Read the saved GTFS-RT snapshot rt.pb."""
    feed = gtfs_realtime_pb2.FeedMessage()
    feed.ParseFromString((folder / "rt.pb").read_bytes())
    return feed
