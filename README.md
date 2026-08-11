# mcinject

Attach to a **running** Minecraft client, tap its Netty pipeline, and read or write packets live —
no restart, no mod, no dropped connection. On top of that sits a generic reflection layer that can
read any field, call any method, and search the live heap of the game process. Drive it three ways:
a **CLI**, a plain **HTTP/JSON API**, or an **MCP server** so an LLM agent can operate the game
directly.

It is a general-purpose runtime-control layer, not a bot for one task. Anything the client can do,
you can do through it: observe traffic, inspect and mutate live game state, drive the client's own
input methods, or craft and send raw packets. The player keeps playing the whole time — attaching,
hot-reloading the agent, and detaching are all invisible from inside the game.

## How it works

**Attach, don't patch.** `bin/mcinject-attach` uses the JVM attach API to load an agent into the live
game process. The JVM starts an attach listener on demand and calls `agentmain` on a new thread while
the game keeps rendering.

**Hook Netty, not Minecraft.** Minecraft's classes may be obfuscated; Netty never is, and the
pipeline handler names Minecraft installs (`packet_handler`, `decoder`, `splitter`, …) are string
literals that survive obfuscation. The agent finds the live connection by walking out from Netty's
event-loop threads until it hits `Channel` instances, then splices a `ChannelDuplexHandler` in with
`addBefore("packet_handler", …)`. That position sees fully decoded packet objects in both directions.
No bytecode is rewritten, so the tap goes in and comes out cleanly.

**Bridge the classloaders with `java.base` types only.** Under Fabric, Netty lives in KnotClassLoader
while the agent lives in the system loader — the agent can't even name `ChannelDuplexHandler`. So the
tap's bytecode is defined directly into Netty's package via a private `MethodHandles.Lookup`, and it
calls back through `java.util.function.Function`, a type both loaders agree on.

**Search the heap instead of relying on mappings.** Obfuscation hides names but not data. Ask for a
value you recognise — a player's name, an item label, a number — and get back a path and a handle you
can read fields from or call methods on.

**Reload without restarting.** The agent jar contains only a small bootstrap. The real code lives in a
separate core jar loaded through a fresh child-first `URLClassLoader` on every attach, so re-attaching
genuinely replaces the code — new methods, new classes, new endpoints included. `Instrumentation.
redefineClasses` can't do that, and a jar already on the system classpath is never re-read.

```
bin/mcinject-attach ──attach API──►  Minecraft JVM
                                      └─ Boot (permanent, tiny)
                                          └─ CoreLoader (fresh per attach)
                                              ├─ HTTP control API  ◄── bin/mci, mcp/server.js
                                              └─ McInjectTap ──► spliced into the live Netty pipeline
```

## Setup

