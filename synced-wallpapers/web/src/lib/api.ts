import { supabase, IMAGE_BUCKET } from "./supabase";
import { check, AppError } from "./errors";

// Shapes match the RPCs in supabase/migrations (same as the Android DTOs).
export type Profile = { id: string; email: string | null; name: string; photo_url: string | null; phone_optional: string | null };
export type Group = { id: string; name: string; time_zone: string; max_members: number; admin_id: string; admin_handover_pending: boolean; created_at: string };
export type MemberRow = { group_id: string; user_id: string; joined_at: string; delivery_mode: "AUTO" | "TAP" };
export type Member = MemberRow & { profile?: Profile; isAdmin: boolean; name: string };
export type Proposal = {
  id: string; group_id: string; proposer_id: string | null; start_at: string; end_at: string; target: "HOME" | "LOCK" | "BOTH";
  image_path: string; image_ready: boolean; focal_x: number; focal_y: number; reminder_text: string | null; reminder_style: number;
  status: string; vote_deadline: string; cancel_reason: string | null; created_at: string;
};
export type AdminAction = { id: string; group_id: string; type: AdminType; payload: Record<string, unknown>; created_by: string; status: string; result_note: string | null; deadline: string; created_at: string };
export type AdminType = "RENAME" | "TIMEZONE" | "MEMBER_LIMIT" | "REMOVE_MEMBER" | "TRANSFER_ADMIN" | "DELETE_GROUP";
export type VoteSummary = { proposal_id: string; status: string; required: number; approvals: number; rejections: number; my_vote: string | null; reasons: string[]; skipped_count: number };
export type AdminSummary = { action_id: string; status: string; deadline?: string; required: number; approvals: number; rejections: number; can_vote: boolean; my_vote: string | null; reasons: string[] };
export type Commitment = { proposal_id: string; group_id: string; group_name: string; start_at: string; end_at: string; status: string };
export type Slot = { proposal_id: string; start_at: string; end_at: string; status: string };
export type PendingCounts = { group_id: string; proposals: number; admin_actions: number; join_requests: number };
export type Invite = { invite_id: string; code: string; expires_at: string; email: string | null; join_request_id: string | null };
export type JoinRequestRow = { request_id: string; user_id: string; name: string; email: string | null; photo_url: string | null; created_at: string; terms_accepted: boolean; required: number; approvals: number; my_vote: string | null };
export type MyJoinRequest = { request_id: string; group_id: string; group_name: string; status: string; terms_accepted: boolean; created_at: string };
export type JoinSchedule = { proposal_id: string; start_at: string; end_at: string; target: string; image_path: string; focal_x: number; focal_y: number; reminder_text: string | null; reminder_style: number; conflicts: Commitment[] };
export type JoinPreview = { request_id: string; status: string; terms_accepted_at: string | null; group_id: string; group_name: string; time_zone: string; member_count: number; required: number; approvals: number; schedules: JoinSchedule[] };
export type DeliverySummary = { proposal_id: string; members: number; skipped: number; ready: number; live: number; people: { user_id: string; name: string; state: string }[] };
export type Outbox = { id: number; type: string; payload: Record<string, string | null>; created_at: string; read_at: string | null };
export type Suggestion = { id: string; proposal_id: string; suggested_start: string; suggested_end: string };

export const ADMIN_LABELS: Record<AdminType, string> = {
  RENAME: "Rename the group", TIMEZONE: "Change the time zone", MEMBER_LIMIT: "Change the member limit",
  REMOVE_MEMBER: "Remove a member", TRANSFER_ADMIN: "Hand over admin", DELETE_GROUP: "Delete the group",
};

async function rpc<T>(fn: string, args: Record<string, unknown> = {}): Promise<T> {
  return check(await supabase.rpc(fn, args)) as T;
}

export async function uid(): Promise<string> {
  const { data } = await supabase.auth.getSession();
  const id = data.session?.user.id;
  if (!id) throw new AppError("Your session expired. Please sign in again.");
  return id;
}

// Profile ---------------------------------------------------------------------------
export async function me(): Promise<Profile> {
  return check(await supabase.from("profiles").select("*").eq("id", await uid()).single()) as Profile;
}
export async function updateProfile(fields: { name?: string; phone_optional?: string | null }) {
  check(await supabase.from("profiles").update(fields).eq("id", await uid()));
}

