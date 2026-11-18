import { useState } from "react";
import * as api from "../lib/api";
import { supabase } from "../lib/supabase";
import { stopRealtime, useAction, useLoad } from "../lib/hooks";
import { Banner, Card, Confirm, ErrorState, InputDialog, Spinner } from "../components/ui";

const E164 = /^\+[1-9][0-9]{6,14}$/;

export default function Settings() {
  const act = useAction();
  const [edit, setEdit] = useState<"name" | "phone" | null>(null);
  const [confirmOut, setConfirmOut] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const d = useLoad(api.me, []);
  if (d.loading && !d.data) return <Spinner />;
  if (d.error) return <ErrorState error={d.error} onRetry={d.reload} />;
  const me = d.data!;
  const signOut = async () => { stopRealtime(); await supabase.auth.signOut(); };
  return (
    <div className="stack" style={{ maxWidth: 600 }}>
      <h1>Settings</h1>
      {act.message && <Banner error>{act.message}</Banner>}
      <h2>Account</h2>
      <Card outlined><div className="list">
        <div className="kv"><span>Google account</span><span>{me.email}</span></div>
        <div className="kv" style={{ cursor: "pointer" }} onClick={() => setEdit("name")}><span>Name</span><span>{me.name || "—"} ›</span></div>
        <div className="kv" style={{ cursor: "pointer" }} onClick={() => setEdit("phone")}><span>Phone (optional)</span><span>{me.phone_optional || "Not set · visible only to your groups"} ›</span></div>
      </div></Card>
      <h2>On your phone</h2>
      <Card outlined><p className="small">Your home wallpaper, Setup health, notification preferences and “Skip on my phone” live in the Livrun Android app, because that's where wallpapers change.</p></Card>
      <p className="muted small">To leave a group, open the group and go to its settings.</p>
      <div className="row"><button className="btn ghost" onClick={() => setConfirmOut(true)}>Sign out</button>
        <button className="btn text danger" onClick={() => setConfirmDelete(true)}>Delete account</button></div>
      <InputDialog open={edit === "name"} title="Display name" label="Name" initial={me.name} onClose={() => setEdit(null)}
        onSave={(v) => act.run(async () => { if (!v) throw new Error(); await api.updateProfile({ name: v }); }, d.reload)} />
      <InputDialog open={edit === "phone"} title="Phone number" label="With country code, e.g. +91 98123 45678" initial={me.phone_optional ?? ""} onClose={() => setEdit(null)}
        onSave={(v) => { const clean = v.replace(/[^+\d]/g, ""); if (clean && !E164.test(clean)) { act.setMessage("Enter the number with country code, e.g. +91 98123 45678."); return; }
          act.run(() => api.updateProfile({ phone_optional: clean || null }), d.reload); }} />
      <Confirm open={confirmOut} onClose={() => setConfirmOut(false)} title="Sign out?" body="You'll need to sign in with Google again on this computer." confirm="Sign out" destructive={false} onConfirm={signOut} />
      <Confirm open={confirmDelete} onClose={() => setConfirmDelete(false)} title="Delete your account?" confirm="Delete account"
        body="You leave every group (your proposals and schedules are cancelled for everyone; if you're an admin, it passes on), and your data is deleted. This can't be undone."
        onConfirm={() => act.run(async () => { await api.deleteAccount(); await signOut(); })} />
    </div>
  );
}
