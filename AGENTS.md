# mcinject — agent reference

This is the machine-facing contract for driving a running Minecraft client through the injected
agent. If you are an LLM operating the game, everything you need is here. Prose overview is in
`README.md`; this document is the grammar and the endpoint list.

## Getting oriented

1. The agent must be attached (`./bin/mcinject-attach attach`). It writes `~/.mcinject/session.json`
   with the loopback `port` and `token`. Every request needs header `X-MCInject-Token: <token>`.
2. Call `GET /status` first. It tells you the discovered channels, which one is tapped, the server
   address, and the packet count. If nothing is tapped and no game channel exists, the player is not
   connected to a server yet.
3. The **MCP server** (`mcp/server.js`) wraps all of this as tools and handles the token for you. If
   you have it, prefer the `mc_*` tools. This document is what those tools call underneath, and what
   to use if you are speaking to the HTTP API directly.

## The core idea

Class and field names may be obfuscated and change between versions, so **don't rely on names —
rely on values**. The workflow is always:

1. `POST /search {"contains": "<a value you recognise>"}` — a player name, item label, or number.
2. Read back a `path` and a `handle` (`hN`) from the results.
3. From that handle, `POST /get` fields, `POST /call` methods, or `POST /inspect` the subtree.

Handles are stable string IDs for live objects; use them across calls.

## Target grammar

Anywhere a `target` or `path` is accepted, these forms work:

- `hN` — a handle returned by a previous call (e.g. `h42`).
- `primary` — the channel of the current game connection (the tapped one).
- `channels` — the list of all discovered Netty channels.
- `static:fully.qualified.Class#FIELD` — a static field as a root. Note the `#` before the field.
- A path expression from any root: `h42.slots[21].container`, `primary.pipeline()`,
  `static:net.minecraft.client.Minecraft#instance.player`. Dots read fields; `name()` calls a
  zero-arg method; `[i]` indexes arrays / lists / maps.

## Value-spec grammar

Method arguments, field values, and search roots are **value specs**. A plain JSON scalar
(`5`, `"hi"`, `true`, `null`) passes through as itself. An object with one of these keys is resolved
to a live object:

| Spec | Resolves to |
|---|---|
| `{"h": "h42"}` | the object behind that handle |
| `{"path": "h42.field.sub"}` | the result of a path expression |
| `{"seq": 12345}` | the retained packet object with that sequence |
| `{"class": "some.Class"}` | the `Class` object (use as target for static calls) |
| `{"null": true}` | Java `null` |
| `{"enum": {"class": "...", "name": "PICKUP"}}` | an enum constant (accepts `"ordinal"` as fallback) |
| `{"cast": {"type": "int", "value": 3}}` | coerce to a primitive type |
| `{"new": {"class": "...", "args": [...], "types": [...]}}` | construct an instance |
| `{"field": {"target": <spec>, "name": "x"}}` | read a field, inline |
| `{"call": {"target": <spec>, "method": "m", "args": [...], "types": [...]}}` | call a method, inline |
| `{"array": {"type": "int", "items": [...]}}` | build a typed array |

Specs nest. Optional `types` (a list of parameter type names, simple or fully qualified) disambiguates
overloads. Numbers auto-coerce to the target's primitive type.

## Endpoints

All POST bodies are JSON. Responses are JSON. Read helpers return
`{class, handle, summary, value}` where `value` is a bounded JSON tree (control depth with `depth`,
node budget with `maxNodes`).

| Method + path | Purpose |
|---|---|
| `GET /health` | liveness, no auth |
| `GET /status` | channels, tap state, server, packet count |
| `GET /log?n=` | recent agent log lines |
| `GET /channels` | every discovered channel, with pipeline layout |
| `POST /tap/install` `{channel?}` | splice the tap in (auto-installs by default) |
| `POST /tap/remove` `{channel?}` | remove the tap |
| `GET /packets?limit=&dir=&type=&since=` | recent packet metadata, newest first |
| `GET /packet?seq=&depth=` | full contents of one captured packet |
| `GET /packets/stats` | counts by type + active mute/watch/block rules |
| `POST /packets/config` `{mute?,watch?,block?,recording?,retainCount?}` | tune capture (persists) |
| `POST /packets/reset` | clear rings and counters |
| `GET /events?since=&wait=` | events since a sequence (long-poll with `wait` ms) |
| `POST /inspect` `{target, depth?, maxNodes?}` | expand any object to a JSON tree |
| `POST /get` `{target, name}` | read a field |
| `POST /set` `{target, name, value}` | write a field (finals included) |
| `POST /call` `{target, method, args?, types?, onGameThread?}` | invoke a method |
| `POST /new` `{class, args?, types?}` | construct an object |
| `POST /clone` `{target, overrides}` | copy a packet with components replaced |
| `POST /batch` `{ops: [...]}` | many get/set/call/inspect ops in one round-trip |
| `POST /search` `{contains?|regex?|ofClass?, roots?, depth?, maxResults?}` | BFS the live heap |
| `GET /classes?contains=&limit=` | loaded class names matching a substring |
| `GET /class?name=` | fields, methods, constructors of a class |
| `POST /send` `{packet, dir}` | send a packet — `dir:"out"` to server, `dir:"in"` to client |
| `POST /shutdown` | detach cleanly (tap removed, game keeps running) |

`/batch` op shape: `{"op": "get|set|call|inspect", "target": <spec>, ...}` with the same fields the
standalone route takes. A failing op returns `{"error": "..."}` in place and does not abort the rest.

## Writing to the game — three levels

1. **Drive the client's own methods** (safest). Call what the game itself calls for an action, e.g.
   a slot click via `MultiPlayerGameMode.handleContainerInput(...)`. The client builds the correct
   packet *and* updates its state, so it cannot desync.
2. **Clone and mutate** (`POST /clone`). Capture a real packet, replace one component, send it back.
   No constructor knowledge needed; most modern packets are records so the copy is exact.
3. **Construct and send** (`POST /new` → `POST /send`). Full control, most fragile.

## The one rule that prevents crashes

Anything that **mutates game state** (opening a screen, clicking a slot, sending from the client)
must run on the game thread, not the HTTP thread. Pass `onGameThread` on `/call` set to the client
instance: `{"onGameThread": {"path": "static:net.minecraft.client.Minecraft#instance"}}`. Minecraft's
client implements `java.util.concurrent.Executor` — an unobfuscated interface — which is how this
works across versions. Pure reads do not need it.

## Failure modes to expect

- **"unknown or expired handle"** — handles are LRU-bounded; re-`search` to get a fresh one.
- **"packet no longer retained"** — only the most recent `retainCount` packet objects are kept. Add
  the type to the `watch` list (it gets decoded to JSON eagerly and survives) or raise `retainCount`.
- **`NoSuchFieldException` / `NoSuchMethodException`** — the message lists available members; that
  listing is the fastest way to explore an unfamiliar class. `GET /class` gives the full picture.
- **Empty `/search`** — widen `depth`, change `roots`, or search by `ofClass` instead of `contains`.
