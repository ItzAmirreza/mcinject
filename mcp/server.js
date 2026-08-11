#!/usr/bin/env node
/**
 * MCP server exposing a running Minecraft client to an LLM agent.
 *
 * Zero dependencies on purpose — MCP over stdio is just newline-delimited JSON-RPC, and this whole
 * project avoids dragging a dependency tree next to a live game process.
 *
 * Two tiers of tools. The low-level ones (search / get / call / send_packet) are version-agnostic and
 * can reach anything in the JVM; the high-level ones (player_state, open_menu, click_slot, say) are
 * thin compositions over them, and exist because an agent shouldn't have to rediscover how to read a
 * chat component every session.
 */
'use strict';

const fs = require('fs');
const http = require('http');
const os = require('os');
const path = require('path');

const SESSION = path.join(os.homedir(), '.mcinject', 'session.json');

// ---------------------------------------------------------------- agent transport

function session() {
  try {
    return JSON.parse(fs.readFileSync(SESSION, 'utf8'));
  } catch {
    throw new Error(
      'No mcinject agent session found. Start Minecraft, then run: ./bin/mcinject-attach attach'
    );
  }
}

function agent(method, urlPath, body) {
  const s = session();
  const payload = body === undefined ? null : Buffer.from(JSON.stringify(body));
  const opts = {
    host: '127.0.0.1',
    port: Number(s.port),
    path: urlPath,
    method,
    headers: { 'X-MCInject-Token': s.token },
  };
  if (payload) {
    opts.headers['Content-Type'] = 'application/json';
    opts.headers['Content-Length'] = payload.length;
  }
  return new Promise((resolve, reject) => {
    const req = http.request(opts, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        let parsed;
        try {
          parsed = JSON.parse(text);
        } catch {
          return reject(new Error(`agent returned non-JSON (HTTP ${res.statusCode}): ${text.slice(0, 400)}`));
        }
        if (res.statusCode >= 400) return reject(new Error(parsed.error || text.slice(0, 400)));
        resolve(parsed);
      });
    });
    req.on('error', (e) =>
      reject(new Error(`cannot reach the agent (${e.message}). Is Minecraft still running?`))
    );
    if (payload) req.write(payload);
    req.end();
  });
}

/** "h42" | "primary" | "seq:123" | "static:some.Class#FIELD" | any path expression → a value spec. */
function target(spec) {
  if (spec == null) throw new Error('a target is required');
  if (typeof spec === 'object') return spec;
  const s = String(spec).trim();
  if (s.startsWith('seq:')) return { seq: Number(s.slice(4)) };
  if (s.startsWith('{')) return JSON.parse(s);
  return { path: s };
}

const MC = 'static:net.minecraft.client.Minecraft#instance';
const call = (t, method, args = [], extra = {}) =>
  agent('POST', '/call', { target: target(t), method, args, inspect: false, ...extra });

/** Components (chat messages, item names) are trees; the game already knows how to flatten them. */
async function componentText(spec) {
  const r = await call(spec, 'getString', [], { inspect: true, depth: 0 });
  return r.summary;
}

// ---------------------------------------------------------------- tools

