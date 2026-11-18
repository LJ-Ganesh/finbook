import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import * as api from "../lib/api";
import { useAction, useLoad } from "../lib/hooks";
import { Banner, Card, Confirm, ErrorState, Spinner } from "../components/ui";

export default function GroupSettings() {
  const { id = "" } = useParams();
  const nav = useNavigate();
  const act = useAction();
  const [leaving, setLeaving] = useState(false);
  const d = useLoad(async () => ({ detail: await api.groupDetail(id), actions: (await api.adminActions(id)).filter((a) => a.status === "PENDING") }), [id], ["groups", "group_members", "admin_actions"]);
  if (d.loading && !d.data) return <Spinner />;
  if (d.error) return <ErrorState error={d.error} onRetry={d.reload} />;
  const { detail, actions } = d.data!;
  const g = detail.group, admin = detail.me?.isAdmin;
  return (
    <div className="stack" style={{ maxWidth: 640 }}>
      <Link to={`/groups/${id}`} className="muted small">← {g.name}</Link>
      <h1>Group settings</h1>
      {act.message && <Banner error>{act.message}</Banner>}
      <Card outlined><div className="list">
        <div className="kv"><span>Name</span><span>{g.name}</span></div>
        <div className="kv"><span>Time zone</span><span>{g.time_zone}</span></div>
        <div className="kv"><span>Member limit</span><span>{detail.members.length} of {g.max_members}</span></div>
        <div className="kv"><span>Admin</span><span>{detail.members.find((m) => m.isAdmin)?.name}</span></div>
      </div></Card>
      {admin ? <>
        <h2>Invite</h2>
        <Card onClick={() => nav(`/groups/${id}/invite`)}><h3>Invite someone</h3><p className="muted small">By code, link or Google email. Joining still needs every member's approval.</p></Card>
        <h2>Manage group</h2>
        <Card outlined>
          <p className="muted small">Each change takes effect only after every other member approves.</p>
          <div className="list">{(Object.keys(api.ADMIN_LABELS) as api.AdminType[]).map((t) => {
            const pending = actions.find((a) => a.type === t);
            return <div key={t} className="kv" style={{ cursor: "pointer" }} onClick={() => nav(pending ? `/admin-actions/${pending.id}` : `/groups/${id}/admin/new/${t}`)}>
              <span style={{ color: "var(--ink)" }}>{api.ADMIN_LABELS[t]}</span><span className="muted small">{pending ? "Waiting for approval" : "›"}</span></div>;
          })}</div>
        </Card>
      </> : <Banner>Only the admin can rename the group, change its time zone or member limit, remove members or invite. Their requests appear as approval cards.</Banner>}
      <h2>Leave</h2>
      <button className="btn ghost" style={{ color: "var(--danger)" }} onClick={() => setLeaving(true)}>Leave group</button>
      <Confirm open={leaving} onClose={() => setLeaving(false)} title={`Leave ${g.name}?`} confirm="Leave"
        body={`You leave right away. Your proposals and schedules in this group are cancelled for everyone, and its wallpapers are removed from your phone.${admin ? " Admin passes to the longest-standing member." : ""}`}
        onConfirm={() => act.run(() => api.leaveGroup(id), () => nav("/"))} />
    </div>
  );
}
