import { DateTime, Duration } from "luxon";

export const localZone = Intl.DateTimeFormat().resolvedOptions().timeZone;

const dt = (iso: string, zone: string) => DateTime.fromISO(iso, { zone });

export function zoneShort(zone: string, at = new Date().toISOString()) {
  return dt(at, zone).toFormat("ZZZZ");
}

export function sameOffset(at: string, a: string, b: string) {
  return dt(at, a).offset === dt(at, b).offset;
}

/** "Sat 12 Nov, 18:00–23:00 IST" */
export function windowText(start: string, end: string, zone: string) {
  const s = dt(start, zone), e = dt(end, zone);
  const endText = s.hasSame(e, "day") ? e.toFormat("H:mm") : e.toFormat("ccc d LLL, H:mm");
  return `${s.toFormat("ccc d LLL, H:mm")}–${endText} ${s.toFormat("ZZZZ")}`;
}

export function localWindowIfDifferent(start: string, end: string, zone: string) {
  if (sameOffset(start, zone, localZone) && sameOffset(end, zone, localZone)) return null;
  return `${windowText(start, end, localZone)} your time`;
}

/** "9:00 IST · 10:30 your time" */
export function groupAndLocal(at: string, zone: string) {
  const g = `${dt(at, zone).toFormat("H:mm")} ${dt(at, zone).toFormat("ZZZZ")}`;
  return sameOffset(at, zone, localZone) ? g : `${g} · ${dt(at, localZone).toFormat("H:mm")} your time`;
}

export function remaining(toIso: string, now = DateTime.now()) {
  const d = dt(toIso, "utc").diff(now);
  if (d.toMillis() <= 0) return "ended";
  const m = Math.floor(d.as("minutes"));
  if (m < 1) return "less than a minute";
  if (m < 60) return `${m}m`;
  if (m < 1440) return `${Math.floor(m / 60)}h ${m % 60}m`;
  return `${Math.floor(m / 1440)}d ${Math.floor((m % 1440) / 60)}h`;
}

export function shortDateTime(iso: string) {
  return DateTime.fromISO(iso).toFormat("ccc d LLL · H:mm");
}

/** Wall-clock date + time in the group's zone → ISO instant. */
export function toInstant(date: string, time: string, zone: string): string | null {
  const d = DateTime.fromISO(`${date}T${time}`, { zone });
  return d.isValid ? d.toUTC().toISO() : null;
}

export function partsInZone(iso: string, zone: string) {
  const d = dt(iso, zone);
  return { date: d.toISODate()!, time: d.toFormat("HH:mm") };
}

// Same rules as the server (create_proposal / _validate_window).
export function validateWindow(start: string, end: string): string | null {
  const s = DateTime.fromISO(start), e = DateTime.fromISO(end), now = DateTime.now();
  if (e <= s) return "The end must be after the start.";
  if (s < now.plus(Duration.fromObject({ minutes: 30 }))) return "Start at least 30 minutes from now.";
  if (e.diff(s).as("minutes") < 15) return "Make it at least 15 minutes long.";
  if (e.diff(s).as("days") > 30) return "Make it at most 30 days long.";
  return null;
}

export function overlaps(aStart: string, aEnd: string, bStart: string, bEnd: string) {
  return Date.parse(aStart) < Date.parse(bEnd) && Date.parse(bStart) < Date.parse(aEnd);
}

export function zones(): string[] {
  const all = (Intl as unknown as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.("timeZone") ?? [localZone];
  return all.filter((z) => z.includes("/"));
}
