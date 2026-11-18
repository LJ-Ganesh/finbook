import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { DateTime } from "luxon";
import * as api from "../lib/api";
import { useAction, useLoad } from "../lib/hooks";
import { prepareUpload } from "../lib/image";
import { Banner, Card, ErrorState, Spinner } from "../components/ui";
import { PhoneFrame } from "../components/PhoneFrame";
import { Timeline, TSlot } from "../components/Timeline";
import { groupAndLocal, localWindowIfDifferent, overlaps, partsInZone, toInstant, validateWindow, windowText } from "../lib/time";

const STEPS = ["Photo", "Focal point", "Where", "When", "Reminder", "Review"] as const;
const TARGETS = [["HOME", "Home screen"], ["LOCK", "Lock screen"], ["BOTH", "Both"]] as const;

export default function Propose() {
  const { id = "" } = useParams();
  const [qs] = useSearchParams();
  const replaces = qs.get("replaces");
  const nav = useNavigate();
  const act = useAction();
  const ctx = useLoad(async () => {
    const now = new Date();
    const [detail, slots, commitments] = await Promise.all([
      api.groupDetail(id), api.takenSlots(id, now.toISOString(), new Date(now.getTime() + 62 * 864e5).toISOString()), api.myCommitments()]);
    const old = replaces ? await api.proposal(replaces) : null;
    const oldUrl = old ? await api.signedUrl(old.image_path).catch(() => null) : null;
    return { group: detail.group, slots: slots.filter((s) => s.proposal_id !== replaces), commitments: commitments.filter((c) => c.proposal_id !== replaces), old, oldUrl };
  }, [id, replaces]);

  const [step, setStep] = useState(0);
  const [jpeg, setJpeg] = useState<{ blob: Blob; url: string; width: number; height: number } | null>(null);
  const [focal, setFocal] = useState({ x: 0.5, y: 0.5 });
  const [target, setTarget] = useState<"HOME" | "LOCK" | "BOTH">("BOTH");
  const [when, setWhen] = useState({ startDate: "", startTime: "09:00", endDate: "", endTime: "21:00" });
  const [reminder, setReminder] = useState("");
  const [style, setStyle] = useState(0);

  // One-tap re-propose: same photo and settings, new time.
  useEffect(() => {
    const c = ctx.data; if (!c?.old) return;
    const zone = c.group.time_zone, s = qs.get("start") ?? c.old.start_at, e = qs.get("end") ?? c.old.end_at;
    const ps = partsInZone(s, zone), pe = partsInZone(e, zone);
    setWhen({ startDate: ps.date, startTime: ps.time, endDate: pe.date, endTime: pe.time });
    setFocal({ x: c.old.focal_x, y: c.old.focal_y }); setTarget(c.old.target); setReminder(c.old.reminder_text ?? ""); setStyle(c.old.reminder_style);
    setStep(3);
  }, [ctx.data, qs]);
  useEffect(() => () => { if (jpeg) URL.revokeObjectURL(jpeg.url); }, [jpeg]);

  const zone = ctx.data?.group.time_zone ?? "UTC";
  const start = when.startDate ? toInstant(when.startDate, when.startTime, zone) : null;
  const end = (when.endDate || when.startDate) ? toInstant(when.endDate || when.startDate, when.endTime, zone) : null;
  const problem = useMemo(() => {
    if (!ctx.data) return null;
    if (!start || !end) return "Pick a start date.";
    const v = validateWindow(start, end); if (v) return v;
    if (ctx.data.slots.some((s) => overlaps(start, end, s.start_at, s.end_at))) return "That overlaps a slot that's already taken in this group (shaded).";
    const c = ctx.data.commitments.find((c) => overlaps(start, end, c.start_at, c.end_at));
    if (c) return `Overlaps with “${c.group_name}” ${windowText(c.start_at, c.end_at, zone)} on your phone.`;
    return null;
  }, [ctx.data, start, end, zone]);

  if (ctx.loading && !ctx.data) return <Spinner />;
  if (ctx.error) return <ErrorState error={ctx.error} onRetry={ctx.reload} />;
  const c = ctx.data!;
  const previewUrl = jpeg?.url ?? c.oldUrl;
  const firstStep = replaces ? 3 : 0;
  const frame = (withText: boolean) => <PhoneFrame url={previewUrl} focalX={focal.x} focalY={focal.y} reminder={withText ? reminder : null} style={style} />;
  const timelineSlots: TSlot[] = [
    ...c.slots.map((s) => ({ start: s.start_at, end: s.end_at, kind: "TAKEN" as const })),
    ...c.commitments.map((s) => ({ start: s.start_at, end: s.end_at, kind: "TAKEN" as const })),
    ...(start && end && end > start ? [{ start, end, kind: "MINE" as const }] : []),
  ];
  const daysShown = start && end ? Math.min(7, Math.max(1, Math.ceil(DateTime.fromISO(end).diff(DateTime.fromISO(start).startOf("day"), "days").days))) : 3;

  async function send() {
    await act.run(async () => {
      const pid = await api.createProposal({ groupId: id, start: start!, end: end!, target, focalX: focal.x, focalY: focal.y, reminder: reminder || null, style, jpeg: jpeg?.blob ?? null, replaces });
      nav(`/proposals/${pid}`, { replace: true });
    });
  }

  return (
    <div className="stack" style={{ maxWidth: 860 }}>
      <Link to={`/groups/${id}`} className="muted small">← {c.group.name}</Link>
      <h1>{replaces ? "Re-propose" : "Propose a wallpaper"}</h1>
      <div className="steps">{STEPS.map((s, i) => <span key={s} className={i <= step ? "on" : ""} title={s} />)}</div>
      {act.message && <Banner error>{act.message}</Banner>}

      <div className="row" style={{ alignItems: "flex-start", gap: 32, flexWrap: "wrap" }}>
        <div className="stack" style={{ flex: 1, minWidth: 300 }}>
          <h3>{STEPS[step]}</h3>
          {step === 0 && <>
            <p className="muted">JPEG, PNG or WebP, up to 20 MB. We upload a smaller copy with location and other metadata removed.</p>
            <label className="btn" style={{ alignSelf: "flex-start" }}>
              {act.busy ? "Preparing…" : "Choose photo"}
              <input type="file" accept="image/jpeg,image/png,image/webp,image/heic" hidden onChange={(e) => {
                const f = e.target.files?.[0]; if (!f) return;
                act.run(async () => { setJpeg(await prepareUpload(f)); setStep(1); });
              }} />
            </label>
          </>}
          {step === 1 && jpeg && <>
            <p className="muted">Click the part of the photo that must stay visible. Each phone crops around it.</p>
            <div className="focal" onClick={(e) => {
              const r = (e.currentTarget as HTMLDivElement).getBoundingClientRect();
              setFocal({ x: Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)), y: Math.min(1, Math.max(0, (e.clientY - r.top) / r.height)) });
            }}>
              <img src={jpeg.url} alt="Your photo" draggable={false} onContextMenu={(e) => e.preventDefault()} />
              <div className="dot" style={{ left: `${focal.x * 100}%`, top: `${focal.y * 100}%` }} />
            </div>
          </>}
          {step === 2 && <div className="seg">{TARGETS.map(([k, l]) => <button key={k} className={target === k ? "on" : ""} onClick={() => setTarget(k)}>{l}</button>)}</div>}
          {step === 3 && <>
            <p className="muted">Times are in the group's zone, {zone}.</p>
            <div className="row wrap">
              <label className="field">Start date<input type="date" value={when.startDate} min={DateTime.now().setZone(zone).toISODate()!}
                onChange={(e) => setWhen((w) => ({ ...w, startDate: e.target.value, endDate: !w.endDate || w.endDate < e.target.value ? e.target.value : w.endDate }))} /></label>
              <label className="field">From<input type="time" value={when.startTime} onChange={(e) => setWhen((w) => ({ ...w, startTime: e.target.value }))} /></label>
            </div>
            <div className="row wrap">
              <label className="field">End date<input type="date" value={when.endDate || when.startDate} min={when.startDate} onChange={(e) => setWhen((w) => ({ ...w, endDate: e.target.value }))} /></label>
              <label className="field">Until<input type="time" value={when.endTime} onChange={(e) => setWhen((w) => ({ ...w, endTime: e.target.value }))} /></label>
            </div>
            {when.startDate && <Card><Timeline zone={zone} slots={timelineSlots} days={daysShown} from={start ?? undefined} />
              <div className="legend"><span><i style={{ background: "color-mix(in srgb, var(--danger) 35%, transparent)" }} />Taken, or your other commitments</span><span><i style={{ background: "color-mix(in srgb, var(--accent) 60%, transparent)" }} />Your choice</span></div></Card>}
            {start && end && end > start && <><p>{windowText(start, end, zone)}</p>{localWindowIfDifferent(start, end, zone) && <p className="muted small">{localWindowIfDifferent(start, end, zone)}</p>}</>}
            {problem && when.startDate && <Banner error>{problem}</Banner>}
          </>}
          {step === 4 && <>
            <label className="field">Reminder text (optional) · {reminder.length}/60
              <input value={reminder} maxLength={60} onChange={(e) => setReminder(e.target.value)} placeholder="e.g. Happy Diwali!" /></label>
            <div className="seg">{["Soft", "Bold", "Minimal"].map((s, i) => <button key={s} className={style === i ? "on" : ""} onClick={() => setStyle(i)}>{s}</button>)}</div>
            <p className="muted small">Placed away from the clock.</p>
          </>}
          {step === 5 && start && end && <>
            <Card outlined>
              <h3>{c.group.name}</h3><p>{windowText(start, end, zone)}</p>
              {localWindowIfDifferent(start, end, zone) && <p className="muted small">{localWindowIfDifferent(start, end, zone)}</p>}
              <p className="muted small">Voting closes {groupAndLocal(new Date(Date.parse(start) - 15 * 60e3).toISOString(), zone)} (15 minutes before the start).</p>
              <p className="muted small">{TARGETS.find(([k]) => k === target)![1]}</p>
            </Card>
            <Banner>Every member is notified and must approve before the deadline. One “no” stops it. You can't edit it after sending, but you can cancel it.</Banner>
          </>}

          <div className="row" style={{ marginTop: 8 }}>
            {step > firstStep && <button className="btn ghost" onClick={() => setStep(step === 4 && replaces ? 3 : step - 1)}>Back</button>}
            {step > 0 && step < 5 && <button className="btn" disabled={step === 3 && !!problem}
              onClick={() => setStep(replaces && step === 3 ? 5 : step + 1)}>Next</button>}
            {step === 5 && <button className="btn" disabled={!!problem || act.busy} onClick={send}>{act.busy ? "Sending…" : "Send proposal"}</button>}
          </div>
        </div>
        {step > 0 && <div className="stack" style={{ alignItems: "center" }}>{frame(step >= 4)}<p className="muted small">Preview on a phone · dashed box = clock</p></div>}
      </div>
    </div>
  );
}
