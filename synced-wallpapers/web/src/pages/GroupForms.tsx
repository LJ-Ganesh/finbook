import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import * as api from "../lib/api";
import { useAction } from "../lib/hooks";
import { Banner, Card } from "../components/ui";
import { localZone, zones } from "../lib/time";

export function ZonePicker({ value, onChange }: { value: string; onChange: (z: string) => void }) {
  return (
    <label className="field">Time zone
      <input list="zones" value={value} onChange={(e) => onChange(e.target.value)} placeholder="e.g. Asia/Kolkata" />
      <datalist id="zones">{zones().map((z) => <option key={z} value={z} />)}</datalist>
    </label>
  );
}

export function CreateGroup() {
  const nav = useNavigate();
  const act = useAction();
  const [name, setName] = useState("");
  const [zone, setZone] = useState(localZone);
  return (
    <div className="stack" style={{ maxWidth: 520 }}>
      <Link to="/" className="muted small">← Home</Link>
      <h1>New group</h1>
      {act.message && <Banner error>{act.message}</Banner>}
      <label className="field">Group name<input value={name} maxLength={40} onChange={(e) => setName(e.target.value)} autoFocus /></label>
      <ZonePicker value={zone} onChange={setZone} />
      <p className="muted small">Everyone enters and sees times in this zone (plus their own local time if it differs). You'll be the admin, with one vote like everyone else.</p>
      <button className="btn big" disabled={!name.trim() || act.busy} onClick={() => act.run(async () => nav(`/groups/${await api.createGroup(name, zone)}`, { replace: true }))}>Create</button>
    </div>
  );
}

export function Invite() {
  const { id = "" } = useParams();
  const act = useAction();
  const [invite, setInvite] = useState<api.Invite | null>(null);
  const [email, setEmail] = useState("");
  const [note, setNote] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const webLink = invite ? `${window.location.origin}/join/${invite.code}` : "";
  return (
    <div className="stack" style={{ maxWidth: 560 }}>
      <Link to={`/groups/${id}/settings`} className="muted small">← Group settings</Link>
      <h1>Invite</h1>
      <p className="muted">Anyone who joins must accept the group's upcoming wallpapers, and every member must approve them.</p>
      {act.message && <Banner error>{act.message}</Banner>}
      {note && <Banner>{note}</Banner>}
      <Card outlined>
        <h3>Code or link</h3><p className="muted small">Single use, expires in 24 hours.</p>
        {invite && !invite.email && <>
          <div style={{ fontSize: 30, fontWeight: 700, letterSpacing: 2 }}>{invite.code.match(/.{1,4}/g)!.join("-")}</div>
          <div className="row wrap">
            <button className="btn ghost" onClick={() => { navigator.clipboard.writeText(webLink); setCopied(true); }}>{copied ? "Copied" : "Copy web link"}</button>
            <button className="btn ghost" onClick={() => navigator.clipboard.writeText(`livrun://join/${invite.code}`)}>Copy app link</button>
          </div>
        </>}
        <button className="btn" disabled={act.busy} onClick={() => act.run(async () => { setInvite(await api.createInvite(id)); setCopied(false); setNote(null); })}>{invite && !invite.email ? "Create another" : "Create code"}</button>
      </Card>
      <Card outlined>
        <h3>Google email</h3><p className="muted small">If they haven't signed up yet, the invite waits until they do.</p>
        <input type="email" placeholder="name@gmail.com" value={email} onChange={(e) => setEmail(e.target.value.trim())} />
        <button className="btn" disabled={!email.includes("@") || act.busy} onClick={() => act.run(async () => {
          const inv = await api.createInvite(id, email);
          setNote(inv.join_request_id ? `Invite sent to ${email}. They'll be asked to review the group's schedules.` : `${email} hasn't signed up yet. The invite will be waiting when they do.`);
          setEmail("");
        })}>Send invite</button>
      </Card>
    </div>
  );
}
