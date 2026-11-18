import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import * as api from "../lib/api";
import { useAction, useLoad } from "../lib/hooks";
import { Avatar, Banner, Card, Empty, ErrorState, ReasonDialog, Ring, Spinner, statusLabel } from "../components/ui";
import { Timeline } from "../components/Timeline";
import { localWindowIfDifferent, windowText, zoneShort } from "../lib/time";
import { adminTitle } from "./Admin";

export default function GroupPage() {
  const { id = "" } = useParams();
  const nav = useNavigate();
  const act = useAction();
  const [rejecting, setRejecting] = useState<string | null>(null);
  const d = useLoad(async () => {
    const [detail, proposals, actions, joins] = await Promise.all([api.groupDetail(id), api.groupProposals(id), api.adminActions(id), api.joinRequests(id)]);
    const items = await Promise.all(proposals.map(async (p) => ({
      p,
      votes: await api.voteSummary(p.id).catch(() => null),
      delivery: ["APPROVED", "LIVE"].includes(p.status) ? await api.deliverySummary(p.id).catch(() => null) : null,
    })));
    const pendingActions = await Promise.all(actions.filter((a) => a.status === "PENDING").map(async (a) => ({ a, s: await api.adminSummary(a.id).catch(() => null) })));
    return { detail, items, pendingActions, joins };
  }, [id], ["groups", "group_members", "proposals", "admin_actions", "join_requests"]);

  if (d.loading && !d.data) return <Spinner />;
  if (d.error) return <ErrorState error={d.error} onRetry={d.reload} />;
  const { detail, items, pendingActions, joins } = d.data!;
  const g = detail.group, zone = g.time_zone;
  const run = (fn: () => Promise<unknown>) => act.run(fn, d.reload);

  return (
    <div className="grid2">
      <div className="stack">
        <div className="row spread wrap">
          <div><h1>{g.name}</h1><p className="muted">Times in {zone} · {zoneShort(zone)}</p></div>
          <div className="row"><Link className="btn ghost" to={`/groups/${id}/settings`}>Settings</Link><Link className="btn" to={`/groups/${id}/propose`}>Propose</Link></div>
        </div>
        {act.message && <Banner error action={<button className="btn text" onClick={() => act.setMessage(null)}>OK</button>}>{act.message}</Banner>}
        {detail.me?.isAdmin && g.admin_handover_pending && (
          <Banner action={<div className="row"><button className="btn text" onClick={() => run(() => api.declineAdmin(id))}>Decline</button><button className="btn text" onClick={() => run(() => api.acceptAdmin(id))}>OK</button></div>}>
            You're now the admin because the previous admin left.
          </Banner>)}

        <Card>
          <Timeline zone={zone} slots={items.filter((i) => ["PENDING", "APPROVED", "LIVE"].includes(i.p.status))
            .map((i) => ({ start: i.p.start_at, end: i.p.end_at, kind: i.p.status === "PENDING" ? "PENDING" : "APPROVED" }))} />
          <div className="legend"><span><i style={{ background: "var(--accent)" }} />Approved</span><span><i style={{ border: "2px solid var(--accent)" }} />Pending</span></div>
        </Card>

        {joins.length > 0 && <><h2>Join requests</h2>{joins.map((r) => (
          <Card key={r.request_id} outlined>
            <h3>{r.name || "Someone"} wants to join</h3>{r.email && <p className="muted small">{r.email}</p>}
            <Ring done={r.approvals} total={r.required} size={44} label={r.terms_accepted ? `${r.approvals} of ${r.required} approved` : "Waiting for them to accept the upcoming wallpapers"} />
            {r.my_vote ? <p className="muted small">You voted.</p> : <div className="row"><button className="btn" onClick={() => run(() => api.voteJoin(r.request_id, true))}>Approve</button><button className="btn ghost" onClick={() => setRejecting(r.request_id)}>Reject</button></div>}
          </Card>))}</>}

        {pendingActions.length > 0 && <><h2>Admin requests</h2>{pendingActions.map(({ a, s }) => (
          <Card key={a.id} outlined onClick={() => nav(`/admin-actions/${a.id}`)}>
            <h3>{adminTitle(a)}</h3>{s && <Ring done={s.approvals} total={s.required} size={44} label={s.can_vote ? "Your approval is needed" : `${s.approvals} of ${s.required} approved`} />}
          </Card>))}</>}

        <h2>Wallpapers</h2>
        {items.length === 0 ? <Empty icon="🖼" title="Nothing scheduled" body="Propose a photo and a time. It goes live only if everyone approves." /> :
          items.map(({ p, votes, delivery }) => {
            const by = detail.members.find((m) => m.user_id === p.proposer_id)?.name ?? "A former member";
            return (
              <Card key={p.id} outlined={p.status === "PENDING"} accent={p.status === "LIVE"} onClick={() => nav(`/proposals/${p.id}`)}>
                <div className="row spread"><h3>{windowText(p.start_at, p.end_at, zone)}</h3><span className="pill">{statusLabel(p.status)}</span></div>
                {localWindowIfDifferent(p.start_at, p.end_at, zone) && <p className="muted small">{localWindowIfDifferent(p.start_at, p.end_at, zone)}</p>}
                <p className="muted small">Proposed by {by}</p>
                {p.status === "PENDING" && votes && <Ring done={votes.approvals} total={votes.required} size={44} label={`${votes.approvals} of ${votes.required} approved${votes.my_vote ? "" : " · your vote is needed"}`} />}
                {delivery && <p className="small">{delivery.ready} of {delivery.members - delivery.skipped} ready{delivery.live ? ` · ${delivery.live} live` : ""}{delivery.skipped ? ` · ${delivery.skipped} skipped` : ""}</p>}
              </Card>);
          })}
      </div>

      <aside className="stack">
        <h2 style={{ marginTop: 8 }}>Members ({detail.members.length} of {g.max_members})</h2>
        <Card outlined>
          <div className="list">{detail.members.map((m) => (
            <div className="member" key={m.user_id}>
              <Avatar name={m.name} url={m.profile?.photo_url} />
              <div style={{ minWidth: 0 }}>
                <div className="row" style={{ gap: 8 }}><b>{m.name}</b>{m.isAdmin && <span className="pill accent">Admin</span>}</div>
                <p className="muted small" style={{ overflow: "hidden", textOverflow: "ellipsis" }}>{[m.profile?.email, m.profile?.phone_optional].filter(Boolean).join(" · ")}</p>
              </div>
            </div>))}</div>
        </Card>
        <h2>On my phone</h2>
        <Card outlined>
          <div className="seg" role="radiogroup">
            {(["AUTO", "TAP"] as const).map((m) => <button key={m} className={detail.me?.delivery_mode === m ? "on" : ""} role="radio" aria-checked={detail.me?.delivery_mode === m}
              onClick={() => run(() => api.setDeliveryMode(id, m))}>{m === "AUTO" ? "Auto" : "Tap to set"}</button>)}
          </div>
          <p className="muted small">{detail.me?.delivery_mode === "TAP" ? "At the start time your phone shows a notification. Nothing changes until you tap “Set wallpaper”." : "Approving means the wallpaper is set on your phone automatically at the start time."}</p>
        </Card>
      </aside>

      <ReasonDialog open={!!rejecting} title="Reject this join request?" body="They'll see “Not approved” and your reason, but not your name." confirm="Reject"
        onClose={() => setRejecting(null)} onConfirm={(r) => rejecting && run(() => api.voteJoin(rejecting, false, r))} />
    </div>
  );
}
