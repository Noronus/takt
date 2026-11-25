// Runtime configuration, all from environment variables (set via the Unraid template).
const env = process.env

function num(name: string, def: number): number {
  const v = env[name]
  return v && !Number.isNaN(Number(v)) ? Number(v) : def
}

const bbox = (env.BBOX || '11.40,50.95,13.20,51.75').split(',').map(Number)

export const config = {
  port: num('PORT', 8080),
  dataDir: env.DATA_DIR || '/data',
  // minLon, minLat, maxLon, maxLat of the area to keep (default: MDV area around Leipzig/Halle)
  bbox: { minLon: bbox[0], minLat: bbox[1], maxLon: bbox[2], maxLat: bbox[3] },
  // two gtfs.de feeds: local transport (bus/tram) and regional rail (S-Bahn, RE, RB).
  // Trip ids are unique across both and match the realtime feed; route/agency ids are not, so
  // they become separate MOTIS datasets.
  datasets: [
    { tag: 'nv', url: env.GTFS_NV_URL || 'https://download.gtfs.de/germany/nv_free/latest.zip' },
    { tag: 'rv', url: env.GTFS_RV_URL || 'https://download.gtfs.de/germany/rv_free/latest.zip' },
  ],
  rtUrl: env.RT_URL || 'https://realtime.gtfs.de/realtime-free.pb',
  osmUrls: (env.OSM_URLS ||
    'https://download.geofabrik.de/europe/germany/sachsen-latest.osm.pbf,' +
    'https://download.geofabrik.de/europe/germany/sachsen-anhalt-latest.osm.pbf,' +
    'https://download.geofabrik.de/europe/germany/thueringen-latest.osm.pbf').split(',').map(s => s.trim()).filter(Boolean),
  // realtime polling: fast while the app was used recently, slow otherwise (the feed is ~6 MB gzip per fetch)
  rtActiveInterval: num('RT_ACTIVE_INTERVAL', 30),
  rtIdleInterval: num('RT_IDLE_INTERVAL', 300),
  activeWindow: num('ACTIVE_WINDOW', 900),
  gtfsRefreshDays: num('GTFS_REFRESH_DAYS', 7),
  osmRefreshDays: num('OSM_REFRESH_DAYS', 30),
  refreshHour: num('REFRESH_HOUR', 3),
  motisUrl: (env.MOTIS_URL || 'http://motis:8080').replace(/\/$/, ''),
  // URL under which the MOTIS container reaches this server (for the filtered realtime feed)
  selfUrl: (env.SELF_URL || 'http://takt:8080').replace(/\/$/, ''),
  insaEnabled: (env.INSA_ENABLED || 'true') !== 'false',
  userAgent: env.USER_AGENT || 'takt-private-transit-app',
  apiToken: env.API_TOKEN || '',
  // one line per person: "NAME TOKEN" (token >= 32 chars). Edited live, checked every 10 s.
  tokensFile: env.TOKENS_FILE || `${env.DATA_DIR || '/data'}/tokens.txt`,
  // refuse to start without at least one valid token (set this when the API is reachable from the internet)
  requireToken: env.REQUIRE_TOKEN === 'true',
  // requests per person per minute
  rateLimit: num('RATE_LIMIT', 120),
  // parallel journey searches (MOTIS is the expensive part)
  maxPlans: num('MAX_PLANS', 4),
}

export type Config = typeof config
