export type ClockMode = "REAL_TIME" | "SYNTHETIC_DEMO";
export type CheckinSummary = {
  checkinConfirmationId: string;
  replacementBookingId: string;
  status: "CUSTOMER_CONFIRMED";
  checkinAt: string;
  isSynthetic: true;
  clockMode: ClockMode;
};
export type ClosureSummary = {
  closureId: string;
  status: "CLOSED";
  closedAt: string;
  isSynthetic: true;
  clockMode: ClockMode;
};
export type CustomerResolution = {
  caseStatus: "OPEN" | "IN_PROGRESS" | "WAITING" | "CLOSED";
  version: number;
  checkin: CheckinSummary | null;
  closure: ClosureSummary | null;
  effectiveAt: string;
  clockMode: ClockMode;
  isSynthetic: true;
};
export type FollowupOutcome = "COMPLETED" | "NOT_REQUIRED";
export type ResolutionTask = {
  taskId: string;
  type: string;
  status: "TODO" | "IN_PROGRESS" | "DONE";
  completionNote: string | null;
  completedAt: string | null;
  followupOutcome: FollowupOutcome | null;
};
export type ResolutionBlocker = { code: string; stayDates?: string[]; taskId?: string };
export type CounselorResolution = Omit<CustomerResolution, "closure"> & {
  closure: (ClosureSummary & { reason: string; version: number }) | null;
  coverage: { fullyCovered: boolean; requiredStayDates: string[]; coveredStayDates: string[]; uncoveredStayDates: string[] };
  requiredTasks: ResolutionTask[];
  canClose: boolean;
  blockers: ResolutionBlocker[];
};
export type ResolutionCommand =
  | { kind: "checkin"; targetId: string; body: { checkedIn: true } }
  | { kind: "task"; targetId: string; body: { status: "DONE"; completionNote: string; expectedVersion: number; followupOutcome?: FollowupOutcome } }
  | { kind: "closure"; targetId: string; body: { reason: string; expectedVersion: number } };
export type ResolutionIntent = { version: 1; key: string; command: ResolutionCommand };
