/**
 * ZakiWorld Worker — Cloudflare Workers (free tier)
 *
 * Endpoints:
 *   GET  /donations          → últimas donaciones + top donador (público, cache 5min)
 *   GET  /info               → info de la tienda Tebex
 *   POST /tickets            → guarda un mensaje de contacto/reclamo en KV
 *   GET  /tickets            → lista los mensajes (protegido con ADMIN_KEY)
 *   POST /tickets/update     → marca un ticket como leído/resuelto (protegido)
 *   GET  /purchases?nick=X   → resumen de compras de un usuario (protegido)
 *
 * REQUIERE:
 *   - Variable secreta:  TEBEX_SECRET = <Private Key de Tebex>
 *   - Variable secreta:  ADMIN_KEY = <contraseña larga para el backoffice>
 *   - KV Namespace enlazado como:  TICKETS
 *
 * CÓMO CREAR EL KV (en el panel de Cloudflare):
 *   1. Workers & Pages → KV → Create namespace → nombre: "zaki-tickets"
 *   2. Abre tu Worker → Settings → Variables → KV Namespace Bindings
 *      → Add binding:  Variable name = TICKETS  |  KV namespace = zaki-tickets
 *   3. Settings → Variables → Add:  ADMIN_KEY = (una contraseña larga que inventes)
 */

const ALLOWED_ORIGIN = "*"; // puedes restringir a "https://zakiworld.net"

function cors(extra = {}) {
  return {
    "Access-Control-Allow-Origin": ALLOWED_ORIGIN,
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, X-Admin-Key",
    "Content-Type": "application/json",
    ...extra,
  };
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { headers: cors() });
    }

    const url = new URL(request.url);
    const path = url.pathname;

    try {
      // ── Público ──
      if (path === "/donations" || path === "/") return await getDonations(env);
      if (path === "/info") return await getStoreInfo(env);

      // ── Tickets ──
      if (path === "/tickets" && request.method === "POST") return await createTicket(request, env);
      if (path === "/tickets" && request.method === "GET") return await listTickets(request, env);
      if (path === "/tickets/update" && request.method === "POST") return await updateTicket(request, env);

      // ── Compras de un usuario ──
      if (path === "/purchases") return await getPurchases(request, env, url);

      return new Response(JSON.stringify({ error: "Not found" }), { status: 404, headers: cors() });
    } catch (err) {
      return new Response(JSON.stringify({ error: "Internal error", detail: String(err) }), {
        status: 500, headers: cors(),
      });
    }
  },
};

/* ═══════════════════════════ TICKETS ═══════════════════════════ */

async function createTicket(request, env) {
  if (!env.TICKETS) {
    return new Response(JSON.stringify({ error: "KV no configurado" }), { status: 500, headers: cors() });
  }
  let body;
  try { body = await request.json(); } catch { 
    return new Response(JSON.stringify({ error: "JSON inválido" }), { status: 400, headers: cors() }); 
  }

  // Validación server-side
  const nick = String(body.nick || "").trim();
  const reason = String(body.reason || "").trim();
  const message = String(body.message || "").trim();
  const validReasons = ["pago", "desbaneo", "error", "reporte", "cuenta", "otro"];

  if (!/^[A-Za-z0-9_]{3,16}$/.test(nick)) return bad("Usuario inválido");
  if (!validReasons.includes(reason)) return bad("Motivo inválido");
  if (message.length < 10 || message.length > 1200) return bad("Mensaje fuera de rango");

  // Anti-spam simple: máx 5 tickets por nick por hora
  const rateKey = `rate:${nick}`;
  const rate = parseInt(await env.TICKETS.get(rateKey) || "0", 10);
  if (rate >= 5) return bad("Has enviado demasiados mensajes. Inténtalo más tarde.", 429);

  const id = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const ticket = {
    id, nick, reason,
    reasonLabel: body.reasonLabel || reason,
    message,
    ts: Date.now(),
    status: "nuevo", // nuevo | leido | resuelto
    ua: String(body.ua || "").slice(0, 160),
  };

  // Guardar el ticket (clave ordenable por fecha desc gracias al timestamp invertido)
  const sortKey = `ticket:${9999999999999 - Date.now()}:${id}`;
  await env.TICKETS.put(sortKey, JSON.stringify(ticket));
  await env.TICKETS.put(rateKey, String(rate + 1), { expirationTtl: 3600 });

  return new Response(JSON.stringify({ ok: true, id }), { headers: cors() });

  function bad(msg, status = 400) {
    return new Response(JSON.stringify({ error: msg }), { status, headers: cors() });
  }
}

async function listTickets(request, env) {
  const auth = checkAdmin(request, env);
  if (auth) return auth;

  const list = await env.TICKETS.list({ prefix: "ticket:" });
  const tickets = [];
  for (const key of list.keys) {
    const raw = await env.TICKETS.get(key.name);
    if (raw) tickets.push(JSON.parse(raw));
  }
  // Ya vienen ordenados por la sortKey (más reciente primero)
  return new Response(JSON.stringify({ tickets, count: tickets.length }), { headers: cors() });
}

