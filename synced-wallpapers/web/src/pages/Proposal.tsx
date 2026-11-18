import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import * as api from "../lib/api";
import { useAction, useLoad } from "../lib/hooks";
import { Banner, Card, Confirm, Dialog, ErrorState, ReasonDialog, Ring, Spinner, statusLabel } from "../components/ui";
import { PhoneFrame } from "../components/PhoneFrame";
import { groupAndLocal, localWindowIfDifferent, localZone, remaining, validateWindow, windowText } from "../lib/time";

const TARGET: Record<string, string> = { HOME: "Home screen", LOCK: "Lock screen", BOTH: "Home and lock screen" };

export default function ProposalPage() {
  const { id = "" } = useParams();
  const nav = useNavigate();
  const act = useAction();
  const [rejecting, setRejecting] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [suggesting, setSuggesting] = useState(false);
  const [shift, setShift] = useState(24);
  const d = useLoad(async () => {
    const p = await api.proposal(id);
    const [votes, conflicts, detail, url, delivery] = await Promise.all([
      api.voteSummary(id), p.status === "PENDING" ? api.proposalConflicts(id) : Promise.resolve([]), api.groupDetail(p.group_id),
      ["PENDING", "APPROVED", "LIVE"].includes(p.status) ? api.signedUrl(p.image_path).catch(() => null) : Promise.resolve(null),
      ["APPROVED", "LIVE"].includes(p.status) ? api.deliverySummary(id).catch(() => null) : Promise.resolve(null)]);
    const isProposer = p.proposer_id === detail.myId;
    const suggestions = isProposer && p.status === "PENDING" ? await api.suggestions(id).catch(() => []) : [];
    return { p, votes, conflicts, detail, url, delivery, isProposer, suggestions };
  }, [id], ["proposals", "group_members"]);

  if (d.loading && !d.data) return <Spinner />;
  if (d.error) return <ErrorState error={d.error} onRetry={d.reload} />;
  const { p, votes, conflicts, detail, url, delivery, isProposer, suggestions } = d.data!;
  const zone = detail.group.time_zone;
  const proposer = detail.members.find((m) => m.user_id === p.proposer_id)?.name ?? "A former member";
  const run = (fn: () => Promise<unknown>) => act.run(fn, d.reload);
  const sStart = new Date(Date.parse(p.start_at) + shift * 36e5).toISOString(), sEnd = new Date(Date.parse(p.end_at) + shift * 36e5).toISOString();

  return (
    <div className="stack" style={{ maxWidth: 860 }}>
      <Link to={`/groups/${p.group_id}`} className="muted small">← {detail.group.name}</Link>
      <div className="row" style={{ alignItems: "flex-start", gap: 32, flexWrap: "wrap" }}>
        {url ? <PhoneFrame url={url} focalX={p.focal_x} focalY={p.focal_y} reminder={p.reminder_text} style={p.reminder_style} width={260} />
          : <Banner>The photo is no longer available.</Banner>}
        <div className="stack" style={{ flex: 1, minWidth: 300 }}>
          <div className="row"><h1>Proposal</h1><span className="pill">{statusLabel(p.status)}</span></div>
          {act.message && <Banner error>{act.message}</Banner>}
          <Card outlined>
            <h3>{windowText(p.start_at, p.end_at, zone)}</h3>
            {localWindowIfDifferent(p.start_at, p.end_at, zone) && <p className="muted small">{localWindowIfDifferent(p.start_at, p.end_at, zone)}</p>}
            <p className="muted small">{TARGET[p.target]} · proposed by {proposer}</p>
            {p.status === "PENDING" && <p className="muted small">Voting closes {groupAndLocal(p.vote_deadline, zone)} · in {remaining(p.vote_deadline)}</p>}
          </Card>

          {p.status === "PENDING" && <>
            <Ring done={votes.approvals} total={votes.required} label={`${votes.approvals} of ${votes.required} approved`} />
            {conflicts[0] && <Banner error>Overlaps with “{conflicts[0].group_name}” {windowText(conflicts[0].start_at, conflicts[0].end_at, localZone)} on your phone.</Banner>}
            {votes.my_vote == null ? <>
              <div className="row"><button className="btn" disabled={conflicts.length > 0 || act.busy} onClick={() => run(() => api.castVote(id, true))}>Approve</button>
                <button className="btn ghost" onClick={() => setRejecting(true)}>Reject</button></div>
              {detail.me?.delivery_mode === "AUTO" && <p className="muted small">Approving means it is set on your phone automatically at the start time.</p>}
            </> : <p>{isProposer ? "You proposed this." : `You voted ${votes.my_vote === "APPROVE" ? "Approve" : "Reject"}.`}</p>}
            {!isProposer && <button className="btn text" style={{ alignSelf: "flex-start" }} onClick={() => setSuggesting(true)}>Suggest another time</button>}
            {isProposer && suggestions.length > 0 && <><h2>Suggested times</h2>{suggestions.map((s) => (
              <Card key={s.id} outlined><p>{windowText(s.suggested_start, s.suggested_end, zone)}</p>
                <button className="btn" style={{ alignSelf: "flex-start" }} onClick={() => nav(`/groups/${p.group_id}/propose?replaces=${p.id}&start=${encodeURIComponent(s.suggested_start)}&end=${encodeURIComponent(s.suggested_end)}`)}>Re-propose at this time</button>
                <p className="muted small">Creates a new proposal; votes start again.</p></Card>))}</>}
          </>}
          {["APPROVED", "LIVE"].includes(p.status) && delivery && <>
            <Ring done={delivery.ready} total={delivery.members - delivery.skipped} label={`${delivery.ready} of ${delivery.members - delivery.skipped} phones ready${delivery.skipped ? ` · ${delivery.skipped} skipped` : ""}`} />
            {delivery.people.length > 0 && <p className="muted small">{delivery.people.map((x) => `${x.name} (${x.state.toLowerCase()})`).join(", ")}</p>}
            <p className="muted small">To skip it on your phone, use the Livrun app.</p>
          </>}
          {p.status === "REJECTED" && <><h3>Not approved.</h3>{isProposer && votes.reasons.map((r, i) => <p key={i}>“{r}”</p>)}</>}
          {p.status === "EXPIRED" && <p>Expired: not everyone voted before the deadline.</p>}
          {p.status === "CANCELLED" && <p>Cancelled.</p>}
          {isProposer && ["PENDING", "APPROVED", "LIVE"].includes(p.status) && <button className="btn text danger" style={{ alignSelf: "flex-start" }} onClick={() => setCancelling(true)}>Cancel for everyone</button>}
        </div>
      </div>

      <ReasonDialog open={rejecting} title="Reject?" body="The proposer sees “Not approved” and your reason, never your name." confirm="Reject" onClose={() => setRejecting(false)} onConfirm={(r) => run(() => api.castVote(id, false, r))} />
      <Confirm open={cancelling} onClose={() => setCancelling(false)} title={`Cancel this ${p.status === "PENDING" ? "proposal" : "schedule"}?`}
        body="It's cancelled on every member's phone. If it's live, everyone's own wallpaper comes back." confirm="Cancel it" onConfirm={() => run(() => api.cancelProposal(id))} />
      <Dialog open={suggesting} onClose={() => { setSuggesting(false); setShift(24); }}>
        <h3>Suggest another time</h3>
        <p>{windowText(sStart, sEnd, zone)}</p>
        <div className="row wrap" style={{ margin: "12px 0" }}>
          {([[-24, "−1 day"], [-1, "−1 h"], [1, "+1 h"], [24, "+1 day"]] as const).map(([h, l]) =>
            <button key={l} className="btn ghost" onClick={() => setShift((x) => x + h)}>{l}</button>)}
        </div>
        {validateWindow(sStart, sEnd) && <Banner error>{validateWindow(sStart, sEnd)}</Banner>}
        <div className="actions">
          <button className="btn text" onClick={() => { setSuggesting(false); setShift(24); }}>Cancel</button>
          <button className="btn" disabled={!!validateWindow(sStart, sEnd)} onClick={() => { setSuggesting(false); run(() => api.suggestTime(id, sStart, sEnd)); }}>Send</button>
        </div>
      </Dialog>
    </div>
  );
}
