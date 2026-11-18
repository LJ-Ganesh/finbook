import { useNavigate } from "react-router-dom";
import { myGroups, myJoinRequests, pendingCounts, myCommitments } from "../lib/api";
import { useLoad } from "../lib/hooks";
import { Card, Empty, ErrorState, Spinner } from "../components/ui";
import { remaining, windowText, localZone } from "../lib/time";

export default function Home() {
  const nav = useNavigate();
  const data = useLoad(async () => {
    const [groups, counts, joins, commitments] = await Promise.all([myGroups(), pendingCounts(), myJoinRequests(), myCommitments()]);
    return { groups, counts, joins, commitments };
  }, [], ["groups", "group_members", "proposals", "admin_actions", "join_requests"]);

  if (data.loading && !data.data) return <Spinner />;
  if (data.error) return <ErrorState error={data.error} onRetry={data.reload} />;
  const { groups, counts, joins, commitments } = data.data!;
  const now = Date.now();
  const live = commitments.find((c) => c.status !== "PENDING" && Date.parse(c.start_at) <= now);
  const next = commitments.find((c) => c.status !== "PENDING" && Date.parse(c.start_at) > now);

  return (
    <div className="stack">
      <div className="row spread wrap">
        <div><h1>Home</h1><p className="muted">Your groups and what's coming up.</p></div>
        <div className="row"><button className="btn ghost" onClick={() => nav("/join")}>Join with code</button><button className="btn" onClick={() => nav("/groups/new")}>New group</button></div>
      </div>

      <div className="cards" style={{ marginTop: 12 }}>
        <Card accent={!!live}>
          <span className="muted small">Now</span>
          {live ? <><h3>{live.group_name}</h3><p>Live on phones · {remaining(live.end_at)} left</p></> : <h3>Your own wallpaper</h3>}
        </Card>
        <Card>
          <span className="muted small">Next</span>
          {next ? <><h3>{next.group_name}</h3><p className="small">{windowText(next.start_at, next.end_at, localZone)}</p><p className="muted small">Starts in {remaining(next.start_at)}</p></>
            : <p className="muted">Nothing scheduled.</p>}
        </Card>
      </div>

      {joins.length > 0 && <>
        <h2>Joining</h2>
        <div className="cards">{joins.map((j) => (
          <Card key={j.request_id} outlined onClick={() => nav(`/join-requests/${j.request_id}`)}>
            <h3>{j.group_name}</h3><p className="muted small">{j.terms_accepted ? "Waiting for every member to approve" : "Review the upcoming wallpapers to continue"}</p>
          </Card>))}</div>
      </>}

      <h2>Groups</h2>
      {groups.length === 0 ? (
        <Empty icon="👥" title="No groups yet" body="Create a group and invite the people you trust, or join one with an invite code. A group of one works too, for your own routine reminders.">
          <div className="row"><button className="btn ghost" onClick={() => nav("/join")}>Join with code</button><button className="btn" onClick={() => nav("/groups/new")}>Create group</button></div>
        </Empty>
      ) : (
        <div className="cards">{groups.map((g) => {
          const c = counts.find((x) => x.group_id === g.id);
          const n = c ? c.proposals + c.admin_actions + c.join_requests : 0;
          return (
            <Card key={g.id} onClick={() => nav(`/groups/${g.id}`)}>
              <div className="row spread"><h3>{g.name}</h3>{n > 0 && <span className="badge" title="Waiting for your vote">{n}</span>}</div>
              <p className="muted small">{g.time_zone}</p>
            </Card>);
        })}</div>
      )}
    </div>
  );
}
