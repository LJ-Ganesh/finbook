// purge-images: deletes images from Storage once they are no longer allowed to exist
// (immediately on REJECTED/EXPIRED/CANCELLED, 1h after ENDED). Called every 5 minutes
// by pg_cron. Votes and delivery rows are removed by the _cleanup_old_rows() job.
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { hasCronSecret, json, serviceClient } from "../_shared/auth.ts";

const BUCKET = "proposal-images";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  if (!hasCronSecret(req)) return json({ error: "forbidden" }, 403);

  const db = serviceClient();
  const { data: paths, error } = await db.rpc("_purge_candidates", { p_limit: 100 });
  if (error) return json({ error: "candidates_failed" }, 500);
  const list = (paths ?? []) as string[];
  if (list.length === 0) return json({ purged: 0 });

  // remove() succeeds for paths that are already gone, so retries are safe.
  const { error: removeError } = await db.storage.from(BUCKET).remove(list);
  if (removeError) return json({ error: "remove_failed" }, 502);

  const { error: markError } = await db.rpc("_mark_purged", { p_paths: list });
  if (markError) return json({ error: "mark_failed" }, 500);
  return json({ purged: list.length });
});