async function updateTicket(request, env) {
  const auth = checkAdmin(request, env);
  if (auth) return auth;

  const body = await request.json();
  const { id, status } = body;
  if (!id || !["nuevo", "leido", "resuelto"].includes(status)) {
    return new Response(JSON.stringify({ error: "Parámetros inválidos" }), { status: 400, headers: cors() });
  }

  // Buscar la clave que contiene ese id
  const list = await env.TICKETS.list({ prefix: "ticket:" });
  for (const key of list.keys) {
    if (key.name.endsWith(id)) {
      const raw = await env.TICKETS.get(key.name);
      if (raw) {
        const t = JSON.parse(raw);
        t.status = status;
        await env.TICKETS.put(key.name, JSON.stringify(t));
        return new Response(JSON.stringify({ ok: true }), { headers: cors() });
      }
    }
  }
  return new Response(JSON.stringify({ error: "Ticket no encontrado" }), { status: 404, headers: cors() });
}

function checkAdmin(request, env) {
  const key = request.headers.get("X-Admin-Key");
  if (!env.ADMIN_KEY) {
    return new Response(JSON.stringify({ error: "ADMIN_KEY no configurado en el Worker" }), { status: 500, headers: cors() });
  }
  if (key !== env.ADMIN_KEY) {
    return new Response(JSON.stringify({ error: "No autorizado" }), { status: 401, headers: cors() });
  }
  return null; // ok
}

/* ═══════════════════════════ COMPRAS ═══════════════════════════ */

async function getPurchases(request, env, url) {
  const auth = checkAdmin(request, env);
  if (auth) return auth;

  const nick = (url.searchParams.get("nick") || "").trim();
  if (!/^[A-Za-z0-9_]{3,16}$/.test(nick)) {
    return new Response(JSON.stringify({ error: "Nick inválido" }), { status: 400, headers: cors() });
  }
  if (!env.TEBEX_SECRET) {
    return new Response(JSON.stringify({ error: "TEBEX_SECRET no configurado" }), { status: 500, headers: cors() });
  }

  // Tebex: pagos del usuario. La Plugin API permite filtrar por jugador.
  const res = await fetch(`https://plugin.tebex.io/user/${encodeURIComponent(nick)}`, {
    headers: { "X-Tebex-Secret": env.TEBEX_SECRET },
  });

  if (!res.ok) {
    // Puede que el usuario no exista o la tienda aún no esté aprobada
    return new Response(JSON.stringify({ nick, purchases: [], total: 0, count: 0, note: "Sin datos de Tebex todavía" }), { headers: cors() });
  }

  const data = await res.json();
  const payments = data.payments || data.data || [];
  let total = 0;
  const purchases = payments.map(p => {
    const amt = parseFloat(p.amount) || 0;
    total += amt;
    return {
      amount: amt.toFixed(2),
      currency: p.currency || "EUR",
      packages: (p.packages || []).map(x => x.name).join(", "),
      date: p.date || p.created_at || "",
      status: p.status || "",
    };
  });

  return new Response(JSON.stringify({
    nick, purchases, total: total.toFixed(2), count: purchases.length,
  }), { headers: cors() });
}

/* ═══════════════════════════ DONACIONES ═══════════════════════════ */

async function getDonations(env) {
  const secret = env.TEBEX_SECRET;
  if (!secret) {
    return new Response(JSON.stringify({ error: "TEBEX_SECRET not configured" }), { status: 500, headers: cors() });
  }
  const res = await fetch("https://plugin.tebex.io/payments?paged=1&page=1", {
    headers: { "X-Tebex-Secret": secret },
  });
  if (!res.ok) {
    return new Response(JSON.stringify({ error: "Tebex API error", status: res.status }), { status: 502, headers: cors() });
  }
  const data = await res.json();
  const payments = data.data || data || [];
  const donations = [];
  const totals = {};

  for (const p of payments) {
    if (p.status && p.status !== "Complete") continue;
    const name = p.player?.name || p.player?.username || "Anónimo";
    const amount = parseFloat(p.amount) || 0;
    const symbol = p.currency?.symbol || "€";
    const packages = (p.packages || []).map(pkg => pkg.name).join(", ") || "Paquete";
    const date = p.date || p.created_at || "";
    donations.push({ name, pkg: packages, amt: `${amount.toFixed(2)}${symbol}`, date });
    if (!totals[name]) totals[name] = { name, total: 0, count: 0 };
    totals[name].total += amount;
    totals[name].count++;
  }

  let topDonor = null, maxTotal = 0;
  for (const info of Object.values(totals)) {
    if (info.total > maxTotal) {
      maxTotal = info.total;
      topDonor = { name: info.name, total: info.total.toFixed(2), count: info.count };
    }
  }

  // Ranking top 10 por total donado
  const ranking = Object.values(totals)
    .sort((a, b) => b.total - a.total)
    .slice(0, 10)
    .map(info => ({ name: info.name, total: Number(info.total.toFixed(2)) }));

  return new Response(JSON.stringify({
    donations: donations.slice(0, 20),
    topDonor,
    ranking,
    totalDonations: donations.length,
    totalDonors: Object.keys(totals).length,
    updatedAt: new Date().toISOString(),
  }), { headers: cors({ "Cache-Control": "public, max-age=300" }) });
}

async function getStoreInfo(env) {
  const secret = env.TEBEX_SECRET;
  if (!secret) return new Response(JSON.stringify({ error: "TEBEX_SECRET not configured" }), { status: 500, headers: cors() });
  const res = await fetch("https://plugin.tebex.io/information", { headers: { "X-Tebex-Secret": secret } });
  if (!res.ok) return new Response(JSON.stringify({ error: "Tebex API error" }), { status: 502, headers: cors() });
  const data = await res.json();
  return new Response(JSON.stringify(data), { headers: cors({ "Cache-Control": "public, max-age=300" }) });
}
