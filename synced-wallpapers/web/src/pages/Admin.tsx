import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import * as api from "../lib/api";
import { useAction, useLoad } from "../lib/hooks";
import { Banner, Card, Confirm, ErrorState, ReasonDialog, Ring, Spinner } from "../components/ui";
import { remaining, windowText } from "../lib/time";
import { ZonePicker } from "./GroupForms";
import { MAX_MEMBERS } from "../lib/supabase";

const s = (a: api.AdminAction, k: string) => (a.payload?.[k] as string | number | undefined)?.toString();

export function adminTitle(a: api.AdminAction) {
  switch (a.type) {
    case "RENAME": return `Rename: “${s(a, "previous")}” → “${s(a, "name")}”`;
    case "TIMEZONE": return `Time zone: ${s(a, "previous")} → ${s(a, "time_zone")}`;
    case "MEMBER_LIMIT": return `Member limit: ${s(a, "previous")} → ${s(a, "max_members")}`;
    case "REMOVE_MEMBER": return `Remove ${s(a, "member_name") ?? "a member"}`;
    case "TRANSFER_ADMIN": return `Hand over admin to ${s(a, "member_name") ?? "a member"}`;
    case "DELETE_GROUP": return "Delete the group";
  }
}

/** Upcoming schedules keep their absolute instants; only how they're shown changes. */
function TzPreview({ upcoming, from, to }: { upcoming: api.Proposal[]; from: string; to: string }) {
  return (
    <Card outlined>
      <p className="small">Upcoming schedules stay at the same moment. Only how times are shown changes:</p>
      {upcoming.length === 0 ? <p className="muted small">No upcoming schedules.</p> : upcoming.map((p) => (
        <p key={p.id} className="small">{windowText(p.start_at, p.end_at, from)}<br />→ {windowText(p.start_at, p.end_at, to)}</p>))}
    </Card>
  );
}

export function NewAdminAction() {
  const { id = "", type = "RENAME" } = useParams();
  const t = type as api.AdminType;
  const nav = useNavigate();
  const act = useAction();
  const [text, setText] = useState("");
  const [zone, setZone] = useState("");
  const [member, setMember] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const d = useLoad(async () => ({ detail: await api.groupDetail(id), upcoming: (await api.groupProposals(id)).filter((p) => ["APPROVED", "LIVE"].includes(p.status)) }), [id]);
  if (d.loading && !d.data) return <Spinner />;
  if (d.error) return <ErrorState error={d.error} />;
  const { detail, upcoming } = d.data!;
  const g = detail.group, others = detail.members.filter((m) => !m.isAdmin);
  const n = Number(text);
  const payload: Record<string, unknown> | null =
    t === "RENAME" ? (text.trim() ? { name: text.trim() } : null) :
    t === "TIMEZONE" ? (zone && zone !== g.time_zone ? { time_zone: zone } : null) :
    t === "MEMBER_LIMIT" ? (Number.isInteger(n) && n >= detail.members.length && n <= MAX_MEMBERS ? { max_members: n } : null) :
    t === "REMOVE_MEMBER" || t === "TRANSFER_ADMIN" ? (member ? { user_id: member } : null) : {};
  return (
    <div className="stack" style={{ maxWidth: 560 }}>
      <Link to={`/groups/${id}/settings`} className="muted small">← Group settings</Link>
      <h1>{api.ADMIN_LABELS[t]}</h1>
      {act.message && <Banner error>{act.message}</Banner>}
      <Banner>{others.length === 0 ? "You're the only member, so this applies straight away." :
        `This needs approval from all ${others.length} other member${others.length === 1 ? "" : "s"}${t === "REMOVE_MEMBER" ? " (except the person being removed, who isn't told until the outcome)" : ""}. It expires after 48 hours without everyone's approval.`}</Banner>
      {t === "RENAME" && <label className="field">New name (now “{g.name}”)<input value={text} maxLength={40} onChange={(e) => setText(e.target.value)} /></label>}
      {t === "TIMEZONE" && <><ZonePicker value={zone || g.time_zone} onChange={setZone} />{zone && zone !== g.time_zone && <TzPreview upcoming={upcoming} from={g.time_zone} to={zone} />}</>}
      {t === "MEMBER_LIMIT" && <label className="field">New limit ({detail.members.length}–{MAX_MEMBERS}, now {g.max_members})<input type="number" min={detail.members.length} max={MAX_MEMBERS} value={text} onChange={(e) => setText(e.target.value)} /></label>}
      {(t === "REMOVE_MEMBER" || t === "TRANSFER_ADMIN") && <Card outlined>
        {others.length === 0 ? <p>There's nobody else in the group.</p> : others.map((m) => (
          <label key={m.user_id} className="row" style={{ cursor: "pointer" }}><input type="radio" style={{ width: "auto" }} checked={member === m.user_id} onChange={() => setMember(m.user_id)} />{m.name}</label>))}
        {t === "TRANSFER_ADMIN" && <p className="muted small">The new admin must also accept (their approval counts as acceptance).</p>}
      </Card>}
      {t === "DELETE_GROUP" && <p>When approved, every schedule is cancelled and all members are removed, as if everyone left.</p>}
      <button className="btn big" disabled={!payload || act.busy} onClick={() => setConfirm(true)}>Ask for approval</button>
      <Confirm open={confirm} onClose={() => setConfirm(false)} title="Ask the group?" body="Nothing changes until every other member approves." confirm="Send"
        destructive={t === "DELETE_GROUP" || t === "REMOVE_MEMBER"}
        onConfirm={() => act.run(async () => { const aid = await api.createAdminAction(id, t, payload!); nav(`/admin-actions/${aid}`, { replace: true }); })} />
    </div>
  );
}