const tools = [
  {
    name: 'mc_status',
    description:
      'Agent status: the discovered Netty channels, which one is tapped, the server address, and packet counts. Start here.',
    inputSchema: { type: 'object', properties: {} },
    run: () => agent('GET', '/status'),
  },
  {
    name: 'mc_player_state',
    description:
      'Player snapshot: name, position, health, food, dimension, the currently open container menu, and the server address.',
    inputSchema: { type: 'object', properties: {} },
    run: playerState,
  },
  {
    name: 'mc_packets',
    description:
      'Recent packets, newest first, as {seq, dir, type, handle}. dir "in" is server→client, "out" is client→server. Use mc_packet to read one. Noisy types can be silenced with mc_configure.',
    inputSchema: {
      type: 'object',
      properties: {
        limit: { type: 'number', description: 'how many to return (default 50)' },
        dir: { type: 'string', enum: ['in', 'out'], description: 'filter by direction' },
        type: { type: 'string', description: 'case-insensitive substring of the packet class name' },
        since: { type: 'number', description: 'only packets with a sequence above this' },
      },
    },
    run: (a) =>
      agent(
        'GET',
        `/packets?limit=${a.limit ?? 50}&dir=${a.dir ?? ''}&type=${encodeURIComponent(a.type ?? '')}&since=${a.since ?? 0}`
      ),
  },
  {
    name: 'mc_packet',
    description:
      'Full field-by-field contents of one captured packet. Only recent packets stay retained; raise retainCount or add a watch rule via mc_configure to keep specific types longer.',
    inputSchema: {
      type: 'object',
      properties: {
        seq: { type: 'number', description: 'sequence number from mc_packets' },
        depth: { type: 'number', description: 'how deep to expand (default 6)' },
      },
      required: ['seq'],
    },
    run: (a) => agent('GET', `/packet?seq=${a.seq}&depth=${a.depth ?? 6}&maxNodes=600`),
  },
  {
    name: 'mc_stats',
    description: 'Packet counts by type, plus the active mute/watch/block rules. Good for discovering what a server actually sends.',
    inputSchema: { type: 'object', properties: {} },
    run: () => agent('GET', '/packets/stats'),
  },
  {
    name: 'mc_configure',
    description:
      'Tune capture. mute = never record (silence movement spam). watch = eagerly decode and push to the event stream. block = DROP the packet so neither the game nor the server ever sees it. All match on case-insensitive substrings of the packet class name; each list replaces the previous one.',
    inputSchema: {
      type: 'object',
      properties: {
        mute: { type: 'array', items: { type: 'string' } },
        watch: { type: 'array', items: { type: 'string' } },
        block: { type: 'array', items: { type: 'string' } },
        recording: { type: 'boolean' },
        retainCount: { type: 'number', description: 'how many recent packet objects stay inspectable' },
      },
    },
    run: (a) => agent('POST', '/packets/config', a),
  },
  {
    name: 'mc_search',
    description:
      'Breadth-first search of the live game heap for a value you recognise — a player name, an item label, a number. Returns paths and handles you can then read or call. This is the way in when class names are obfuscated or unfamiliar.',
    inputSchema: {
      type: 'object',
      properties: {
        contains: { type: 'string', description: 'case-insensitive substring to find' },
        regex: { type: 'string', description: 'alternative to contains' },
        ofClass: { type: 'string', description: 'match objects whose class name contains this' },
        root: {
          type: 'string',
          description:
            'where to start: a handle like h42, a path, or "static" to sweep net.minecraft statics. Defaults to the tapped connection.',
        },
        depth: { type: 'number', description: 'max hops (default 12)' },
        maxResults: { type: 'number' },
      },
    },
    run: (a) => {
      const body = {
        contains: a.contains,
        regex: a.regex,
        ofClass: a.ofClass,
        depth: a.depth ?? 12,
        maxResults: a.maxResults ?? 40,
      };
      if (a.root === 'static') body.staticsPrefix = 'net.minecraft';
      else if (a.root) body.roots = [target(a.root)];
      return agent('POST', '/search', body);
    },
  },
  {
    name: 'mc_inspect',
    description: 'Expand any live object into a JSON tree. Target: a handle (h42), a path, seq:123, or static:some.Class#FIELD.',
    inputSchema: {
      type: 'object',
      properties: {
        target: { type: 'string' },
        depth: { type: 'number' },
      },
      required: ['target'],
    },
    run: (a) => agent('POST', '/inspect', { target: target(a.target), depth: a.depth ?? 4 }),
  },
  {
    name: 'mc_get',
    description: 'Read one field of a live object. Errors list the available field names, which is the fastest way to explore an unfamiliar class.',
    inputSchema: {
      type: 'object',
      properties: { target: { type: 'string' }, field: { type: 'string' }, depth: { type: 'number' } },
      required: ['target', 'field'],
    },
    run: (a) => agent('POST', '/get', { target: target(a.target), name: a.field, depth: a.depth ?? 3 }),
  },
  {
    name: 'mc_set',
    description: 'Write a field on a live object, final fields included. Changes client-side state only; the server still has its own view.',
    inputSchema: {
      type: 'object',
      properties: {
        target: { type: 'string' },
        field: { type: 'string' },
        value: { description: 'a JSON scalar, or a value spec such as {"h":"h42"}' },
      },
      required: ['target', 'field', 'value'],
    },
    run: (a) => agent('POST', '/set', { target: target(a.target), name: a.field, value: a.value }),
  },
  {
    name: 'mc_call',
    description:
      'Invoke any method on any live object. Arguments are JSON scalars or value specs: {"h":"h42"}, {"path":"..."}, {"enum":{"class":"...","name":"PICKUP"}}, {"new":{"class":"...","args":[...]}}. Set onGameThread when the call mutates game state — running that off the render thread can crash the client.',
    inputSchema: {
      type: 'object',
      properties: {
        target: { type: 'string' },
        method: { type: 'string' },
        args: { type: 'array', description: 'JSON values or value specs' },
        types: { type: 'array', items: { type: 'string' }, description: 'parameter types, to disambiguate overloads' },
        onGameThread: { type: 'boolean', description: 'run on the Minecraft client thread' },
        depth: { type: 'number' },
      },
      required: ['target', 'method'],
    },
    run: (a) => {
      const body = {
        target: target(a.target),
        method: a.method,
        args: a.args ?? [],
        depth: a.depth ?? 3,
      };
      if (a.types) body.types = a.types;
      if (a.onGameThread) body.onGameThread = target(MC);
      return agent('POST', '/call', body);
    },
  },
  {
    name: 'mc_clone_packet',
    description:
      'Copy a captured packet with some components replaced, then optionally send it. The reliable way to write packets without knowing a constructor: observe a real one, change the field you care about, send it back.',
    inputSchema: {
      type: 'object',
      properties: {
        target: { type: 'string', description: 'e.g. seq:12345 or a handle' },
        overrides: { type: 'object', description: 'field/component name → new value' },
      },
      required: ['target', 'overrides'],
    },
    run: (a) => agent('POST', '/clone', { target: target(a.target), overrides: a.overrides }),
  },
  {
    name: 'mc_send_packet',
    description:
      'Write a packet. dir "out" sends it to the server as if the client produced it; dir "in" feeds it to the client as if the server had sent it (useful for fake messages and UI). Target a handle from mc_clone_packet or a constructed object.',
    inputSchema: {
      type: 'object',
      properties: {
        packet: { type: 'string', description: 'handle or path of the packet object' },
        dir: { type: 'string', enum: ['in', 'out'] },
      },
      required: ['packet'],
    },
    run: (a) => agent('POST', '/send', { packet: target(a.packet), dir: a.dir ?? 'out' }),
  },
  {
    name: 'mc_describe_class',
    description: 'Fields, methods and constructors of a loaded class. Use before calling into unfamiliar game code.',
    inputSchema: { type: 'object', properties: { name: { type: 'string' } }, required: ['name'] },
    run: (a) => agent('GET', `/class?name=${encodeURIComponent(a.name)}`),
  },
  {
    name: 'mc_find_classes',
    description: 'Loaded class names containing a substring. Handy when a class was renamed between game versions.',
    inputSchema: {
      type: 'object',
      properties: { contains: { type: 'string' }, limit: { type: 'number' } },
      required: ['contains'],
    },
    run: (a) => agent('GET', `/classes?contains=${encodeURIComponent(a.contains)}&limit=${a.limit ?? 60}`),
  },
  {
    name: 'mc_chat_recent',
    description: 'Recent chat and system messages as plain text. Add "SystemChat" and "PlayerChat" to the watch list first so they stay decoded.',
    inputSchema: { type: 'object', properties: { limit: { type: 'number' } } },
    run: chatRecent,
  },
  {
    name: 'mc_say',
    description: 'Send a chat message as the player. A leading "/" is sent as a command.',
    inputSchema: {
      type: 'object',
      properties: { message: { type: 'string' } },
      required: ['message'],
    },
    run: async (a) => {
      const msg = String(a.message);
      const isCommand = msg.startsWith('/');
      await call(`${MC}.player.connection`, isCommand ? 'sendCommand' : 'sendChat', [
        isCommand ? msg.slice(1) : msg,
      ], { onGameThread: target(MC) });
      return { sent: msg, as: isCommand ? 'command' : 'chat' };
    },
  },
  {
    name: 'mc_open_menu',
    description:
      'The container GUI the player currently has open, as a slot → item-name map. Use this to find which slot a button lives in before mc_click_slot.',
    inputSchema: { type: 'object', properties: {} },
    run: openMenu,
  },
  {
    name: 'mc_click_slot',
    description:
      'Click a slot in the open container, exactly as a mouse click would: the client builds and sends the real click packet and updates its own state. button 0 = left, 1 = right.',
    inputSchema: {
      type: 'object',
      properties: {
        slot: { type: 'number' },
        button: { type: 'number' },
        mode: {
          type: 'string',
          description: 'PICKUP (default), QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL',
        },
      },
      required: ['slot'],
    },
    run: (a) => clickSlot(a.slot, a.button ?? 0, a.mode ?? 'PICKUP'),
  },
  {
    name: 'mc_events',
    description: 'Events since a sequence number: watched packets (already decoded), tap installs, connection changes.',
    inputSchema: {
      type: 'object',
      properties: {
        since: { type: 'number' },
        waitMs: { type: 'number', description: 'block up to this long for something new' },
      },
    },
    run: (a) => agent('GET', `/events?since=${a.since ?? 0}&wait=${Math.min(a.waitMs ?? 0, 30000)}`),
  },
];

