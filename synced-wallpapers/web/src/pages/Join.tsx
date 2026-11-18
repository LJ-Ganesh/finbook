import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import * as api from "../lib/api";
import { useAction, useLoad } from "../lib/hooks";
import { Banner, Card, Confirm, ErrorState, Ring, Spinner } from "../components/ui";
import { PhoneFrame } from "../components/PhoneFrame";
import { localWindowIfDifferent, localZone, windowText } from "../lib/time";

export function JoinCode() {
  const { code: initial = "" } = useParams();
  const [code, setCode] = useState(initial);
  const nav = useNavigate();
  const act = useAction();
  const tried = useRef(false);
  const redeem = (c: string) => act.run(async () => nav(`/join-requests/${await api.redeemInvite(c)}`, { replace: true }));
  useEffect(() => { if (initial && !tried.current) { tried.current = true; redeem(initial); } }, [initial]); // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <div className="stack" style={{ maxWidth: 480 }}>
      <Link to="/" className="muted small">← Home</Link>
      <h1>Join a group</h1>
      {act.message && <Banner error>{act.message}</Banner>}
      <label className="field">Invite code or link<input value={code} onChange={(e) => setCode(e.target.value)} placeholder="ABCD-2345" autoFocus /></label>
      <p className="muted small">You'll see the group's upcoming wallpapers and must accept them. Then every member must approve you.</p>
      <button className="btn big" disabled={api.normalizeCode(code).length < 6 || act.busy} onClick={() => redeem(code)}>Continue</button>
    </div>
  );
}

export function JoinReview() {
  const { id = "" } = useParams();
  const nav = useNavigate();
  const act = useAction();
  const [declining, setDeclining] = useState(false);
  const d = useLoad(async () => {
    const p = await api.joinPreview(id);
    const urls = Object.fromEntries(await Promise.all(p.schedules.map(async (s) => [s.proposal_id, await api.signedUrl(s.image_path).catch(() => null)])));
    return { p, urls: urls as Record<string, string | null> };
  }, [id], ["join_requests", "notification_outbox"]);
  useEffect(() => { if (d.data?.p.status === "APPROVED") nav(`/groups/${d.data.p.group_id}`, { replace: true }); }, [d.data, nav]);
  if (d.loading && !d.data) return <Spinner />;
  if (d.error) return <ErrorState error={d.error} onRetry={d.reload} />;
  const { p, urls } = d.data!;
  const conflicts = p.schedules.flatMap((s) => s.conflicts);
  const run = (fn: () => Promise<unknown>) => act.run(fn, d.reload);
  return (
    <div className="stack" style={{ maxWidth: 760 }}>
      <Link to="/" className="muted small">← Home</Link>
      <h1>{p.group_name}</h1>
      <p className="muted">{p.member_count} members · {p.time_zone}</p>
      {act.message && <Banner error>{act.message}</Banner>}
      {p.status === "PENDING" && p.terms_accepted_at && <Ring done={p.approvals} total={p.required} label={`Waiting for members: ${p.approvals} of ${p.required} approved`} />}
      {p.status === "REJECTED" && <Banner>Not approved. You can't join this group with this request.</Banner>}
      {p.status === "CANCELLED" && <Banner>This join request was cancelled.</Banner>}
      {p.status === "EXPIRED" && <Banner>This join request expired.</Banner>}
      {p.status === "PENDING" && !p.terms_accepted_at && <p>{p.schedules.length ? "To join you must accept these upcoming wallpapers on your phone:" : "There are no upcoming wallpapers right now."}</p>}
      <div className="cards">{p.schedules.map((s) => (
        <Card key={s.proposal_id} outlined>
          {urls[s.proposal_id] && <PhoneFrame url={urls[s.proposal_id]} focalX={s.focal_x} focalY={s.focal_y} reminder={s.reminder_text} style={s.reminder_style} width={180} />}
          <b className="small">{windowText(s.start_at, s.end_at, p.time_zone)}</b>
          {localWindowIfDifferent(s.start_at, s.end_at, p.time_zone) && <p className="muted small">{localWindowIfDifferent(s.start_at, s.end_at, p.time_zone)}</p>}
          {s.conflicts.map((c) => <Banner key={c.proposal_id} error>Overlaps with “{c.group_name}” {windowText(c.start_at, c.end_at, localZone)} on your phone. Skip that one in the phone app, or leave that group.</Banner>)}
        </Card>))}</div>
      {p.status === "PENDING" && !p.terms_accepted_at && <>
        {conflicts.length > 0 && <p className="small" style={{ color: "var(--danger)" }}>Resolve the overlaps first.</p>}
        <div className="row"><button className="btn" disabled={conflicts.length > 0 || act.busy} onClick={() => run(() => api.acceptJoinTerms(id, true))}>Accept and ask to join</button>
          <button className="btn ghost" onClick={() => setDeclining(true)}>Don't join</button></div>
      </>}
      {p.status === "PENDING" && p.terms_accepted_at && <button className="btn ghost" style={{ alignSelf: "flex-start" }} onClick={() => setDeclining(true)}>Cancel join request</button>}
      <Confirm open={declining} onClose={() => setDeclining(false)} title="Cancel joining?" body="Your join request is cancelled." confirm="Cancel request"
        onConfirm={() => run(() => (p.terms_accepted_at ? api.cancelJoinRequest(id) : api.acceptJoinTerms(id, false)))} />
    </div>
  );
}
