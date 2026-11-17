// Shared helpers for the edge functions. Secrets come only from function secrets
// (`supabase secrets set ...`); nothing secret is ever logged.
import { createClient, type SupabaseClient } from "jsr:@supabase/supabase-js@2";

export function serviceClient(): SupabaseClient {
  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) throw new Error("SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY missing");
  return createClient(url, key, { auth: { persistSession: false, autoRefreshToken: false } });
}

/** Constant-time comparison of the x-cron-secret header with CRON_SECRET. */
export function hasCronSecret(req: Request): boolean {
  const expected = Deno.env.get("CRON_SECRET") ?? "";
  const given = req.headers.get("x-cron-secret") ?? "";
  if (expected.length < 16 || given.length !== expected.length) return false;
  let diff = 0;
  for (let i = 0; i < expected.length; i++) diff |= expected.charCodeAt(i) ^ given.charCodeAt(i);
  return diff === 0;
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
