import csv, sys, datetime, zipfile, io, time
from google.transit import gtfs_realtime_pb2
d=sys.argv[1]; name=sys.argv[2]
stops={s["stop_id"]:s for s in csv.DictReader(open(f"{d}/stops.txt",encoding="utf-8-sig")) if s["stop_id"]==name or s["parent_station"]==name}
print("stop ids:",len(stops))
routes={r["route_id"]:r for r in csv.DictReader(open(f"{d}/routes.txt",encoding="utf-8-sig"))}
trips={t["trip_id"]:t for t in csv.DictReader(open(f"{d}/trips.txt",encoding="utf-8-sig")) if routes[t["route_id"]]["agency_id"] in("84",)}
today=datetime.date.today(); ds=today.strftime("%Y%m%d"); wd=today.strftime("%A").lower(); act=set()
for c in csv.DictReader(open(f"{d}/calendar.txt",encoding="utf-8-sig")):
    if c["start_date"]<=ds<=c["end_date"] and c[wd]=="1": act.add(c["service_id"])
for c in csv.DictReader(open(f"{d}/calendar_dates.txt",encoding="utf-8-sig")):
    if c["date"]==ds: (act.add if c["exception_type"]=="1" else act.discard)(c["service_id"])
now=datetime.datetime.now(); ns=now.hour*3600+now.minute*60
rows=[]
r=csv.reader(io.TextIOWrapper(zipfile.ZipFile(f"{d}/gtfs.zip").open("stop_times.txt"),encoding="utf-8-sig")); h=next(r)
ti,si,di,qi=h.index("trip_id"),h.index("stop_id"),h.index("departure_time"),h.index("stop_sequence")
for row in r:
    if row[si] in stops and row[ti] in trips and trips[row[ti]]["service_id"] in act:
        a,b,c=map(int,row[di].split(":")); x=a*3600+b*60
        if ns-1800<=x<=ns+1200: rows.append((x,row[ti],row[si],int(row[qi])))
feed=gtfs_realtime_pb2.FeedMessage(); feed.ParseFromString(open(f"{d}/rt.pb","rb").read())
tu={e.trip_update.trip.trip_id:e.trip_update for e in feed.entity if e.HasField("trip_update")}
for x,t,s,q in sorted(rows):
    u=tu.get(t); delay="-"; st=""
    if u:
        if u.trip.schedule_relationship==3: st="CANCELED"
        best=None
        for stu in u.stop_time_update:
            if stu.stop_sequence<=q or stu.stop_id==s: best=stu
        if best:
            ev=best.departure if best.HasField("departure") else best.arrival
            delay=ev.delay if ev.HasField("delay") else (ev.time-0 and "t")
            if best.schedule_relationship==1 and best.stop_id==s: st="SKIPPED"
    print(f"{x//3600:02d}:{x%3600//60:02d}",delay,st,routes[trips[t]['route_id']]['route_short_name'])
