import { useState } from "react";
import { useParams } from "react-router-dom";
import { supabase } from "../lib/supabase";
import { Banner, Mark } from "../components/ui";

export default function SignIn() {
  const { code } = useParams();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  async function signIn() {
    setBusy(true); setError(null);
    // Web uses Supabase's Google OAuth redirect (the phone app uses a Google ID token).
    const { error } = await supabase.auth.signInWithOAuth({
      provider: "google",
      options: { redirectTo: window.location.origin + (code ? `/join/${code}` : "/"), queryParams: { prompt: "select_account" } },
    });
    if (error) { setError("Couldn't start Google sign-in. Try again."); setBusy(false); }
  }
  return (
    <div className="signin">
      <div className="box">
        <div className="row"><Mark size={44} /><span style={{ fontSize: 30, fontWeight: 700 }}>Livrun</span></div>
        <h1>One photo, everyone's phone</h1>
        <p className="muted">Small trusted groups propose a photo and a time. If everyone approves, it becomes the wallpaper on every member's phone for that window, then each person's own wallpaper comes back.</p>
        <ul className="muted" style={{ margin: 0, paddingLeft: 18, lineHeight: 1.8 }}>
          <li>Everyone must agree. One “no” is enough, and nobody sees who said it.</li>
          <li>Skip any wallpaper on your phone at any time.</li>
          <li>Photos are deleted after the window. Anyone can screenshot their own screen.</li>
        </ul>
        {code && <Banner>Sign in to continue joining with code <b>{code}</b>.</Banner>}
        {error && <Banner error>{error}</Banner>}
        <button className="btn big" onClick={signIn} disabled={busy}>{busy ? "Opening Google…" : "Continue with Google"}</button>
        <p className="muted small">On desktop you can organise groups, propose and vote. The wallpaper itself changes on phones with the Livrun Android app.</p>
      </div>
    </div>
  );
}
