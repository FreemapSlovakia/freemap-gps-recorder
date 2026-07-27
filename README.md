# Freemap Tracker

Android GPS track recorder. Records a high-accuracy location stream from a foreground service and
appends every fix to on-disk SQLite, so recording survives backgrounding and screen lock.

This is an early skeleton: the UI is recording state, a start/stop button, a live point count and a
setup checklist. Track management and upload come later.

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

## Setup

Recording in the background is not one permission but five separate concessions, three of which
Android will not even prompt for. The Activity therefore shows a checklist of them, each with its
live state and a button that opens whatever screen can resolve it. It is re-read on every resume,
because most of them are granted in Settings — outside the app, with nothing to call back.

| item | how it is resolved | blocking? |
| --- | --- | --- |
| Location access | prominent-disclosure screen, then the system prompt for fine + coarse | **yes** |
| Background location | Android 11+ shows no prompt at all, so it is app settings → Permissions → Location → *Allow all the time* | no |
| Notifications | system prompt on Android 13+; implicit before that | **yes** |
| Unrestricted battery use | plain-language explanation, then `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | no |
| Vendor app restrictions | vendor-specific guidance, plus a shortcut to that vendor's own screen | no |

Start is blocked until location and notifications are granted — without the first there is nothing
to record, and without the second a recording would run with no visible sign of it, which is the one
thing a foreground service exists to prevent. `POST /start` refuses the same two with `403` and
`"error":"setup incomplete"`.

`TrackingService` checks the same gate in `onStartCommand` and stops instead of starting. That is not
a formality: revoking a permission kills the app process, `START_STICKY` has the system restart the
service, and Play services does not fail the location subscription synchronously — so without the
check the service comes back and sits in the notification claiming to record while appending nothing.

The rest only make a recording *survive*, so they never block anything; while any of them is
outstanding a banner says as much and the checklist stays on screen. Once everything is resolved
both disappear and the screen is just state, count and button again.

The order in the table is the order the items can be asked in: Android silently drops a
background-location request that is bundled with, or precedes, the foreground one, so the background
row stays disabled until location is granted.

### Vendor app restrictions

Xiaomi, Huawei, Samsung, Oppo, Vivo and OnePlus kill background apps on terms of their own, well
beyond anything the platform does, and no API reports whether they have been told not to. The
checklist detects the manufacturer, shows what to change, and offers a shortcut to the screen that
changes it. Vendor components are undocumented and move between ROM versions, so each is tried in
turn and every launch is wrapped; anything that misses falls back to the app-details page, which is
where the per-app switches live on most of these ROMs anyway. The row is the user's word that it is
done, kept in `SharedPreferences`, and it is only shown at all on those manufacturers.

Note what this item is *not*: autostart. Autostart governs whether a vendor ROM may launch an app
that is not running — on boot, from a broadcast, from another app. A recording the user started is
already running, so autostart is not what keeps it alive; the per-app battery mode is. It matters
here only in one place, and second-hand: `TrackingService` is `START_STICKY`, so a process kill has
the system try to start the service again, and *that* is a background start a vendor can refuse.
The guidance says so rather than repeating the folklore.

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
| `GET /status` | recording state, `lastSeq`, point count, `port`/`portEcho`, and the whole [setup](#setup) state |

Points are encoded positionally to keep long tracks small:

```json
{"fields":["seq","ts","lat","lon","alt","acc","spd","brg"],
 "points":[[550,1785174195365,48.7062033,21.2367267,279.2,1.9,0.0,null]]}
```

Coordinates are rounded to 7 decimals and metre-scale values to 2 — past that it is float noise, and
over a long track those digits add up. Absent fields are `null`, never `0`.

`/status` reports every checklist item, so a page can say "the recorder needs setup" instead of
watching `POST /start` fail for reasons it cannot name:

```json
{"recording":false,"lastSeq":24,"count":24,"port":8378,"portEcho":null,
 "permissions":{"fine":true,"background":true,"notifications":false},
 "batteryExempt":true,
 "oem":{"vendor":"xiaomi","needed":true,"acknowledged":false},
 "canRecord":false,"setupComplete":false}