// Groups ----------------------------------------------------------------------------
export async function myGroups(): Promise<Group[]> {
  return check(await supabase.from("groups").select("*").order("created_at")) as Group[];
}
export const pendingCounts = () => rpc<PendingCounts[]>("my_pending_counts");
export async function groupDetail(groupId: string) {
  const group = check(await supabase.from("groups").select("*").eq("id", groupId).single()) as Group;
  const rows = check(await supabase.from("group_members").select("*").eq("group_id", groupId).order("joined_at")) as MemberRow[];
  const ids = rows.map((r) => r.user_id);
  const profiles = ids.length ? (check(await supabase.from("profiles").select("*").in("id", ids)) as Profile[]) : [];
  const me = await uid();
  const members: Member[] = rows.map((r) => {
    const profile = profiles.find((p) => p.id === r.user_id);
    return { ...r, profile, isAdmin: r.user_id === group.admin_id, name: profile?.name || "Member" };
  });
  return { group, members, me: members.find((m) => m.user_id === me), myId: me };
}
export type GroupDetail = Awaited<ReturnType<typeof groupDetail>>;
export const createGroup = (name: string, zone: string) => rpc<string>("create_group", { p_name: name.trim(), p_time_zone: zone });
export const setDeliveryMode = (groupId: string, mode: string) => rpc<void>("set_delivery_mode", { p_group_id: groupId, p_mode: mode });
export const createInvite = (groupId: string, email?: string) => rpc<Invite>("create_invite", { p_group_id: groupId, p_email: email?.trim() || null });
export const normalizeCode = (s: string) => s.trim().split("/").pop()!.split("code=").pop()!.toUpperCase().replace(/[^A-Z0-9]/g, "");
export const redeemInvite = (code: string) => rpc<string>("redeem_invite", { p_code: normalizeCode(code) });
export const joinPreview = (id: string) => rpc<JoinPreview>("join_request_preview", { p_request_id: id });
export const acceptJoinTerms = (id: string, accept: boolean) => rpc<{ status: string }>("accept_join_terms", { p_request_id: id, p_accept: accept });
export const cancelJoinRequest = (id: string) => rpc<void>("cancel_join_request", { p_request_id: id });
export const voteJoin = (id: string, approve: boolean, reason?: string | null) => rpc("vote_join", { p_request_id: id, p_decision: approve ? "APPROVE" : "REJECT", p_reason: reason ?? null });
export const joinRequests = (groupId: string) => rpc<JoinRequestRow[]>("group_join_requests", { p_group_id: groupId });
export const myJoinRequests = () => rpc<MyJoinRequest[]>("my_join_requests");
export const leaveGroup = (groupId: string) => rpc<void>("leave_group", { p_group_id: groupId });

// Admin -----------------------------------------------------------------------------
export async function adminActions(groupId: string): Promise<AdminAction[]> {
  return check(await supabase.from("admin_actions").select("*").eq("group_id", groupId).order("created_at", { ascending: false }).limit(30)) as AdminAction[];
}
export async function adminAction(id: string): Promise<AdminAction> {
  return check(await supabase.from("admin_actions").select("*").eq("id", id).single()) as AdminAction;
}
export const createAdminAction = (groupId: string, type: AdminType, payload: Record<string, unknown>) =>
  rpc<string>("create_admin_action", { p_group_id: groupId, p_type: type, p_payload: payload });
export const castAdminVote = (id: string, approve: boolean, reason?: string | null) => rpc<AdminSummary>("cast_admin_vote", { p_action_id: id, p_decision: approve ? "APPROVE" : "REJECT", p_reason: reason ?? null });
export const cancelAdminAction = (id: string) => rpc<void>("cancel_admin_action", { p_action_id: id });
export const adminSummary = (id: string) => rpc<AdminSummary>("admin_action_summary", { p_action_id: id });
export const declineAdmin = (groupId: string) => rpc<void>("decline_admin", { p_group_id: groupId });
export const acceptAdmin = (groupId: string) => rpc<void>("accept_admin", { p_group_id: groupId });

