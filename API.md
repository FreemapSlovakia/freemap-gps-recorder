# Freemap GPS Recorder HTTP API

The recorder serves this API from inside the app process on **`http://127.0.0.1:8378`**, so a page
open in a browser on the same phone can follow a recording as it happens, and start or stop one.

It is bound to loopback only — never `0.0.0.0` — so it is not reachable from the LAN, from another
device, or from anywhere but this phone. The server comes up with the app process rather than with
the recording, because `POST /start` has to be answerable before anything is being recorded, and the
foreground service keeps that process alive with the screen off for as long as a recording runs.

> This file is the source of truth for the API. `./gradlew checkApiDocs` compares it against
> `RecorderApi.kt` and fails on any disagreement; `releaseApk` and `check` both depend on it, so a
> release cannot be built with this file out of date. See [Keeping this in sync](#keeping-this-in-sync).

## Endpoints

| | |
| --- | --- |
| [`GET /status`](#get-status) | recorder state, version, and the whole setup checklist |
| [`GET /track`](#get-track) | the recorded points, whole or from a given `seq` |
| [`DELETE /track`](#delete-track) | throw the recorded track away |
| [`GET /stream`](#get-stream) | Server-Sent Events tail, one event per fix |
| [`POST /start`](#post-start) | start recording, optionally on terms of your own |
| [`POST /stop`](#post-stop) | stop recording |

Every response is `application/json` except `/stream`, which is `text/event-stream`.

### GET /status

Takes no parameters. Always answers `200` with the status object:

```json
{"recording":false,"lastSeq":1919,"count":1919,"generation":0,
 "fields":["seq","ts","lat","lon","alt","acc","spd","brg","altMsl","altAcc","spdAcc","brgAcc","sat","src","seg"],
 "version":{"code":8,"name":"0.8"},
 "port":8378,"portEcho":null,
 "permissions":{"fine":true,"background":true,"notifications":true},
 "batteryExempt":true,
 "oem":{"vendor":"xiaomi","needed":true,"acknowledged":false},
 "canRecord":true,"setupComplete":false,
 "config":{"intervalMs":1000,"minDistanceM":0.0,"maxAccuracyM":null,"priority":"high"}}
```

| field | |
| --- | --- |
| `recording` | whether a recording is in progress right now |
| `lastSeq` | highest `seq` currently stored, or `0` when the track is empty. Poll `/track?since=` with this |
| `count` | how many points are stored |
| `generation` | how many times the track has been cleared — see [`DELETE /track`](#delete-track) |
| `fields` | the point column names, in order — the same list [`GET /track`](#get-track) returns |
| `version.code` | `versionCode` of the installed recorder. Compare against what your page needs |
| `version.name` | human-readable version, e.g. `"0.8"` |
| `port` | the port this recorder is listening on, always `8378` |
| `portEcho` | the `port` from the last `freemap-gps-recorder://` link, or `null` — see [Launching from the web](README.md#launching-from-the-web) |
| `permissions.fine` | `ACCESS_FINE_LOCATION` granted |
| `permissions.background` | `ACCESS_BACKGROUND_LOCATION` granted |
| `permissions.notifications` | `POST_NOTIFICATIONS` granted |
| `batteryExempt` | app is exempt from battery optimisation. Required to record — see `canRecord` |
| `oem.vendor` | `"xiaomi"`, `"huawei"`, `"samsung"`, `"oppo"`, `"vivo"`, `"oneplus"`, or `null` |
| `oem.needed` | this manufacturer needs a per-app battery setting changed by hand |
| `oem.acknowledged` | the user has said they did it — nothing on the platform can verify this |
| `canRecord` | the hard gate: `permissions.fine && permissions.notifications && batteryExempt`. `POST /start` fails without it |
| `setupComplete` | everything above resolved, including the items that only make a *long* recording survive |
| `config.intervalMs` | desired interval between fixes |
| `config.minDistanceM` | minimum displacement before a fix is recorded |
| `config.maxAccuracyM` | fixes worse than this are dropped; `null` means everything is kept |
| `config.priority` | `"high"`, `"balanced"` or `"low"` |
| `error` | present only on a failed `/start` or `DELETE /track` — see those endpoints |

`canRecord` is what to check before offering a start button, and it is a promise rather than a guess:
the battery exemption is one of its terms precisely because a start from a page is a *background*
foreground-service start, which Android 12+ refuses without it. So `canRecord` being true means
[`POST /start`](#post-start) will work from a page that is not in front, and not merely that the
permissions are in place.

`setupComplete` being false is not an error: it covers `permissions.background` and the OEM item,
which only make a *long* recording survive. Recording works without them; it is just liable to be
killed after a while.

`fields` is here for the client that attaches to [`GET /stream`](#get-stream) without reading a
`/track` page first — a reconnecting `EventSource`, or one whose cursor is already current. The stream
sends bare rows, so such a client would otherwise fall back on a hardcoded column list. Appending to
the list can never break it, since the names it knows stay a prefix of the real one; naming them here
means it need not guess at all. It costs no extra request, because a client reads `/status` anyway.

`config` is the **effective** recording config — what the running recording actually uses, or what the
next one will use, after clamping to what the platform allows. Send values with
[`POST /start`](#post-start) and read them back here; comparing the two is how you find out that a
request was clamped. The presence of `config` at all is the feature detection: a recorder that returns
none ignored the body you sent it, and there is no separate capability flag or version gate to check.

The same status object is the body of every `/start`, `/stop` and `DELETE /track`
response, so one request tells you both what happened and where things now stand. It is also what the
`status` event on [`GET /stream`](#get-stream) carries, which is how a connected client keeps up without
polling this endpoint at all.

### GET /track

The whole track, or the part of it a client has not seen.

| parameter | |
| --- | --- |
| `since` | return only points with `seq` **greater than** this. Optional; omit or `0` for the whole track |

```json
{"fields":["seq","ts","lat","lon","alt","acc","spd","brg","altMsl","altAcc","spdAcc","brgAcc","sat","src","seg"],
 "points":[[550,1785174195365,48.7062033,21.2367267,279.2,1.9,0.0,null,237.1,2.4,0.3,null,9,"fused",3]]}
```

Points are encoded positionally, in the order named by `fields`, to keep a long track small. Read
the order from `fields` rather than hard-coding it. The list is append-only, so a column added later
costs a reader nothing and one you do not know about can simply be ignored.

| field | |
| --- | --- |
| `seq` | id of the point. Strictly increasing, never reused, gapless within one generation |
| `ts` | UTC milliseconds since the epoch, from the location fix itself and not from the clock on receipt |
| `lat`, `lon` | WGS84 degrees, rounded to 7 decimals (~1 cm) |
| `alt` | metres above the WGS84 **ellipsoid**, or `null` |
| `acc` | horizontal accuracy in metres, 68% confidence, or `null` |
| `spd` | ground speed in m/s, or `null` |
| `brg` | direction of travel in degrees clockwise from true north, or `null` |
| `altMsl` | metres above **mean sea level** — what GPX `<ele>` wants. `null` below Android 14, and until a GNSS fix has been seen |
| `altAcc` | vertical accuracy of `alt` in metres, 68% confidence, or `null` |
| `spdAcc` | accuracy of `spd` in m/s, or `null` |
| `brgAcc` | accuracy of `brg` in degrees, or `null` |
| `sat` | satellites used in the fix, for GPX `<sat>`, or `null` — see below |
| `src` | the platform provider that produced the fix: `"gps"`, `"fused"`, `"network"`, or `null` |
| `seg` | segment ordinal. A point whose `seg` differs from its predecessor's begins a new segment |

Every numeric field after `lon` is rounded to 2 decimals. A field the platform reported as absent is
`null` and never `0` — an absent speed and a genuine standstill are different facts.

**`alt` and `altMsl` are both here on purpose.** `alt` is the raw ellipsoidal figure the platform
hands over, which is the one that round-trips losslessly; `altMsl` is the one a consumer usually
wants, since GPX `<ele>` is metres above mean sea level and the geoid separation is around +40 m over
Slovakia. Only Android 14 and newer knows the separation at all, so on older devices `altMsl` is
`null` and falling back to `alt` is all there is.

Even on Android 14+ it does not arrive with the fix. The fixes come from Play services'
`FusedLocationProviderClient`, and the `Location` it hands over reports no MSL altitude — verified on
an API 36 emulator, where the platform's own providers carry the figure and the fused client's copy
does not. So the recorder reads the **separation** off a raw GNSS fix and subtracts it from this fix's
own `alt`. That is why `altMsl` is `null` for the first few points of a recording, until a GNSS fix has
been along, and why it stays `null` for a whole recording at `priority: "low"` that never turns the
receiver on. Reading the separation rather than copying the MSL altitude is deliberate: the altitude
belongs to the fix that carried it, and would be wrong here by the difference between the two fixes'
altitudes, while the separation is a property of the ground and changes by metres over kilometres.

**`sat` is best-effort.** A fused fix carries nothing about satellites, so the count is read from the
GNSS receiver running alongside it and matched up by time. It is `null` when the receiver has not
reported recently enough to speak for the fix in hand — which is the honest answer for a fix that came
from the network, or one taken while the receiver was duty-cycled off between widely spaced fixes.

**`seg` marks the breaks**, so a client drawing the points does not join them with a straight line
across a lunch break or a drive home with the recorder off. It increments on every start, and —
because a `START_STICKY` restart after a process kill is a real break too — it survives the process. It is an ordinal rather than a first-fix-of-a-session flag so that a client fetching a
mid-track page with `?since=` knows which segment it is in without having to hold the point before it.
Like `seq` it does not restart within a generation, and it does not restart after a clear either; it is
only promised to be monotonic and to change exactly at the breaks. It maps directly onto `<trkseg>`.
Points recorded before this field existed all carry `seg` `0`.

There is no `<fix>`, `<hdop>`, `<vdop>` or `<pdop>`: nothing in the platform's location API carries
them, and getting them would mean parsing NMEA `GGA`/`GSA` alongside the fixes. That is a different
scale of work, with a power cost that has not been measured, so it is deliberately left out for now.

Points are returned in `seq` order and streamed off the database cursor, so a long track does not
have to fit in memory twice. There is no paging: `since` is the only way to ask for less.

### DELETE /track

Throws every recorded point away, and returns the [status object](#get-status).

**Refused while recording** — `409` with `"error":"recording"` and nothing deleted. Call
[`POST /stop`](#post-stop) first. The refusal is deliberate: the recording thread is appending as the
request runs, and a client tailing `/stream` would otherwise go on being handed points belonging to a
track it has been told no longer exists.

On success `count` becomes `0`, `lastSeq` becomes `0`, and **`generation` increases by one**. A
[`status` event](#the-status-event) carrying the new `generation` goes out to every open `/stream`, so a
client holding a copy of the track hears about it rather than discovering it on its next poll.

`seq` does *not* restart. The next fix recorded carries on above the highest id ever handed out, so
a client polling `/track?since=1919` after a clear is never served a different set of points under
ids it already believes it has. That is also why `generation` exists: it is the only reliable signal
that the points a client holds are gone. Store it alongside your copy of the track, compare it on
every `/status`, and when it changes, discard everything and re-fetch from `/track` with no `since`.

The database file is vacuumed, so the disk space actually comes back.

### GET /stream

A [Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events) tail: one
event per fix as it is recorded, plus a `status` event whenever the recorder's state changes. Content
type `text/event-stream`, sent unbuffered and uncompressed.

```
retry: 3000

event: status
data: {"recording":true,"lastSeq":550,…,"fields":["seq","ts",…,"seg"],…}

id: 551
data: [551,1785174196371,48.7062102,21.2367301,279.4,1.9,0.4,183.0,237.3,2.4,0.3,12.0,9,"fused",3]
```

An unnamed event is a point: the `data:` payload is one point in exactly the positional encoding
`/track` uses — same order, same rounding, same nulls. `id:` is the point's `seq`, which is what comes
back as `Last-Event-ID`.

#### The status event

A **named** `status` event carries the whole [status object](#get-status), the same one `/status`
returns. `EventSource` delivers named events only to a matching `addEventListener`, so a client that
handles just `message` ignores these and behaves exactly as it did before they existed.

One arrives **on connect**, before any point, so a client that never calls `/status` still knows what it
has joined — and, since the object names the point columns in `fields`, how to decode the rows that
follow. After that, one arrives whenever the status **changes**: on start, on stop, and on
[`DELETE /track`](#delete-track). A permission being granted or revoked, the battery exemption changing
or the OEM item being acknowledged is noticed the next time the app's own screen is resumed, which is
the only moment the platform gives it to notice — those are set in Settings, with nothing to call back.

An identical snapshot is never sent twice, so receiving one means something is genuinely different.

That is enough to drop a `/status` poll entirely. It also closes a real hole rather than only saving a
request: a client that stays connected across a stop, a clear and a fresh start used to see `seq` simply
carry on, with the `generation` bump discoverable only by polling. Now it arrives at the moment it
happens.

Status events deliberately carry **no `id:`**. `Last-Event-ID` is a point cursor, and a status frame
that set it would have a reconnecting client resume from something that is not a `seq` — losing points,
or replaying them. It follows that a reconnect never "replays" missed status events; it gets one fresh
snapshot on connect, which is strictly better.

| how to resume | |
| --- | --- |
| `Last-Event-ID` header | sent automatically by `EventSource` on reconnect. Replays everything after that `seq`, then continues live |
| `?since=N` | same thing for clients that cannot set headers |
| neither | the stream starts from the next fix recorded; nothing is replayed |

The subscription is registered *before* the replay query runs, so a fix recorded during the replay is
queued rather than lost, and the overlap is dropped by `seq`. Connecting when nothing is recording is
fine — the stream stays open and idle.

A `: ping` comment frame arrives every 15 seconds. It keeps the connection from being closed as idle
and turns a client that vanished without a FIN into a write error rather than a silent hang.

A client that stops reading has its connection closed once the server-side queue fills (256 points,
roughly four minutes at 1 Hz) rather than being given a silent hole in its tail. `EventSource` then
reconnects on its own and the `Last-Event-ID` replay fills the gap.

A cleared track is signalled by the `status` event above, whose `generation` is what tells a client its
points are gone. `DELETE /track` is refused while recording, so a live tail cannot have the track pulled
out from under it in the first place; the event covers the case where a client stays connected across a
stop, a clear and a fresh start, and would otherwise see `seq` simply continue.

### POST /start

Starts recording. Returns the [status object](#get-status).

| status | |
| --- | --- |
| `200` | recording. Already recording is also `200` — the call is idempotent |
| `400` | `"error":"bad config"`. The body was not JSON, or a value was not a number |
| `403` | `"error":"setup incomplete"`. `canRecord` is false; the missing item is in the same body |
| `409` | the platform refused the start anyway. `error` carries the exception class name |

**The battery-optimisation exemption is required**, and this endpoint is the reason: it is answered
from the app process while the browser is in front, so the start it makes is a *background*
foreground-service start, which Android 12+ refuses without the exemption. Rather than let that
surface as a `409` after a page has been told it may record, `batteryExempt` is one of `canRecord`'s
terms — so a recorder that is not exempt answers `403 setup incomplete` up front, naming the item to
resolve. The user resolves it once, in the app's own checklist, and every later start from the page
works.

#### The recording config

The body is optional JSON saying **what gets recorded**. Every key is optional, and unknown keys are
ignored — which is what lets an older page and a newer recorder go on understanding each other in both
directions.

```jsonc
{
  "intervalMs": 1000,      // desired interval between fixes
  "minDistanceM": 0,       // minimum displacement before a fix is recorded
  "maxAccuracyM": 50,      // drop fixes whose `acc` is worse than this; null = keep everything
  "priority": "high"       // "high" | "balanced" | "low"
}
```

A key you leave out keeps whatever value is already in force, so `{"maxAccuracyM":50}` changes one
thing. `maxAccuracyM` is the one key where an explicit `null` means something: it turns the filter back
off. A fix that reports no accuracy at all is kept whatever the floor says — the floor is a statement
about bad fixes, not about silent ones.

`priority` is the power/accuracy trade: `high` drives the GNSS receiver, `balanced` accepts
network-derived fixes, `low` asks for as little as the platform will do. An unrecognised value leaves
the previous priority alone rather than failing the request, and you see that in the `config` you get
back.

These are enforced on **this** side rather than left to the client, because filtering in the browser
still burns the battery and still fills the database, and because every other client would then see a
different track from the one that was recorded.

Values are clamped to what the platform will honour — `intervalMs` to 200 ms … 1 hour, `minDistanceM`
to 0 … 100 km — and the clamped result is what comes back as `config` in the
[status object](#get-status). Compare what you asked for against what you got; there is no error for
having asked for something out of range.

**The config persists.** A start with no body records with whatever was last asked for, which is also
what a recording started from the app's own button or from a `freemap-gps-recorder://start` link uses. So a
page that has a settings dialog does not have to resend it, and the phone's own UI honours what was
chosen on the web.

**A start against a recording that is already running is still `200`, and still idempotent.** Every one
of these values can be changed without interrupting the session, so the new config is applied live to
the running recording and reported back in `config` rather than being refused with a `409`.

The `409` is a safety net rather than a path a page should expect to walk. It carries whatever the
platform threw — nearly always `ForegroundServiceStartNotAllowedException`, whose one cause is the
missing exemption that the `403` above already turns away. If it does arrive, the
`freemap-gps-recorder://` link route in the [README](README.md#launching-from-the-web) is the fallback: it
brings the app forward first, so there is no background start to refuse.

The response is not sent until the service has actually settled into the state that was asked for (up
to 3 seconds) — recording, and running with this config — so the body is the real state and not a
hopeful guess.

### POST /stop

Stops recording. Takes no body. Always `200` with the [status object](#get-status); stopping when
nothing is recording is a no-op, not an error. The recorded track is kept — use
[`DELETE /track`](#delete-track) to discard it.

As with `/start`, the response waits for the service to settle.

**There is no pause.** A break in a recording is a `/stop` and a later `/start`. That costs nothing a
pause would have saved — the fixes stop, the GNSS subscription and the wake lock go, and the next start
opens a new [`seg`](#get-track), exactly as a pause and a resume did — and it is reliable from a
backgrounded page now that `canRecord` requires the battery exemption.

## CORS

The API is meant to be called by a page on freemap.sk, so it answers CORS — but only for an
allowlist, since any site you visit could otherwise talk to a recorder running on your phone.

| origin | |
| --- | --- |
| `https://freemap.sk` | |
| `https://www.freemap.sk` | |
| `https://www.freemap.eu` | |
| `local.freemap.sk` | any port, and http as well as https — a dev server picks its own port |

`Access-Control-Allow-Origin` takes one origin and never a list, so the caller's own `Origin` is
matched against that list and echoed back. `Vary: Origin` is on every response, including ones with
no origin to echo. An origin that is not on the list gets no `Access-Control-Allow-Origin` header at
all and the browser refuses the response. A request with no `Origin` at all — curl, the address bar —
is unaffected either way.

Matching is exact, and an origin is scheme **and** host **and** port: `https://freemap.sk` does not
cover `https://www.freemap.sk`, `http://www.freemap.sk` or `https://www.freemap.eu:8443`. A new
hostname for the site needs a new entry in `ALLOWED_ORIGINS` in `RecorderApi.kt`.

Preflights answer `204` with `Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS`,
`Access-Control-Allow-Headers: Content-Type, Last-Event-ID, Cache-Control`, a 24-hour
`Access-Control-Max-Age`, and `Access-Control-Allow-Private-Network: true` — which older Chrome
requires under the Private Network Access model and newer Chrome ignores under Local Network Access.

Note that a page served over plain `http` from a hostname that merely *resolves* to 127.0.0.1 is not
a secure context — trustworthiness is decided by hostname, and only `localhost` and IP literals
qualify. Such a page may be blocked from reaching loopback before CORS is ever consulted.

## Who can reach this

The allowlist above governs **browsers**, which is what it is for: it stops a page you happen to be
visiting from talking to a recorder on your phone. It is not authentication, and it does not stop
anything else on the device. There are no tokens and no auth of any kind — any app on the phone can
read the whole track, start or stop a recording, and since `DELETE /track` exists, throw the track
away. A rejected `Origin` only means the *browser* discards the response; the server still acted.

That is the trade for having no accounts, no pairing step and no shared secret to keep. The exposure
is bounded by the loopback bind: nothing off the device can reach the API at all, and an app that is
already running on your phone with the INTERNET permission has better ways to track you than this.

## Errors

| status | body | |
| --- | --- | --- |
| `400` | the status object with `"error":"bad config"` | `POST /start` with a body that is not a valid config |
| `403` | `{"recording":…,"error":"setup incomplete"}` | `POST /start` without `canRecord` |
| `404` | `{"error":"no such endpoint"}` | unknown path |
| `405` | `{"error":"method not allowed"}` | known path, wrong method |
| `409` | the status object with an `error` | start refused by the platform anyway, or clear while recording |
| `500` | `{"error":"internal"}` | a bug. Logged with a stack trace under tag `RecorderApi` |

`400`, `403` and `409` carry the whole status object, not just the error, so a page can say what is
actually wrong without a second request.

## Trying it

The API is loopback-only, so reaching it from a development machine means forwarding a port:

```sh
adb forward tcp:8378 tcp:8378
curl http://127.0.0.1:8378/status
curl 'http://127.0.0.1:8378/track?since=0'
curl -N http://127.0.0.1:8378/stream
curl -X POST http://127.0.0.1:8378/start
curl -X POST http://127.0.0.1:8378/stop
curl -X DELETE http://127.0.0.1:8378/track
```

With a config, and reading back the effective one:

```sh
curl -X POST -H 'Content-Type: application/json' \
  -d '{"intervalMs":5000,"maxAccuracyM":30,"priority":"balanced"}' \
  http://127.0.0.1:8378/start
curl -s http://127.0.0.1:8378/status | python3 -m json.tool
```

To check CORS behaviour, send an `Origin` and look at what comes back:

```sh
curl -si -H 'Origin: https://www.freemap.sk' http://127.0.0.1:8378/status | grep -i allow-origin
```

## Keeping this in sync

`./gradlew checkApiDocs` reads `RecorderApi.kt` and this file and fails when they disagree on:

- **which paths exist** — the route table against the endpoint headings here
- **which methods are allowed** — the `Access-Control-Allow-Methods` header against the methods in
  those headings
- **which fields `/status` returns** — every JSON key `statusJson` emits must appear here

`check` and `releaseApk` both depend on it, so an undocumented endpoint or a stale field list fails
the build rather than shipping. It compares names, not meaning — prose still has to be kept honest by
hand.