```

`canRecord` is the hard gate — the same one the Start button uses. `setupComplete` additionally
covers the items that only make a long recording survive. `oem.needed` is false on manufacturers
that leave background apps alone, and `oem.vendor` is then `null`.

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

## Launching from the web

A page can link straight into the recorder with the `freemap-recorder://` scheme:

| link | what happens |
| --- | --- |
| `freemap-recorder://start` | starts recording and immediately hands focus back to the browser |
| anything else, e.g. `freemap-recorder://open` | just opens the app |

`start` is the useful one: the Activity fires up the foreground service and then gets out of the
way — it finishes when the link created the task, and moves the task to the back when the app was
already open, so the screen the user left behind survives. Either way focus returns to the browser
instead of leaving them looking at the native UI. If the app is not able to record yet it stays up
with the [checklist](#setup) instead, opens the first thing standing in the way, and hands back the
moment recording actually starts — which may be several prompts later.

This is also the way to start recording that always works. `POST /start` needs the
battery-optimisation exemption below, because it is a background start; a link makes the app
foreground, so the foreground-service start is permitted with no exemption and no extra dialog —
the exemption prompt is deliberately skipped on this path so nothing interrupts the hand-back.

An optional `?port=` is echoed back as `portEcho` in `GET /status`, alongside the authoritative
`port`. Send the port you intend to talk to, then read `/status` on it: getting your own value back
confirms that the app answering there is the one the link just launched, and not something else
bound to that port. A mismatch with `port` means the page and the app disagree — trust `port`.

```js
location.href = 'freemap-recorder://start?port=8378'
// then, on return to the page:
const s = await (await fetch('http://127.0.0.1:8378/status')).json()
// s.recording === true, s.portEcho === 8378
```

Nothing tells the page whether the app is installed — the link silently does nothing if it is not,
so treat a `/status` that never answers as "not installed".

### Battery-optimisation exemption

`POST /start` while the app is backgrounded needs the app to be exempt from battery optimisation —
without it Android 12+ rejects the foreground-service start with
`ForegroundServiceStartNotAllowedException`, which the endpoint reports as a `409` with the exception
name in `error`. Declining the exemption costs only remote start, not recording, which is why it is
a non-blocking [checklist](#setup) item; `/status` reports it as `batteryExempt`.

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

`freemap-recorder://start` was verified on the same device with Firefox as the default browser, on a
cold process and with the app already open, and with the battery-optimisation exemption *removed*:
recording started in both cases and the browser was the resumed activity again within seconds, with
no dialog in between. When the app was already open its task survived the hand-back; when the link
had created the task, no activity was left behind. `?port=` came back as `portEcho`, and a
mismatched port was logged rather than acted on.

The setup checklist was walked end to end on an API 36 emulator, since MIUI refuses `INJECT_EVENTS`
over adb and the phone cannot be driven by script:

- from a state with every permission revoked: Start disabled, no banner, location and notifications
  marked missing and blocking, background disabled behind *Grant location access first*
- the disclosure screen precedes the system location prompt, and granting it enables the background
  row on return
- background location opens App info, where *Allow all the time* lives; the battery item explains
  itself and then raises `RequestIgnoreBatteryOptimizations`, and the row flips to ✓ on resume
- granting the last item from outside the app, while it sat in the background, collapsed both the
  checklist and the banner on resume — nothing else prompted it
- `POST /start` with notifications revoked answered `403` with `"canRecord":false` and
  `"error":"setup incomplete"`, then `200` once granted
- `freemap-recorder://start` still hands focus straight back when setup is complete; with location
  revoked it keeps the app up and opens the first blocking item instead

Revoking location mid-recording is what turned up the zombie-service case above: the service came
back with `recording:true` and `lastSeq` frozen. With the gate in `onStartCommand` the restart logs
`cannot record: location or notification permission missing, stopping` and leaves no service record,
and no `ForegroundServiceDidNotStartInTimeException` for stopping before going foreground.

On the phone, vendor detection reports `"oem":{"vendor":"xiaomi","needed":true}`, and setting the
acknowledgement flips `acknowledged` and `setupComplete` together. The vendor dialog's own buttons
are the one thing not exercised by script, for the `INJECT_EVENTS` reason above.