// Proposals -------------------------------------------------------------------------
export async function groupProposals(groupId: string): Promise<Proposal[]> {
  return check(await supabase.from("proposals").select("*").eq("group_id", groupId)
    .in("status", ["PENDING", "APPROVED", "LIVE", "REJECTED", "EXPIRED", "CANCELLED"])
    .gt("end_at", new Date().toISOString()).order("start_at")) as Proposal[];
}
export async function proposal(id: string): Promise<Proposal> {
  return check(await supabase.from("proposals").select("*").eq("id", id).single()) as Proposal;
}
export const voteSummary = (id: string) => rpc<VoteSummary>("proposal_vote_summary", { p_proposal_id: id });
export const proposalConflicts = (id: string) => rpc<Commitment[]>("proposal_conflicts", { p_proposal_id: id });
export const takenSlots = (groupId: string, from: string, to: string) => rpc<Slot[]>("group_taken_slots", { p_group_id: groupId, p_from: from, p_to: to });
export const myCommitments = () => rpc<Commitment[]>("my_upcoming_commitments");
export const castVote = (id: string, approve: boolean, reason?: string | null) => rpc<VoteSummary>("cast_vote", { p_proposal_id: id, p_decision: approve ? "APPROVE" : "REJECT", p_reason: reason ?? null });
export const cancelProposal = (id: string) => rpc<void>("cancel_proposal", { p_proposal_id: id });
export const suggestTime = (id: string, start: string, end: string) => rpc<string>("suggest_time", { p_proposal_id: id, p_start: start, p_end: end });
export async function suggestions(id: string): Promise<Suggestion[]> {
  return check(await supabase.from("time_suggestions").select("*").eq("proposal_id", id).order("at", { ascending: false })) as Suggestion[];
}
export const deliverySummary = (id: string) => rpc<DeliverySummary>("delivery_summary", { p_proposal_id: id });

export type NewProposal = {
  groupId: string; start: string; end: string; target: "HOME" | "LOCK" | "BOTH"; focalX: number; focalY: number;
  reminder: string | null; style: number; jpeg: Blob | null; replaces?: string | null;
};

/** Reserve the slot → upload to the reserved path → confirm (cancel on upload failure). */
export async function createProposal(p: NewProposal): Promise<string> {
  const created = await rpc<{ proposal_id: string; image_path: string; image_ready: boolean }>("create_proposal", {
    p_group_id: p.groupId, p_start: p.start, p_end: p.end, p_target: p.target, p_focal_x: p.focalX, p_focal_y: p.focalY,
    p_reminder_text: p.reminder?.trim() || null, p_reminder_style: p.style, p_replaces: p.replaces ?? null,
  });
  if (created.image_ready) return created.proposal_id;
  if (!p.jpeg) throw new AppError("Pick a photo first.");
  try {
    check(await supabase.storage.from(IMAGE_BUCKET).upload(created.image_path, p.jpeg, { contentType: "image/jpeg", upsert: false }));
    await rpc<void>("confirm_proposal_image", { p_proposal_id: created.proposal_id });
  } catch (e) {
    await cancelProposal(created.proposal_id).catch(() => undefined);
    throw e;
  }
  return created.proposal_id;
}

/** 10-minute signed URL; images are never public. */
export async function signedUrl(path: string): Promise<string> {
  const r = check(await supabase.storage.from(IMAGE_BUCKET).createSignedUrl(path, 600)) as { signedUrl: string };
  return r.signedUrl;
}

// Activity --------------------------------------------------------------------------
export async function inbox(): Promise<Outbox[]> {
  return check(await supabase.from("notification_outbox").select("id,type,payload,created_at,read_at").eq("user_id", await uid())
    .order("created_at", { ascending: false }).limit(100)) as Outbox[];
}
export async function markAllRead() {
  check(await supabase.from("notification_outbox").update({ read_at: new Date().toISOString() }).eq("user_id", await uid()).is("read_at", null));
}

export async function deleteAccount() {
  const { error } = await supabase.functions.invoke("delete-account", { method: "POST" });
  if (error) throw new AppError("Couldn't delete the account. Try again.");
}
