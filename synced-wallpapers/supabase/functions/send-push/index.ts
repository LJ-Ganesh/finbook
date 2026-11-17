// send-push: delivers queued notification_outbox rows as FCM data messages.
// Called by a database trigger (right after an RPC queued rows) and every minute by
// pg_cron as a retry. Protected by the x-cron-secret header (verify_jwt is off).
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { hasCronSecret, json, serviceClient } from "../_shared/auth.ts";
import { sendData, serviceAccount } from "../_shared/fcm.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  if (!hasCronSecret(req)) return json({ error: "forbidden" }, 403);

  const db = serviceClient();
  const sa = serviceAccount();
  const { data: rows, error } = await db.rpc("_claim_outbox", { p_limit: 200 });
  if (error) return json({ error: "claim_failed" }, 500);

  const sent: number[] = [];
  const failed: number[] = [];
  const deadTokens = new Set<string>();

  for (const row of rows ?? []) {
    const tokens: string[] = row.tokens ?? [];
    if (tokens.length === 0) { sent.push(row.id); continue; } // nobody to push to; Activity still shows it
    const data = {
      outbox_id: String(row.id),
      type: String(row.type),
      payload: JSON.stringify(row.payload ?? {}),
      created_at: String(row.created_at),
    };
    let anyOk = false;
    let anyRetry = false;
    for (const token of tokens) {
      const result = await sendData(sa, token, data).catch(() => "retry" as const);
      if (result === "ok") anyOk = true;
      else if (result === "dead_token") deadTokens.add(token);
      else anyRetry = true;
    }
    if (anyOk || !anyRetry) sent.push(row.id); else failed.push(row.id);
  }

  if (deadTokens.size > 0) await db.from("device_tokens").delete().in("fcm_token", [...deadTokens]);
  if (sent.length || failed.length) {
    await db.rpc("_finish_outbox", { p_sent: sent, p_failed: failed, p_error: failed.length ? "fcm_retry" : null });
  }
  return json({ sent: sent.length, failed: failed.length, dead_tokens: deadTokens.size });
});
