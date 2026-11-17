// delete-account: called by the signed-in user. Leaves every group using the normal
// leave rules (cancellations, unanimity recompute, admin handover), deletes their data,
// then deletes the auth user. Needs the service role, which never leaves the server.
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { json, serviceClient } from "../_shared/auth.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  const jwt = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!jwt) return json({ error: "unauthorized" }, 401);

  const db = serviceClient();
  const { data: userData, error: userError } = await db.auth.getUser(jwt);
  if (userError || !userData?.user) return json({ error: "unauthorized" }, 401);
  const uid = userData.user.id;

  const { error: leaveError } = await db.rpc("_delete_account", { p_user_id: uid });
  if (leaveError) return json({ error: "delete_failed" }, 500);

  const { error: authError } = await db.auth.admin.deleteUser(uid);
  if (authError) return json({ error: "auth_delete_failed" }, 500);
  return json({ deleted: true });
});
