import { groupAndLocal } from "./time";

const adminLabel = (t?: string | null) => ({
  RENAME: "rename the group", TIMEZONE: "change the time zone", MEMBER_LIMIT: "change the member limit",
  REMOVE_MEMBER: "remove a member", TRANSFER_ADMIN: "hand over admin", DELETE_GROUP: "delete the group",
} as Record<string, string>)[t ?? ""] ?? "change the group";

/** Activity text for an outbox event (same wording as the Android notifications). */
export function eventText(type: string, p: Record<string, string | null>): { title: string; body: string } | null {
  const group = p.group_name || "Your group";
  const when = p.start_at && p.time_zone ? ` · ${groupAndLocal(p.start_at, p.time_zone)}` : "";
  const proposer = p.proposer_name || "Someone";
  switch (type) {
    case "NEW_PROPOSAL": return { title: `${group}: new wallpaper proposal`, body: `${proposer} proposed a wallpaper${when}. Everyone must approve.` };
    case "VOTE_REMINDER": return { title: `${group}: vote closes in an hour`, body: `You haven't voted on a wallpaper${when} yet.` };
    case "PROPOSAL_APPROVED": return { title: `${group}: approved`, body: `Everyone approved the wallpaper${when}.` };
    case "PROPOSAL_REJECTED": return { title: `${group}: not approved`, body: p.reason ? `Not approved. Reason: “${p.reason}”` : "A wallpaper proposal wasn't approved." };
    case "PROPOSAL_EXPIRED": return { title: `${group}: expired`, body: "Not everyone voted in time, so the proposal expired." };
    case "PROPOSAL_CANCELLED": return { title: `${group}: schedule cancelled`, body: `A wallpaper${when} was cancelled.` };
    case "SCHEDULE_SKIPPED": return { title: group, body: `A member skipped a wallpaper${when} on their phone.` };
    case "TIME_SUGGESTION": return { title: `${group}: another time suggested`, body: "A member suggested another time for your proposal." };
    case "JOIN_REQUEST": return { title: `${group}: join request`, body: `${p.joiner_name || "Someone"} wants to join. Everyone must approve.` };
    case "JOIN_RESULT": return p.result === "APPROVED"
      ? { title: `You joined ${group}`, body: "Everyone approved. Upcoming wallpapers will appear on your phone." }
      : { title: `${group}: join not approved`, body: p.reason ? `Reason: “${p.reason}”` : "Your request to join wasn't approved." };
    case "JOIN_TERMS_CHANGED": return { title: `${group}: please review again`, body: "A new schedule was approved. Accept the upcoming schedules again to join." };
    case "MEMBER_JOINED": return { title: group, body: `${p.member_name || "A new member"} joined.` };
    case "MEMBER_LEFT": return { title: group, body: `${p.member_name || "A member"} left the group.` };
    case "MEMBER_REMOVED": return { title: group, body: `${p.member_name || "A member"} was removed.` };
    case "REMOVED_FROM_GROUP": return { title: group, body: "You were removed from this group." };
    case "GROUP_DELETED": return { title: group, body: "The group was deleted." };
    case "ADMIN_ACTION_REQUEST": return { title: `${group}: admin request`, body: `The admin wants to ${adminLabel(p.action_type)}. Everyone must approve.` };
    case "ADMIN_ACTION_RESULT": {
      const r = p.result === "APPROVED" ? "Approved" : p.result === "REJECTED" ? "Not approved" : p.result === "EXPIRED" ? "Expired" : "Cancelled";
      return { title: group, body: `${r}: ${adminLabel(p.action_type)}.` };
    }
    case "ADMIN_CHANGED": return { title: group, body: `${p.admin_name || "Another member"} is now the admin.` };
    case "YOU_ARE_ADMIN": return { title: `You're now the admin of ${group}`, body: String(p.can_decline) === "true" ? "The previous admin left. You can decline to pass it on." : "The group approved the handover." };
    default: return null;
  }
}