// ---------------------------------------------------------------- composed helpers

async function playerState() {
  const out = {};

  // Keep failures short. A missing field can otherwise return hundreds of suggested names, and this
  // is a survey tool: an unavailable value should cost one line, not a screenful.
  const brief = (e) => `<unavailable: ${String(e.message).split('(')[0].trim()}>`;
  const field = async (p, name) => {
    try {
      return (await agent('POST', '/get', { target: target(p), name, depth: 0 })).summary;
    } catch (e) {
      return brief(e);
    }
  };
  const method = async (p, name, args = []) => {
    try {
      return (await call(p, name, args, { inspect: true, depth: 0 })).summary;
    } catch (e) {
      return brief(e);
    }
  };

  const status = await agent('GET', '/status');
  const chan = (status.channels || []).find((c) => c.isGameConnection && c.active);
  out.server = chan ? chan.remote : null;
  out.tapped = (status.tapped || []).length > 0;
  out.connected = Boolean(chan);

  if (!out.connected) {
    out.note = 'Not connected to a server right now — the player is on a menu or between servers.';
    return out;
  }

  // Health and the display name are derived, not stored: read them the way the game does.
  out.name = await componentText(`${MC}.player.getName()`).catch(() => null);
  out.position = await field(`${MC}.player`, 'position');
  out.health = await method(`${MC}.player`, 'getHealth');
  out.maxHealth = await method(`${MC}.player`, 'getMaxHealth');
  out.food = await field(`${MC}.player.foodData`, 'foodLevel');
  // The accessor keeps getting renamed (location() → identifier()), but toString on the key itself
  // has been stable and already prints the dimension id.
  out.dimension = await method(`${MC}.level.dimension()`, 'toString');
  out.gameMode = await field(`${MC}.gameMode`, 'localPlayerMode');
  try {
    const menu = await agent('POST', '/get', { target: target(`${MC}.player`), name: 'containerMenu', depth: 0 });
    out.openMenu = { class: menu.class, handle: menu.handle, summary: menu.summary };
  } catch {
    out.openMenu = null;
  }
  return out;
}

