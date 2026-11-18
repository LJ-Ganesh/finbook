import { useEffect, useState } from "react";
import { NavLink, Navigate, Route, Routes } from "react-router-dom";
import type { Session } from "@supabase/supabase-js";
import { configured, supabase } from "./lib/supabase";
import { startRealtime, stopRealtime, useLoad } from "./lib/hooks";
import { pendingCounts } from "./lib/api";
import { Mark, Spinner } from "./components/ui";
import SignIn from "./pages/SignIn";
import Home from "./pages/Home";
import GroupPage from "./pages/Group";
import GroupSettings from "./pages/GroupSettings";
import { CreateGroup, Invite } from "./pages/GroupForms";
import Propose from "./pages/Propose";
import ProposalPage from "./pages/Proposal";
import { AdminActionPage, NewAdminAction } from "./pages/Admin";
import { JoinCode, JoinReview } from "./pages/Join";
import Activity from "./pages/Activity";
import Settings from "./pages/Settings";

export default function App() {
  const [session, setSession] = useState<Session | null | undefined>(undefined);
  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => setSession(data.session));
    const { data } = supabase.auth.onAuthStateChange((_e, s) => setSession(s));
    return () => data.subscription.unsubscribe();
  }, []);
  useEffect(() => { if (session) startRealtime(); else if (session === null) stopRealtime(); }, [session]);

  if (!configured) return <div className="signin"><div className="box"><h1>Not configured</h1><p className="muted">Set VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY (see .env.example).</p></div></div>;
  if (session === undefined) return <Spinner />;
  if (!session) return <Routes><Route path="/join/:code" element={<SignIn />} /><Route path="*" element={<SignIn />} /></Routes>;
  return <Shell />;
}

function Shell() {
  const counts = useLoad(pendingCounts, [], ["proposals", "admin_actions", "join_requests", "group_members"]);
  const pending = (counts.data ?? []).reduce((n, c) => n + c.proposals + c.admin_actions + c.join_requests, 0);
  return (
    <div className="shell">
      <nav className="side" aria-label="Main">
        <div className="brand"><Mark size={30} />Livrun</div>
        <NavLink to="/" end className={({ isActive }) => `nav ${isActive ? "active" : ""}`}>Home {pending > 0 && <span className="badge">{pending}</span>}</NavLink>
        <NavLink to="/activity" className={({ isActive }) => `nav ${isActive ? "active" : ""}`}>Activity</NavLink>
        <NavLink to="/settings" className={({ isActive }) => `nav ${isActive ? "active" : ""}`}>Settings</NavLink>
        <div className="grow" />
        <p className="muted small" style={{ padding: "0 12px" }}>Wallpapers change on members' phones. Use the Android app to receive them.</p>
      </nav>
      <main className="main">
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/groups/new" element={<CreateGroup />} />
          <Route path="/groups/:id" element={<GroupPage />} />
          <Route path="/groups/:id/settings" element={<GroupSettings />} />
          <Route path="/groups/:id/invite" element={<Invite />} />
          <Route path="/groups/:id/propose" element={<Propose />} />
          <Route path="/groups/:id/admin/new/:type" element={<NewAdminAction />} />
          <Route path="/proposals/:id" element={<ProposalPage />} />
          <Route path="/admin-actions/:id" element={<AdminActionPage />} />
          <Route path="/join" element={<JoinCode />} />
          <Route path="/join/:code" element={<JoinCode />} />
          <Route path="/join-requests/:id" element={<JoinReview />} />
          <Route path="/activity" element={<Activity />} />
          <Route path="/settings" element={<Settings />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  );
}
