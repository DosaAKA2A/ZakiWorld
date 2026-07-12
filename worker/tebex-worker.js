/**
 * ZakiWorld Tebex Worker — Cloudflare Workers (free tier)
 * 
 * Este worker consulta la API de Tebex cada 5 minutos (cacheado)
 * y sirve las últimas donaciones + top donador como JSON público.
 * La Private Key NUNCA se expone al frontend.
 * 
 * DEPLOY:
 * 1. Ve a Cloudflare Dashboard → Workers & Pages → Create
 * 2. Crea un worker, pega este código
 * 3. Settings → Variables → añade: TEBEX_SECRET = hrvNL35bPtt7tJuQPbwVA7IkYJdKQPps
 * 4. El worker quedará en: https://tebex-api.TU_SUBDOMINIO.workers.dev
 *    (o puedes asignarlo a api.zakiworld.net con un Custom Domain)
 */

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
  "Content-Type": "application/json",
  "Cache-Control": "public, max-age=300", // cache 5 minutos
};

export default {
  async fetch(request, env) {
    // Handle CORS preflight
    if (request.method === "OPTIONS") {
      return new Response(null, { headers: CORS_HEADERS });
    }

    const url = new URL(request.url);

    try {
      if (url.pathname === "/donations" || url.pathname === "/") {
        return await getDonations(env);
      }
      if (url.pathname === "/info") {
        return await getStoreInfo(env);
      }
      return new Response(JSON.stringify({ error: "Not found" }), {
        status: 404,
        headers: CORS_HEADERS,
      });
    } catch (err) {
      return new Response(JSON.stringify({ error: "Internal error" }), {
        status: 500,
        headers: CORS_HEADERS,
      });
    }
  },
};

async function getDonations(env) {
  const secret = env.TEBEX_SECRET;
  if (!secret) {
    return new Response(JSON.stringify({ error: "TEBEX_SECRET not configured" }), {
      status: 500, headers: CORS_HEADERS,
    });
  }

  // Fetch recent payments from Tebex Plugin API
  const res = await fetch("https://plugin.tebex.io/payments?paged=1&page=1", {
    headers: { "X-Tebex-Secret": secret },
  });

  if (!res.ok) {
    return new Response(JSON.stringify({ error: "Tebex API error", status: res.status }), {
      status: 502, headers: CORS_HEADERS,
    });
  }

  const data = await res.json();
  const payments = data.data || data || [];

  // Process payments: extract relevant fields, filter completed only
  const donations = [];
  const totals = {}; // track totals per player for top donor

  for (const p of payments) {
    // Only include completed payments
    if (p.status && p.status !== "Complete") continue;

    const name = p.player?.name || p.player?.username || "Anónimo";
    const amount = parseFloat(p.amount) || 0;
    const currency = p.currency?.iso_4217 || "EUR";
    const symbol = p.currency?.symbol || "€";
    const packages = (p.packages || []).map(pkg => pkg.name).join(", ") || "Paquete";
    const date = p.date || p.created_at || "";

    donations.push({
      name,
      pkg: packages,
      amt: `${amount.toFixed(2)}${symbol}`,
      date,
    });

    // Accumulate totals for top donor calculation
    if (!totals[name]) totals[name] = { name, total: 0, count: 0 };
    totals[name].total += amount;
    totals[name].count++;
  }

  // Determine top donor
  let topDonor = null;
  let maxTotal = 0;
  for (const [name, info] of Object.entries(totals)) {
    if (info.total > maxTotal) {
      maxTotal = info.total;
      topDonor = {
        name: info.name,
        total: info.total.toFixed(2),
        count: info.count,
      };
    }
  }

  const result = {
    donations: donations.slice(0, 20), // últimas 20
    topDonor,
    totalDonations: donations.length,
    totalDonors: Object.keys(totals).length,
    updatedAt: new Date().toISOString(),
  };

  return new Response(JSON.stringify(result), { headers: CORS_HEADERS });
}

async function getStoreInfo(env) {
  const secret = env.TEBEX_SECRET;
  if (!secret) {
    return new Response(JSON.stringify({ error: "TEBEX_SECRET not configured" }), {
      status: 500, headers: CORS_HEADERS,
    });
  }

  const res = await fetch("https://plugin.tebex.io/information", {
    headers: { "X-Tebex-Secret": secret },
  });

  if (!res.ok) {
    return new Response(JSON.stringify({ error: "Tebex API error" }), {
      status: 502, headers: CORS_HEADERS,
    });
  }

  const data = await res.json();
  return new Response(JSON.stringify(data), { headers: CORS_HEADERS });
}
