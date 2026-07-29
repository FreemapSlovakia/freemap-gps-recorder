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
| [`POST /start`](#post-start) | start recording |
| [`POST /stop`](#post-stop) | stop recording |

Every response is `application/json` except `/stream`, which is `text/event-stream`.

### GET /status

Takes no parameters. Always answers `200` with the status object:

```json
{"recording":false,"lastSeq":1919,"count":1919,"generation":0,
 "version":{"code":4,"name":"0.4"},
 "port":8378,"portEcho":null,
 "permissions":{"fine":true,"background":true,"notifications":true},
 "batteryExempt":true,
 "oem":{"vendor":"xiaomi","needed":true,"acknowledged":false},
 "canRecord":true,"setupComplete":false}
```

| field | |
| --- | --- |
| `recording` | whether a recording is in progress right now |
| `lastSeq` | highest `seq` currently stored, or `0` when the track is empty. Poll `/track?since=` with this |
| `count` | how many points are stored |
| `generation` | how many times the track has been cleared — see [`DELETE /track`](#delete-track) |
| `version.code` | `versionCode` of the installed recorder. Compare against what your page needs |
| `version.name` | human-readable version, e.g. `"0.4"` |
| `port` | the port this recorder is listening on, always `8378` |
| `portEcho` | the `port` from the last `freemap-gps-recorder://` link, or `null` — see [Launching from the web](README.md#launching-from-the-web) |
| `permissions.fine` | `ACCESS_FINE_LOCATION` granted |
| `permissions.background` | `ACCESS_BACKGROUND_LOCATION` granted |
| `permissions.notifications` | `POST_NOTIFICATIONS` granted |
| `batteryExempt` | app is exempt from battery optimisation |
| `oem.vendor` | `"xiaomi"`, `"huawei"`, `"samsung"`, `"oppo"`, `"vivo"`, `"oneplus"`, or `null` |
| `oem.needed` | this manufacturer needs a per-app battery setting changed by hand |
| `oem.acknowledged` | the user has said they did it — nothing on the platform can verify this |
| `canRecord` | the hard gate: `permissions.fine && permissions.notifications`. `POST /start` fails without it |
| `setupComplete` | everything above resolved, including the items that only make a *long* recording survive |
| `error` | present only on a failed `/start` or `DELETE /track` — see those endpoints |

`canRecord` is what to check before offering a start button. `setupComplete` being false is not an
error: recording works without those items, it is just liable to be killed after a while.

The same status object is the body of every `/start`, `/stop` and `DELETE /track` response, so one
request tells you both what happened and where things now stand.

### GET /track

The whole track, or the part of it a client has not seen.

| parameter | |
| --- | --- |
| `since` | return only points with `seq` **greater than** this. Optional; omit or `0` for the whole track |

```json
{"fields":["seq","ts","lat","lon","alt","acc","spd","brg"],
 "points":[[550,1785174195365,48.7062033,21.2367267,279.2,1.9,0.0,null]]}
```

Points are encoded positionally, in the order named by `fields`, to keep a long track small. Read
the order from `fields` rather than hard-coding it.

| field | |
| --- | --- |
| `seq` | id of the point. Strictly increasing, never reused, gapless within one generation |
| `ts` | UTC milliseconds since the epoch, from the location fix itself and not from the clock on receipt |
| `lat`, `lon` | WGS84 degrees, rounded to 7 decimals (~1 cm) |
| `alt` | metres above the WGS84 ellipsoid, or `null` |
| `acc` | horizontal accuracy in metres, 68% confidence, or `null` |
| `spd` | ground speed in m/s, or `null` |
| `brg` | direction of travel in degrees clockwise from true north, or `null` |

Everything after `lon` is rounded to 2 decimals. A field the platform reported as absent is `null`
and never `0` — an absent speed and a genuine standstill are different facts.

Points are returned in `seq` order and streamed off the database cursor, so a long track does not
have to fit in memory twice. There is no paging: `since` is the only way to ask for less.

### DELETE /track

Throws every recorded point away, and returns the [status object](#get-status).

**Refused while recording** — `409` with `"error":"recording"` and nothing deleted. Call
[`POST /stop`](#post-stop) first. The refusal is deliberate: the recording thread is appending as the
request runs, and a client tailing `/stream` would otherwise go on being handed points belonging to a
track it has been told no longer exists.

On success `count` becomes `0`, `lastSeq` becomes `0`, and **`generation` increases by one**.

`seq` does *not* restart. The next fix recorded carries on above the highest id ever handed out, so
a client polling `/track?since=1919` after a clear is never served a different set of points under
ids it already believes it has. That is also why `generation` exists: it is the only reliable signal
that the points a client holds are gone. Store it alongside your copy of the track, compare it on
every `/status`, and when it changes, discard everything and re-fetch from `/track` with no `since`.

The database file is vacuumed, so the disk space actually comes back.

### GET /stream

A [Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events) tail: one
event per fix, as it is recorded. Content type `text/event-stream`, sent unbuffered and uncompressed.

```
retry: 3000

id: 551
data: [551,1785174196371,48.7062102,21.2367301,279.4,1.9,0.4,183.0]
```

The `data:` payload is one point in exactly the positional encoding `/track` uses — same order, same
rounding, same nulls. `id:` is the point's `seq`, which is what comes back as `Last-Event-ID`.

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

Nothing on this stream signals a cleared track. `DELETE /track` is refused while recording, so a live
tail cannot have the track pulled out from under it — but a client that stays connected across a
stop, a clear and a fresh start will see `seq` simply continue. Watch `generation` on `/status`.

### POST /start

Starts recording. Takes no body. Returns the [status object](#get-status).

| status | |
| --- | --- |
| `200` | recording. Already recording is also `200` — the call is idempotent |
| `403` | `"error":"setup incomplete"`. `canRecord` is false; the missing item is in the same body |
| `409` | the platform refused the start. `error` carries the exception class name |

The `409` is nearly always `ForegroundServiceStartNotAllowedException`: Android 12+ refuses to let a
backgrounded app start a foreground service unless it is exempt from battery optimisation, which is
what `batteryExempt` reports. If a page needs to start a recording while the app is not in front, the
`freemap-gps-recorder://` link route in the [README](README.md#launching-from-the-web) is the reliable one
— it brings the app forward first.

The response is not sent until the service has actually settled into the recording state (up to 3
seconds), so `recording` in the body is the real state and not a hopeful guess.

### POST /stop

Stops recording. Takes no body. Always `200` with the [status object](#get-status); stopping when
nothing is recording is a no-op, not an error. The recorded track is kept — use
[`DELETE /track`](#delete-track) to discard it.

As with `/start`, the response waits for the service to settle.

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
| `403` | `{"recording":…,"error":"setup incomplete"}` | `POST /start` without `canRecord` |
| `404` | `{"error":"no such endpoint"}` | unknown path |
| `405` | `{"error":"method not allowed"}` | known path, wrong method |
| `409` | the status object with an `error` | start refused by the platform, or clear while recording |
| `500` | `{"error":"internal"}` | a bug. Logged with a stack trace under tag `RecorderApi` |

`403` and `409` carry the whole status object, not just the error, so a page can say what is actually
wrong without a second request.

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
