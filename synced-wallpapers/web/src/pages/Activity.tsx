import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import * as api from "../lib/api";
import { useLoad } from "../lib/hooks";
import { Card, Empty, ErrorState, Spinner } from "../components/ui";
import { eventText } from "../lib/events";
import { shortDateTime } from "../lib/time";

export default function Activity() {
  const nav = useNavigate();
  const d = useLoad(api.inbox, [], ["notification_outbox"]);
  useEffect(() => { if (d.data?.some((o) => !o.read_at)) api.markAllRead().catch(() => undefined); }, [d.data]);
  if (d.loading && !d.data) return <Spinner />;
  if (d.error) return <ErrorState error={d.error} onRetry={d.reload} />;
  const items = d.data!.map((o) => ({ o, t: eventText(o.type, o.payload) })).filter((x) => x.t);
  return (
    <div className="stack" style={{ maxWidth: 720 }}>
      <h1>Activity</h1>
      {items.length === 0 ? <Empty icon="📥" title="Nothing yet" body="Proposals, votes, results and group changes will appear here." /> :
        items.map(({ o, t }) => (
          <Card key={o.id} outlined={!!o.read_at} onClick={o.payload.group_id ? () => nav(`/groups/${o.payload.group_id}`) : undefined}>
            <b style={{ fontWeight: o.read_at ? 600 : 800 }}>{t!.title}</b><p>{t!.body}</p><span className="muted small">{shortDateTime(o.created_at)}</span>
          </Card>))}
    </div>
  );
}
