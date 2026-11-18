// Visual smoke test: serves the built app, answers Supabase requests with real response
// fixtures (generated from the migrations by scripts/make-fixtures.sql), visits every
// screen, saves screenshots and fails on console errors.
// Usage: npm run build && node scripts/ui-smoke.mjs <out-dir>
import { createRequire } from "node:module";
const { chromium } = createRequire(import.meta.url)("playwright"); // Playwright is pre-installed globally here; locally: npm i -D playwright
import { readFileSync, mkdirSync } from "node:fs";
import { spawn } from "node:child_process";
import path from "node:path";

const out = process.argv[2] || "smoke-out";
mkdirSync(out, { recursive: true });
const fx = (n) => JSON.parse(readFileSync(path.resolve("../app/src/test/resources/fixtures", n + ".json"), "utf8"));
const env = Object.fromEntries(readFileSync(".env.local", "utf8").trim().split("\n").map((l) => l.split(/=(.*)/s).slice(0, 2)));
const base = env.VITE_SUPABASE_URL, ref = new URL(base).hostname.split(".")[0];

const profiles = fx("profiles"), me = profiles.find((p) => p.name === "Asha");
const groups = fx("groups"), gid = groups[0].id;
const proposals = fx("proposals");
const approved = proposals.find((p) => p.status === "APPROVED");
const pending = proposals.find((p) => p.status === "PENDING" && p.image_ready);
const action = fx("admin_actions")[0];
const rpc = {
  my_pending_counts: [{ group_id: gid, proposals: 1, admin_actions: 0, join_requests: 1 }],
  my_join_requests: [], my_upcoming_commitments: fx("my_upcoming_commitments"), group_join_requests: fx("group_join_requests"),
  proposal_vote_summary: fx("proposal_vote_summary"), admin_action_summary: { ...fx("admin_action_summary"), can_vote: true },
  delivery_summary: fx("delivery_summary"), group_taken_slots: fx("group_taken_slots"), proposal_conflicts: [],
  join_request_preview: fx("join_request_preview"),
};
const tables = { profiles, groups, group_members: fx("group_members"), proposals, admin_actions: fx("admin_actions"),
  notification_outbox: fx("notification_outbox").filter((o) => o.user_id === me.id), time_suggestions: fx("time_suggestions") };

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
const exp = Math.floor(Date.now() / 1000) + 3600;
const jwt = `${b64({ alg: "HS256", typ: "JWT" })}.${b64({ sub: me.id, exp, role: "authenticated", email: me.email })}.sig`;
const session = { access_token: jwt, refresh_token: "r", token_type: "bearer", expires_in: 3600, expires_at: exp,
  user: { id: me.id, email: me.email, aud: "authenticated", role: "authenticated", app_metadata: {}, user_metadata: {}, created_at: new Date().toISOString() } };

const server = spawn("npx", ["vite", "preview", "--port", "4173", "--strictPort"], { stdio: "ignore" });
await new Promise((r) => setTimeout(r, 2500));
const browser = await chromium.launch();
const errors = [];

async function page(signedIn, dark = false) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: dark ? "dark" : "light" });
  if (signedIn) await ctx.addInitScript(([k, v]) => localStorage.setItem(k, v), [`sb-${ref}-auth-token`, JSON.stringify(session)]);
  // A sample "photo" for the signed image URL.
  const photo = await (async () => { const p = await ctx.newPage(); await p.setViewportSize({ width: 1600, height: 1000 });
    await p.setContent(`<body style="margin:0;width:1600px;height:1000px;background:linear-gradient(160deg,#ffb36b,#e76f51 45%,#264653 46%,#2a9d8f)"><div style="position:absolute;left:1050px;top:230px;width:180px;height:180px;border-radius:50%;background:#ffe8a3"></div></body>`);
    const b = await p.screenshot({ type: "jpeg" }); await p.close(); return b; })();
  await ctx.route(`${base}/**`, async (route) => {
    const req = route.request(), url = new URL(req.url()), single = (req.headers()["accept"] || "").includes("pgrst.object");
    const json = (body, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    if (url.pathname.startsWith("/rest/v1/rpc/")) { const fn = url.pathname.split("/").pop(); return json(rpc[fn] ?? null); }
    if (url.pathname.startsWith("/rest/v1/")) {
      const t = url.pathname.split("/").pop(); let rows = tables[t] ?? [];
      for (const [k, v] of url.searchParams) if (v.startsWith("eq.")) rows = rows.filter((r) => String(r[k]) === v.slice(3));
      if (req.method() !== "GET") return json([]);
      return json(single ? rows[0] ?? null : rows);
    }
    if (url.pathname.startsWith("/storage/v1/object/sign/") && req.method() === "POST") return json({ signedURL: url.pathname.replace("/storage/v1", "") + "?token=x" });
    if (url.pathname.startsWith("/storage/v1/object/sign/")) return route.fulfill({ status: 200, contentType: "image/jpeg", body: photo });
    if (url.pathname.startsWith("/auth/v1/user")) return json(session.user);
    return json({});
  });
  const p = await ctx.newPage();
  p.on("console", (m) => { if (m.type() === "error" && !/WebSocket|realtime|websocket|ERR_TUNNEL/i.test(m.text())) errors.push(`${p.url()}: ${m.text()}`); });
  p.on("pageerror", (e) => errors.push(`${p.url()}: ${e.message}`));
  return p;
}

const shots = [
  ["01-signin", false, "/"],
  ["02-home", true, "/"],
  ["03-group", true, `/groups/${gid}`],
  ["04-proposal-pending", true, `/proposals/${pending.id}`],
  ["05-proposal-approved", true, `/proposals/${approved.id}`],
  ["06-propose-time", true, `/groups/${gid}/propose?replaces=${pending.id}`],
  ["07-admin-action", true, `/admin-actions/${action.id}`],
  ["08-group-settings", true, `/groups/${gid}/settings`],
  ["09-activity", true, "/activity"],
  ["10-settings", true, "/settings"],
  ["11-new-group", true, "/groups/new"],
  ["12-group-dark", true, `/groups/${gid}`, true],
];
for (const [name, signedIn, route, dark] of shots) {
  const p = await page(signedIn, dark);
  await p.goto("http://localhost:4173" + route, { waitUntil: "networkidle" });
  await p.waitForTimeout(600);
  await p.screenshot({ path: `${out}/${name}.png`, fullPage: true });
  const text = await p.locator("body").innerText();
  if (/Couldn't load this|Something went wrong/.test(text)) errors.push(`${name}: error state shown`);
  await p.context().close();
}
// Mobile width check (no horizontal scroll).
{ const p = await page(true); await p.setViewportSize({ width: 390, height: 844 }); await p.goto(`http://localhost:4173/groups/${gid}`, { waitUntil: "networkidle" });
  await p.waitForTimeout(400); await p.screenshot({ path: `${out}/13-group-phone-width.png`, fullPage: true });
  const overflow = await p.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1); if (overflow) errors.push("horizontal scroll at 390px");
  await p.context().close(); }
await browser.close(); server.kill();
console.log(errors.length ? "PROBLEMS:\n" + errors.join("\n") : "OK: all screens rendered without errors");
process.exit(errors.length ? 1 : 0);
