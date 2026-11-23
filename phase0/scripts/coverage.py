import csv, sys, time, datetime, collections, zipfile, io
from google.transit import gtfs_realtime_pb2
d=sys.argv[1]
routes={r["route_id"]:r for r in csv.DictReader(open(f"{d}/routes.txt",encoding="utf-8-sig"))}
lvb_routes={k for k,v in routes.items() if v["agency_id"]=="84"}
trips={t["trip_id"]:t for t in csv.DictReader(open(f"{d}/trips.txt",encoding="utf-8-sig")) if t["route_id"] in lvb_routes}
today=datetime.date.today(); ds=today.strftime("%Y%m%d"); wd=today.strftime("%A").lower()
active=set()
for c in csv.DictReader(open(f"{d}/calendar.txt",encoding="utf-8-sig")):
    if c["start_date"]<=ds<=c["end_date"] and c[wd]=="1": active.add(c["service_id"])
for c in csv.DictReader(open(f"{d}/calendar_dates.txt",encoding="utf-8-sig")):
    if c["date"]==ds: (active.add if c["exception_type"]=="1" else active.discard)(c["service_id"])
today_trips={t for t,v in trips.items() if v["service_id"] in active}
print("LVB trips total",len(trips),"today",len(today_trips))
# trip time spans from stop_times (streamed)
span={}
z=zipfile.ZipFile(f"{d}/gtfs.zip"); f=io.TextIOWrapper(z.open("stop_times.txt"),encoding="utf-8-sig")
r=csv.reader(f); h=next(r); ti=h.index("trip_id"); ai=h.index("arrival_time"); di=h.index("departure_time")
def s(x): a,b,c=map(int,x.split(":")); return a*3600+b*60+c
for row in r:
    t=row[ti]
    if t in today_trips:
        x=s(row[di] or row[ai]); lo,hi=span.get(t,(x,x)); span[t]=(min(lo,x),max(hi,x))
now=datetime.datetime.now(); ns=now.hour*3600+now.minute*60+now.second
running={t for t,(lo,hi) in span.items() if lo-1800<=ns<=hi}
feed=gtfs_realtime_pb2.FeedMessage(); feed.ParseFromString(open(f"{d}/rt.pb","rb").read())
tu={e.trip_update.trip.trip_id for e in feed.entity if e.HasField("trip_update")}
print("running now (incl. starting within 30min):",len(running),"with TU:",len(running&tu), f"{100*len(running&tu)/max(1,len(running)):.0f}%")
miss=collections.Counter(routes[trips[t]["route_id"]]["route_short_name"] for t in running-tu)
print("missing by line:",miss.most_common(15))
