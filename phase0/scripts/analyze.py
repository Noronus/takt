import csv, sys, time, collections
from google.transit import gtfs_realtime_pb2
d = sys.argv[1]
AG = {"84":"LVB","46":"Regionalbus Leipzig"}
routes = {}
for r in csv.DictReader(open(f"{d}/routes.txt", encoding="utf-8-sig")):
    routes[r["route_id"]] = r
trip_route = {}
for t in csv.DictReader(open(f"{d}/trips.txt", encoding="utf-8-sig")):
    trip_route[t["trip_id"]] = t["route_id"]
feed = gtfs_realtime_pb2.FeedMessage(); feed.ParseFromString(open(f"{d}/rt.pb","rb").read())
now = time.time()
print("feed ts", feed.header.timestamp, "age s", int(now-feed.header.timestamp), "entities", len(feed.entity))
stats = collections.Counter(); delays = collections.defaultdict(list); unmatched=0; tsages=[]
agency_tu = collections.Counter(); lines=collections.Counter(); alerts_lpz=[]
for e in feed.entity:
    if e.HasField("trip_update"):
        tu = e.trip_update; tid = tu.trip.trip_id
        rid = trip_route.get(tid) or tu.trip.route_id
        r = routes.get(rid)
        if not r: unmatched+=1; continue
        agency_tu[r["agency_id"]]+=1
        if r["agency_id"] not in AG: continue
        a=AG[r["agency_id"]]; stats[(a,"trips")]+=1
        lines[(a,r["route_short_name"],r["route_type"])]+=1
        if tu.trip.schedule_relationship==3: stats[(a,"CANCELED")]+=1
        if tu.timestamp: tsages.append(now-tu.timestamp)
        for stu in tu.stop_time_update:
            if stu.schedule_relationship==1: stats[(a,"skipped_stops")]+=1
            ev = stu.departure if stu.HasField("departure") else stu.arrival
            if ev.HasField("delay"): delays[a].append(ev.delay)
    if e.HasField("alert"):
        txt=" ".join(t.text for t in e.alert.header_text.translation)+" "+" ".join(t.text for t in e.alert.description_text.translation)
        ags={ie.agency_id for ie in e.alert.informed_entity}
        rs={routes.get(ie.route_id,{}).get("agency_id") for ie in e.alert.informed_entity}
        if "Leipzig" in txt or ags&set(AG) or rs&set(AG): alerts_lpz.append(txt[:160])
        stats["alerts_total"]+=1
print("unmatched trip_updates", unmatched)
print("top agencies by TU:", agency_tu.most_common(8))
for k,v in sorted(stats.items(), key=str): print(k,v)
for a,l in delays.items():
    nz=[x for x in l if x!=0]; print(a,"delay values",len(l),"nonzero",len(nz),"max min",max(l) if l else None,min(l) if l else None)
if tsages: tsages.sort(); print("TU ts age median s", int(tsages[len(tsages)//2]))
print("lines:", sorted(lines.items(), key=lambda x:-x[1])[:40])
print("leipzig alerts:", len(alerts_lpz)); [print(" -",x) for x in alerts_lpz[:10]]