export function AdminActionPage() {
  const { id = "" } = useParams();
  const act = useAction();
  const [rejecting, setRejecting] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const d = useLoad(async () => {
    const a = await api.adminAction(id);
    const [sum, detail, upcoming] = await Promise.all([api.adminSummary(id), api.groupDetail(a.group_id),
      a.type === "TIMEZONE" ? api.groupProposals(a.group_id).then((l) => l.filter((p) => ["APPROVED", "LIVE"].includes(p.status))) : Promise.resolve([])]);
    return { a, sum, detail, upcoming };
  }, [id], ["admin_actions", "groups"]);
  if (d.loading && !d.data) return <Spinner />;
  if (d.error) return <ErrorState error={d.error} onRetry={d.reload} />;
  const { a, sum, detail, upcoming } = d.data!;
  const admin = detail.members.find((m) => m.user_id === a.created_by)?.name ?? "The admin";
  const run = (fn: () => Promise<unknown>) => act.run(fn, d.reload);
  const isNewAdmin = a.type === "TRANSFER_ADMIN" && s(a, "user_id") === detail.myId;
  return (
    <div className="stack" style={{ maxWidth: 600 }}>
      <Link to={`/groups/${a.group_id}`} className="muted small">← {detail.group.name}</Link>
      <h1>{adminTitle(a)}</h1>
      <p className="muted">Started by {admin} (admin)</p>
      {act.message && <Banner error>{act.message}</Banner>}
      {a.type === "TIMEZONE" && s(a, "previous") && s(a, "time_zone") && <TzPreview upcoming={upcoming} from={s(a, "previous")!} to={s(a, "time_zone")!} />}
      {a.type === "DELETE_GROUP" && <Banner>If approved, every schedule is cancelled and everyone is removed.</Banner>}
      <Ring done={sum.approvals} total={sum.required} label={`${sum.approvals} of ${sum.required} approved`} />
      <p>{{ PENDING: `Expires in ${sum.deadline ? remaining(sum.deadline) : "48h"} unless everyone approves.`, APPROVED: "Approved and applied.",
        REJECTED: `Not approved.${sum.reasons.length ? " Reasons: " + sum.reasons.map((r) => `“${r}”`).join("; ") : ""}`, EXPIRED: "Expired: not everyone approved within 48 hours." }[sum.status] ?? "Cancelled."}</p>
      {sum.can_vote ? <div className="row"><button className="btn" onClick={() => run(() => api.castAdminVote(id, true))}>{isNewAdmin ? "Accept" : "Approve"}</button>
        <button className="btn ghost" onClick={() => setRejecting(true)}>Reject</button></div>
        : sum.my_vote && <p className="muted small">You voted.</p>}
      {detail.me?.isAdmin && sum.status === "PENDING" && <button className="btn text" style={{ alignSelf: "flex-start" }} onClick={() => setCancelling(true)}>Cancel request</button>}
      <ReasonDialog open={rejecting} title="Reject?" body="Rejections are anonymous." confirm="Reject" onClose={() => setRejecting(false)} onConfirm={(r) => run(() => api.castAdminVote(id, false, r))} />
      <Confirm open={cancelling} onClose={() => setCancelling(false)} title="Cancel this request?" body="Nothing will change." confirm="Cancel request" onConfirm={() => run(() => api.cancelAdminAction(id))} />
    </div>
  );
}