Requirements: a **JDK 21+** to build (the launcher ships a JRE, which can't compile), and **Node**
if you want the MCP server. Works on macOS and Linux; on Windows use WSL or Git Bash.

`build.sh` finds a JDK via `JAVA_HOME`, `javac` on `PATH`, or `~/.mcinject/jdk/`. If none is found it
prints the exact one-line command to fetch a no-install JDK from Adoptium for your OS and architecture.

```bash
./build.sh                        # javac + jar, no Maven or Gradle
./bin/mcinject-attach list        # shows JVMs, marks likely Minecraft clients
./bin/mcinject-attach attach      # auto-detects the game, injects, installs the tap
```

The agent writes `~/.mcinject/session.json` (port + token, mode 0600). The control API binds to
loopback and requires that token: it can call arbitrary methods inside the game process.

`./bin/mci-reload` rebuilds and re-attaches — the normal edit/test loop, with the game still running.

## CLI

```bash
./bin/mci status                  # connection, pipeline layout, tap state
./bin/mci packets -n 30           # recent packets, newest first
./bin/mci packet 12345            # full field-by-field contents
./bin/mci stats                   # counts by type — what this server actually sends
./bin/mci search "some text"      # find a value in the live heap
./bin/mci get h42 containerId     # read a field (errors list available names)
./bin/mci call h42 getHoverName   # invoke a method
./bin/mci config mute=MoveEntity,LevelChunk watch=SystemChat
./bin/mci events --follow         # live stream
```

Targets are `h42` (a handle), `primary` (the tapped channel), `seq:123` (a captured packet),
`static:some.Class#FIELD`, or a path like `h42.slots[21].container`.

### Capture rules

Movement and terrain packets are most of the traffic and rarely what you want. Rules match a
case-insensitive substring of the packet class name:

- **mute** — never recorded. Sensible defaults ship in `~/.mcinject/config.json`.
- **watch** — eagerly decoded to JSON and pushed to the event stream, so it survives the retain window.
- **block** — dropped entirely: neither the game nor the server ever sees the packet.

## Writing packets

Three ways, in increasing order of how much you need to know:

1. **Drive the client.** Call the method the game itself would call — e.g.
   `MultiPlayerGameMode.handleContainerInput(...)` for a slot click. The client builds the correct
   packet *and* updates its own state, so it can't desync. Pass `onGameThread` for anything mutating:
   Minecraft's client is a `java.util.concurrent.Executor`, and that interface is never obfuscated.
2. **Clone and mutate.** Capture a real packet, replace a component, send it back. Works without
   knowing any constructor, and most modern packets are records, so the copy is exact.
3. **Construct one.** `{"new": {"class": "...", "args": [...]}}`, then `/send`.

`dir: "out"` sends to the server as if the client produced it. `dir: "in"` feeds a packet to the
client as if the server had sent it — useful for injecting UI and messages.

## MCP server — for LLM agents

Point any MCP-capable agent at `mcp/server.js` (Node, no dependencies — MCP over stdio is just
newline-delimited JSON-RPC). With Claude Code, from the repo root:

```bash
claude mcp add mcinject -- node "$(pwd)/mcp/server.js"
```

Or add it to your client's MCP config by hand:

```json
{
  "mcpServers": {
    "mcinject": { "command": "node", "args": ["/absolute/path/to/mcinject/mcp/server.js"] }
  }
}
```

The server reads `~/.mcinject/session.json` to reach whichever client is currently attached, so the
agent needs no host, port, or token of its own. Twenty tools in two tiers.

Low-level and version-agnostic — enough to do anything the game process can:
`mc_status`, `mc_packets`, `mc_packet`, `mc_stats`, `mc_configure`, `mc_search`, `mc_inspect`,
`mc_get`, `mc_set`, `mc_call`, `mc_clone_packet`, `mc_send_packet`, `mc_describe_class`,
`mc_find_classes`, `mc_events`.

Composed conveniences built from those: `mc_player_state`, `mc_chat_recent`, `mc_say`,
`mc_open_menu`, `mc_click_slot`.

For the full machine contract an agent drives — value-spec grammar, path grammar, every HTTP
endpoint, and the one thread-safety rule — see [`AGENTS.md`](AGENTS.md).

A worked example — an agent clicking a button in whatever menu is open, with nothing hardcoded:

```
mc_open_menu                     → returns each slot's index and item name
mc_click_slot {slot: <n>}        → client builds and sends the real click packet
mc_player_state                  → confirm the resulting state change
```

## Version resilience

Verified against Minecraft 26.2 + Fabric on Netty 4.2 (macOS/KQueue). Nothing here hardcodes a
protocol version or packet ID. Where the game's own API has churned, the tools try the current name
and fall back — `handleContainerInput` → `handleInventoryMouseClick`, `ContainerInput` → `ClickType`.
When a name has moved, `mc_find_classes` and `mc_describe_class` find the new one, and `mc_search`
works even when every name is meaningless.

Netty discovery deliberately names no field, so it spans Netty 4.1's `NioEventLoop` and 4.2's
`SingleThreadIoEventLoop`/`IoHandler` split.

## Notes and limits

- **Detach cleanly** with `./bin/mci shutdown`: the tap comes out of the pipeline and the game
  continues unaffected. The tap also fails open — an exception in a callback passes the packet
  through untouched rather than disconnecting the player.
- **The control API is full control** of the game process. Loopback + token, and don't forward it.
- **Server rules are your problem.** This can send packets a vanilla client never would, which on many
  servers is indistinguishable from cheating. Driving the client's own methods (option 1 above) stays
  within what the client would legitimately do; hand-built packets do not.
- **The bootstrap class can't hot-reload.** Everything else can. Changing `boot/` is the one edit that
  needs the game restarted.
- **Retention is bounded.** Metadata for the last 8192 packets, full objects for the most recent
  `retainCount` (default 128; chunk packets are large). Add a `watch` rule for anything you need to
  read later.