async function chatRecent(a) {
  const limit = a.limit ?? 15;
  const list = await agent('GET', `/packets?limit=${limit * 3}&dir=in&type=Chat&since=0`);
  const messages = [];
  for (const p of list.packets) {
    if (messages.length >= limit) break;
    if (!p.retained) continue;
    for (const field of ['content', 'unsignedContent', 'body']) {
      try {
        messages.push({ seq: p.seq, text: await componentText({ field: { target: { seq: p.seq }, name: field } }) });
        break;
      } catch { /* try the next field name */ }
    }
  }
  return { messages, note: messages.length ? undefined : 'Nothing decoded — run mc_configure with watch:["SystemChat","PlayerChat"] and mute the movement packets, then try again.' };
}

async function openMenu() {
  const menu = await agent('POST', '/get', { target: target(`${MC}.player`), name: 'containerMenu', depth: 0 });
  if (!menu.handle) throw new Error('no container menu is open');
  const containerId = (await agent('POST', '/get', { target: { h: menu.handle }, name: 'containerId', depth: 0 })).summary;

  // Ask the list for its size — NonNullList keeps no size *field*, so reading the field and parsing
  // its summary yields nothing.
  const slotCount = Number(
    (await call(`${menu.handle}.slots`, 'size', [], { inspect: true, depth: 0 })).summary
  );
  if (!slotCount) throw new Error('the open menu reported zero slots');

  // Ask the game for each slot's display name rather than scraping strings out of the item's NBT.
  // Most menu buttons carry a custom_name component that a heap search would find, but plain items
  // (a compass, a sword) get their name from a translation key with no string anywhere in the stack
  // — getHoverName resolves both. One batch keeps it to a single round-trip.
  const ops = [];
  for (let i = 0; i < slotCount; i++) {
    ops.push({
      op: 'call',
      method: 'getString',
      inspect: false,
      target: {
        call: {
          target: { call: { target: { path: `${menu.handle}.slots[${i}]` }, method: 'getItem' } },
          method: 'getHoverName',
        },
      },
    });
  }
  const counts = [];
  for (let i = 0; i < slotCount; i++) {
    counts.push({
      op: 'get',
      name: 'count',
      inspect: false,
      target: { call: { target: { path: `${menu.handle}.slots[${i}]` }, method: 'getItem' } },
    });
  }
  const { results } = await agent('POST', '/batch', { ops: [...ops, ...counts] });

  const items = [];
  for (let i = 0; i < slotCount; i++) {
    const name = results[i];
    const count = Number(results[slotCount + i]?.summary ?? 0);
    if (!name || name.error || count <= 0) continue; // empty slots have count 0 and read as "Air"
    items.push({ slot: i, name: name.summary, count });
  }
  return { menu: menu.class, handle: menu.handle, containerId: Number(containerId), slotCount, items };
}

