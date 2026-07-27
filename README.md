# Freemap Tracker

Android GPS track recorder. Records a high-accuracy location stream from a foreground service and
appends every fix to on-disk SQLite, so recording survives backgrounding and screen lock.

This is an early skeleton: the UI is deliberately just recording state, a start/stop button and a
live point count. The first-run UX, track management and upload come later.

## Requirements

- Android 8.0 (API 26) or newer
- Google Play services (uses `FusedLocationProviderClient`)

## Build

```sh
./gradlew assembleDebug     # APK in app/build/outputs/apk/debug/
./gradlew installDebug      # build and install on the attached device
```

Toolchain: AGP 9.2.1, Gradle 9.4.1, JDK 17+, compileSdk/targetSdk 36, minSdk 26.

## How it works

`TrackingService` is a foreground service with `foregroundServiceType="location"` and a persistent
notification carrying a **Stop** action. It requests fixes at 1 Hz with
`PRIORITY_HIGH_ACCURACY` and holds a partial wake lock for the duration of the recording — without
it, delivery becomes unreliable once the screen goes off.

Fixes are delivered onto a dedicated `HandlerThread`, so SQLite writes never touch the main thread.
`PointStore` appends each one through a single reused compiled statement.

Permissions are requested inline when Start is pressed: fine + coarse location (plus
`POST_NOTIFICATIONS` on Android 13+) in one request, then background location as a **separate**
follow-up — the system silently drops a background-location request that is bundled with the
foreground ones. Denying background location still starts recording, since a foreground service
started from the foreground does not require it.

## Local HTTP API

The app serves a small HTTP API on **`127.0.0.1:8378`**, so a page on `https://freemap.sk` can follow
the recording as it happens. It is bound to loopback only — never `0.0.0.0` — so it is not reachable
from the LAN, and it lives in the app process, which the foreground service keeps alive while
recording. The server comes up with the process rather than with the recording, because `POST /start`
has to be answerable before anything is being recorded.

| endpoint | what it does |
| --- | --- |
| `GET /track?since=N` | every point with `seq > N` as JSON, in `seq` order. Omit `since` for the whole track — this is the cold-open catch-up |
| `GET /stream` | Server-Sent Events tail, one event per fix, `id:` set to the point's `seq` |
| `POST /start` | start recording; returns the status object |
| `POST /stop` | stop recording; returns the status object |
| `GET /status` | recording state, `lastSeq`, point count, permissions, battery-optimisation exemption |

Points are encoded positionally to keep long tracks small:

```json
{"fields":["seq","ts","lat","lon","alt","acc","spd","brg"],
 "points":[[550,1785174195365,48.7062033,21.2367267,279.2,1.9,0.0,null]]}
```

Coordinates are rounded to 7 decimals and metre-scale values to 2 — past that it is float noise, and
over a long track those digits add up. Absent fields are `null`, never `0`.

`/stream` honours `Last-Event-ID` on reconnect: it replays the points after that id, then continues
live. The subscription is registered *before* the replay query runs, so a fix recorded during the
replay is queued rather than lost, and the `seq` filter drops the overlap. A client that stops
reading gets its connection closed once the buffer fills, rather than a silent hole in its tail —
`EventSource` then reconnects and the replay path fills the gap.

CORS is locked to a single origin, the `ALLOWED_ORIGIN` constant in `TrackerApi.kt`. Preflights also
answer `Access-Control-Allow-Private-Network: true`, which older Chrome requires under the Private
Network Access model and newer Chrome ignores under Local Network Access.

```sh
adb forward tcp:8378 tcp:8378          # then reach it from the host
curl 'http://127.0.0.1:8378/track?since=0'
curl -N http://127.0.0.1:8378/stream
curl -X POST http://127.0.0.1:8378/start
```

### Battery-optimisation exemption

`POST /start` while the app is backgrounded needs the app to be exempt from battery optimisation —
without it Android 12+ rejects the foreground-service start with
`ForegroundServiceStartNotAllowedException`, which the endpoint reports as a `409` with the exception
name in `error`. The app asks for the exemption once, after the location permissions; declining
costs only remote start, not recording. `/status` reports the current state as `batteryExempt`.

## Storage

One row per fix in `points`, in the app's private `track.db`:

| column | notes |
| --- | --- |
| `seq` | `INTEGER PRIMARY KEY AUTOINCREMENT` — monotonic, never reused, continues across sessions |
| `ts` | fix time, epoch milliseconds |
| `lat`, `lon` | degrees |
| `altitude` | metres, `NULL` when the fix has none |
| `accuracy` | metres, `NULL` when the fix has none |
| `speed` | m/s, `NULL` when the fix has none |
| `bearing` | degrees, `NULL` when the fix has none |

Optional fields are stored as `NULL` rather than `0` when the platform reports them as absent, so a
stationary fix is not mistaken for one heading due north.

To inspect a recording on a debug build:

```sh
adb shell run-as sk.freemap.tracker cat databases/track.db > track.db
sqlite3 track.db 'SELECT * FROM points ORDER BY seq DESC LIMIT 10;'
```

Note that WAL is enabled, so pull `track.db-wal` alongside it to see the most recent commits.

## Status

Verified on a Xiaomi device running Android 16 (API 36): with the app backgrounded and the screen
locked, points appended continuously for over 9 minutes at 1 Hz — `seq` contiguous, no gap
above 1.2 s. Stopping from the notification action tears down the service, releases the wake lock
and removes the notification, leaving the recorded points intact.

The HTTP API was verified on the same device, with the app backgrounded throughout:

- the listening socket is `127.0.0.1:8378` only — a connection from the device to its own
  non-loopback address is refused, while loopback answers
- `GET /track?since=N` returns the points after `N`; `POST /stop` and `POST /start` toggle recording
- a 6-minute `/stream` tail with the screen asleep delivered 359 events, `seq` 796–1154 contiguous,
  no missing steps and no inter-arrival gap above 2 s
- reconnecting with `Last-Event-ID: 595` replayed 596–610 in a burst and then continued live from
  611, with no gap or duplicate at the handover
- with nothing recording, `: ping` heartbeats arrive every 15 s
