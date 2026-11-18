import { useCallback, useEffect, useRef, useState } from "react";
import { supabase } from "./supabase";
import { AppError, toAppError } from "./errors";

type Listener = (table: string) => void;
const listeners = new Set<Listener>();
let channelStarted = false;

/** One Realtime channel for the whole app; screens re-read through the normal API (RLS applies). */
export function startRealtime() {
  if (channelStarted) return;
  channelStarted = true;
  const ch = supabase.channel("livrun-web");
  for (const table of ["groups", "group_members", "proposals", "admin_actions", "join_requests", "notification_outbox"]) {
    ch.on("postgres_changes", { event: "*", schema: "public", table }, () => listeners.forEach((l) => l(table)));
  }
  ch.subscribe();
}

export function stopRealtime() {
  channelStarted = false;
  supabase.removeAllChannels();
}

export type Loaded<T> = { data?: T; error?: AppError; loading: boolean; reload: () => void };

/** Loads data, keeps the last good value on refresh errors, and refreshes on Realtime changes. */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[], tables: string[] = []): Loaded<T> {
  const [state, setState] = useState<{ data?: T; error?: AppError; loading: boolean }>({ loading: true });
  const fnRef = useRef(fn); fnRef.current = fn;
  const seq = useRef(0);
  const reload = useCallback(() => {
    const n = ++seq.current;
    fnRef.current().then(
      (data) => { if (n === seq.current) setState({ data, loading: false }); },
      (e) => { if (n === seq.current) setState((s) => ({ data: s.data, error: s.data ? undefined : toAppError(e), loading: false })); },
    );
  }, []);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { setState({ loading: true }); reload(); }, deps);
  useEffect(() => {
    if (!tables.length) return;
    const l: Listener = (t) => { if (tables.includes(t)) reload(); };
    listeners.add(l);
    return () => { listeners.delete(l); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reload, tables.join()]);
  return { ...state, reload };
}

/** Runs an action, returning a user-facing error message (or null). */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const run = useCallback(async (fn: () => Promise<unknown>, after?: () => void) => {
    setBusy(true); setMessage(null);
    try { await fn(); after?.(); return true; } catch (e) { setMessage(toAppError(e).userMessage); return false; } finally { setBusy(false); }
  }, []);
  return { busy, message, setMessage, run };
}