async function clickSlot(slot, button, mode) {
  const menu = await agent('POST', '/get', { target: target(`${MC}.player`), name: 'containerMenu', depth: 0 });
  const containerId = Number(
    (await agent('POST', '/get', { target: { h: menu.handle }, name: 'containerId', depth: 0 })).summary
  );

  // The click enum and the gameMode method were both renamed in recent versions; try the current
  // names first and fall back, so this keeps working across a game update.
  const variants = [
    { method: 'handleContainerInput', enumClass: 'net.minecraft.world.inventory.ContainerInput' },
    { method: 'handleInventoryMouseClick', enumClass: 'net.minecraft.world.inventory.ClickType' },
  ];
  let lastError;
  for (const v of variants) {
    try {
      await agent('POST', '/call', {
        target: target(`${MC}.gameMode`),
        method: v.method,
        args: [
          containerId,
          slot,
          button,
          { enum: { class: v.enumClass, name: mode } },
          { path: `${MC}.player` },
        ],
        onGameThread: target(MC),
        inspect: false,
      });
      return { clicked: slot, containerId, button, mode, via: v.method };
    } catch (e) {
      lastError = e;
    }
  }
  throw lastError;
}

// ---------------------------------------------------------------- MCP plumbing

const byName = new Map(tools.map((t) => [t.name, t]));

function respond(id, result) {
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id, result }) + '\n');
}

function fail(id, code, message) {
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } }) + '\n');
}

async function handle(msg) {
  const { id, method, params } = msg;
  if (method === 'initialize') {
    return respond(id, {
      protocolVersion: params?.protocolVersion || '2024-11-05',
      capabilities: { tools: {} },
      serverInfo: { name: 'mcinject', version: '0.1.0' },
      instructions:
        'Live control of a running Minecraft client through an injected agent. Call mc_status first to confirm the tap is installed, then mc_player_state. When a class or field name is unfamiliar, use mc_search to find a value you recognise and work outward from the handle it returns.',
    });
  }
  if (method === 'notifications/initialized' || method?.startsWith('notifications/')) return;
  if (method === 'ping') return respond(id, {});
  if (method === 'tools/list') {
    return respond(id, {
      tools: tools.map((t) => ({ name: t.name, description: t.description, inputSchema: t.inputSchema })),
    });
  }
  if (method === 'tools/call') {
    const tool = byName.get(params?.name);
    if (!tool) return fail(id, -32602, `unknown tool: ${params?.name}`);
    try {
      const result = await tool.run(params.arguments || {});
      return respond(id, { content: [{ type: 'text', text: JSON.stringify(result, null, 2) }] });
    } catch (e) {
      return respond(id, { content: [{ type: 'text', text: `Error: ${e.message}` }], isError: true });
    }
  }
  if (id !== undefined) fail(id, -32601, `method not found: ${method}`);
}

let buffer = '';
let pending = 0;
let stdinClosed = false;

/** Exit only once every in-flight tool call has answered, or a piped test run truncates its output. */
function maybeExit() {
  if (stdinClosed && pending === 0) process.exit(0);
}

process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  buffer += chunk;
  let nl;
  while ((nl = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, nl).trim();
    buffer = buffer.slice(nl + 1);
    if (!line) continue;
    let msg;
    try {
      msg = JSON.parse(line);
    } catch {
      continue;
    }
    pending++;
    handle(msg)
      .catch((e) => {
        if (msg.id !== undefined) fail(msg.id, -32603, e.message);
      })
      .finally(() => {
        pending--;
        maybeExit();
      });
  }
});
process.stdin.on('end', () => {
  stdinClosed = true;
  maybeExit();
});
