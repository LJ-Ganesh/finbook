import { ReactNode, useEffect, useRef, useState } from "react";
import { AppError } from "../lib/errors";

export function Mark({ size = 32 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="27 26 56 58" aria-hidden="true">
      <defs>
        <linearGradient id="lg" x1="30" y1="26" x2="80" y2="82" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#7BF1BF" /><stop offset="1" stopColor="#0E9F6E" />
        </linearGradient>
      </defs>
      <path d="M37 33 V61 A15 15 0 0 0 52 76 H73" fill="none" stroke="url(#lg)" strokeWidth="13" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="64.5" cy="45.5" r="9.5" fill="url(#lg)" />
    </svg>
  );
}

export const Card = ({ children, outlined, accent, onClick, className = "" }: { children: ReactNode; outlined?: boolean; accent?: boolean; onClick?: () => void; className?: string }) => (
  <div className={`card ${outlined ? "outlined" : ""} ${accent ? "accent" : ""} ${onClick ? "link" : ""} ${className}`} onClick={onClick}
    role={onClick ? "button" : undefined} tabIndex={onClick ? 0 : undefined} onKeyDown={(e) => { if (onClick && (e.key === "Enter" || e.key === " ")) onClick(); }}>
    {children}
  </div>
);

export const Spinner = () => <div className="spinner" role="status" aria-label="Loading" />;

export function Empty({ icon, title, body, children }: { icon: string; title: string; body: string; children?: ReactNode }) {
  return <div className="empty"><div className="icon">{icon}</div><h3>{title}</h3><p className="muted">{body}</p>{children}</div>;
}

export function ErrorState({ error, onRetry }: { error: AppError; onRetry?: () => void }) {
  return (
    <Empty icon={/waking/.test(error.userMessage) ? "☁" : "!"} title={/waking/.test(error.userMessage) ? "One moment" : "Couldn't load this"} body={error.userMessage}>
      {onRetry && <button className="btn ghost" onClick={onRetry}>Try again</button>}
    </Empty>
  );
}

export function Banner({ children, error, action }: { children: ReactNode; error?: boolean; action?: ReactNode }) {
  return <div className={`banner ${error ? "error" : ""}`}><div className="grow">{children}</div>{action}</div>;
}

export function Ring({ done, total, label, size = 52 }: { done: number; total: number; label: string; size?: number }) {
  const r = (size - 8) / 2, c = 2 * Math.PI * r, f = total > 0 ? Math.min(1, done / total) : 0;
  return (
    <div className="ring">
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true">
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--surface-3)" strokeWidth="6" />
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--accent)" strokeWidth="6" strokeLinecap="round"
          strokeDasharray={`${c * f} ${c}`} transform={`rotate(-90 ${size / 2} ${size / 2})`} style={{ transition: "stroke-dasharray .4s" }} />
        <text x="50%" y="50%" dominantBaseline="central" textAnchor="middle" fontSize="12" fontWeight="600" fill="currentColor">{done}/{total}</text>
      </svg>
      <span>{label}</span>
    </div>
  );
}

export function Dialog({ open, onClose, children }: { open: boolean; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => { const d = ref.current; if (!d) return; if (open && !d.open) d.showModal(); if (!open && d.open) d.close(); }, [open]);
  return <dialog ref={ref} onClose={onClose} onCancel={onClose}>{open && children}</dialog>;
}

export function Confirm({ open, title, body, confirm, destructive = true, onConfirm, onClose }:
  { open: boolean; title: string; body: string; confirm: string; destructive?: boolean; onConfirm: () => void; onClose: () => void }) {
  return (
    <Dialog open={open} onClose={onClose}>
      <h3>{title}</h3><p className="muted">{body}</p>
      <div className="actions"><button className="btn text" onClick={onClose}>Cancel</button>
        <button className={`btn ${destructive ? "danger" : ""}`} onClick={() => { onClose(); onConfirm(); }}>{confirm}</button></div>
    </Dialog>
  );
}

export function ReasonDialog({ open, title, body, confirm, onConfirm, onClose }:
  { open: boolean; title: string; body: string; confirm: string; onConfirm: (r: string | null) => void; onClose: () => void }) {
  const [reason, setReason] = useState("");
  return (
    <Dialog open={open} onClose={onClose}>
      <h3>{title}</h3><p className="muted">{body}</p>
      <label className="field" style={{ marginTop: 14 }}>Reason (optional)
        <textarea rows={3} maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)} /></label>
      <div className="actions"><button className="btn text" onClick={onClose}>Cancel</button>
        <button className="btn" onClick={() => { onClose(); onConfirm(reason.trim() || null); }}>{confirm}</button></div>
    </Dialog>
  );
}

export function InputDialog({ open, title, initial, label, onSave, onClose }:
  { open: boolean; title: string; initial: string; label: string; onSave: (v: string) => void; onClose: () => void }) {
  const [v, setV] = useState(initial);
  useEffect(() => setV(initial), [initial, open]);
  return (
    <Dialog open={open} onClose={onClose}>
      <h3>{title}</h3>
      <label className="field">{label}<input value={v} maxLength={60} onChange={(e) => setV(e.target.value)} /></label>
      <div className="actions"><button className="btn text" onClick={onClose}>Cancel</button><button className="btn" onClick={() => { onClose(); onSave(v.trim()); }}>Save</button></div>
    </Dialog>
  );
}

export function Avatar({ name, url }: { name: string; url?: string | null }) {
  const [broken, setBroken] = useState(false);
  return <div className="avatar">{url && !broken ? <img src={url} alt="" referrerPolicy="no-referrer" onError={() => setBroken(true)} /> : (name[0] ?? "?").toUpperCase()}</div>;
}

export const statusLabel = (s: string) => ({ PENDING: "Voting", APPROVED: "Approved", LIVE: "Live", REJECTED: "Not approved", EXPIRED: "Expired", CANCELLED: "Cancelled", ENDED: "Ended", PURGED: "Ended" } as Record<string, string>)[s] ?? s;
