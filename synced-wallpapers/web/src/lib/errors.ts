// Same codes and wording as the Android app (core/AppError.kt).
const messages: Record<string, string> = {
  NOT_AUTHENTICATED: "Please sign in again.",
  NOT_MEMBER: "You're not a member of this group.",
  NOT_ADMIN: "Only the group admin can do that.",
  NOT_FOUND: "This item no longer exists.",
  INVALID_NAME: "Names must be 1–40 characters.",
  INVALID_TIME_ZONE: "That time zone isn't recognised.",
  INVALID_TIME: "Pick a start and end time.",
  LEAD_TIME_TOO_SHORT: "The start must be at least 30 minutes from now.",
  WINDOW_TOO_SHORT: "A schedule must last at least 15 minutes.",
  WINDOW_TOO_LONG: "A schedule can last at most 30 days.",
  SLOT_TAKEN: "Someone just took that time slot. Pick another time.",
  PERSONAL_CONFLICT: "This overlaps another wallpaper you've committed to.",
  ALREADY_VOTED: "You've already voted.",
  PROPOSAL_CLOSED: "Voting on this proposal has closed.",
  VOTING_CLOSED: "The voting deadline has passed.",
  IMAGE_NOT_UPLOADED: "The photo didn't finish uploading. Try again.",
  IMAGE_GONE: "The original photo is no longer available. Pick it again.",
  REMINDER_TOO_LONG: "Reminder text can be at most 60 characters.",
  GROUP_FULL: "This group is full.",
  INVALID_EMAIL: "That email address doesn't look right.",
  ALREADY_MEMBER: "That person is already in the group.",
  INVITE_NOT_FOUND: "That invite code doesn't exist.",
  INVITE_USED: "That invite has already been used.",
  INVITE_EXPIRED: "That invite has expired. Ask for a new one.",
  INVITE_FOR_SOMEONE_ELSE: "That invite was sent to a different Google account.",
  REQUEST_CLOSED: "This join request is closed.",
  ACTION_ALREADY_PENDING: "A request of this kind is already waiting for approval.",
  ACTION_CLOSED: "This request is closed.",
  NOT_A_VOTER: "You don't vote on this request.",
  INVALID_MEMBER_LIMIT: "The limit must be between the current member count and 20.",
  INVALID_MEMBER: "Pick another member of the group.",
  NO_OTHER_CANDIDATE: "There's nobody else to pass the admin role to.",
  NOTHING_TO_DECLINE: "There's nothing to decline.",
  OWN_PROPOSAL: "You can't suggest a time for your own proposal.",
};

export class AppError extends Error {
  constructor(public userMessage: string, public code?: string, public detail?: string) { super(userMessage); }
}

export function toAppError(e: unknown): AppError {
  if (e instanceof AppError) return e;
  const anyE = e as { message?: string; code?: string; details?: string; status?: number; name?: string };
  const code = (anyE?.message ?? "").trim();
  if (messages[code]) return new AppError(messages[code], code, anyE.details);
  if (anyE?.status === 401 || anyE?.code === "PGRST301") return new AppError("Your session expired. Please sign in again.");
  if ([502, 503, 504, 520, 522, 540, 544].includes(anyE?.status ?? 0)) return new AppError("Server is waking up, try again in a moment.");
  if (anyE?.name === "TypeError" || /fetch|network/i.test(code)) return new AppError("You're offline or the connection dropped. Try again.");
  console.warn("Unexpected error", e);
  return new AppError("Something went wrong. Please try again.");
}

/** Throws a friendly AppError for a supabase-js { error } result. */
export function check<T>(r: { data: T; error: unknown }): T {
  if (r.error) throw toAppError(r.error);
  return r.data;
}
