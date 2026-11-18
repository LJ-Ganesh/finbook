import { DateTime } from "luxon";

export type TSlot = { start: string; end: string; kind: "APPROVED" | "PENDING" | "TAKEN" | "MINE" };

/** Days as 24-hour bars in the group's zone (23/25 h on DST days). */
export function Timeline({ zone, slots, days = 7, from }: { zone: string; slots: TSlot[]; days?: number; from?: string }) {
  const start = (from ? DateTime.fromISO(from, { zone }) : DateTime.now().setZone(zone)).startOf("day");
  return (
    <div className="timeline">
      {Array.from({ length: days }, (_, i) => {
        const d0 = start.plus({ days: i }), d1 = d0.plus({ days: 1 }), total = d1.toMillis() - d0.toMillis();
        return (
          <div className="day" key={i}>
            <span>{d0.toFormat("ccc d")}</span>
            <div className="bar">
              {slots.map((s, j) => {
                const a = Math.max(Date.parse(s.start), d0.toMillis()), b = Math.min(Date.parse(s.end), d1.toMillis());
                if (a >= b) return null;
                return <div key={j} className={`slot ${s.kind}`} style={{ left: `${((a - d0.toMillis()) / total) * 100}%`, width: `max(3px, ${((b - a) / total) * 100}%)` }} />;
              })}
            </div>
          </div>
        );
      })}
      <div className="day"><span /><div className="row spread small muted"><span>0</span><span>6</span><span>12</span><span>18</span><span>24</span></div></div>
    </div>
  );
}
