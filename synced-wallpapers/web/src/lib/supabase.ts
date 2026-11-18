import { createClient } from "@supabase/supabase-js";

export const SUPABASE_URL = import.meta.env.VITE_SUPABASE_URL as string | undefined;
const KEY = import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined;
export const configured = Boolean(SUPABASE_URL && KEY);

// Only the publishable/anon key is ever in the browser. RLS + RPCs enforce the rules.
export const supabase = createClient(SUPABASE_URL || "https://not-configured.invalid", KEY || "not-configured", {
  auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: true, flowType: "pkce" },
});

export const IMAGE_BUCKET = "proposal-images";
export const MAX_MEMBERS = 20;
