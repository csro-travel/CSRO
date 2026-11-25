import { replacementId } from "./replacements";
import type { CounselorResolution, CustomerResolution, ResolutionBlocker, ResolutionCommand, ResolutionIntent } from "../_types/resolution";

const record = (value: unknown): value is Record<string, unknown> => typeof value === "object" && value !== null && !Array.isArray(value);
const text = (value: unknown): value is string => typeof value === "string" && value.trim().length > 0;
const integer = (value: unknown): value is number => typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
const time = (value: unknown): value is string => text(value) && /(Z|[+-]\d{2}:\d{2})$/.test(value) && Number.isFinite(Date.parse(value));
const clock = (value: unknown) => value === "REAL_TIME" || value === "SYNTHETIC_DEMO";
function date(value: unknown): value is string {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(value + "T00:00:00Z");
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}
const dates = (value: unknown): value is string[] => Array.isArray(value) && value.every(date) && new Set(value).size === value.length;
const taskId = (value: unknown): value is string => typeof value === "string" && /^TASK-[0-9]{1,19}$/.test(value);
export function resolutionBlockers(value: unknown): value is ResolutionBlocker[] {
  return Array.isArray(value) && value.every(item => record(item) && text(item.code) &&
    (item.stayDates === undefined || dates(item.stayDates)) && (item.taskId === undefined || taskId(item.taskId)));
}

export function customerResolution(value: unknown): value is CustomerResolution {
  if (!record(value) || !["OPEN", "IN_PROGRESS", "WAITING", "CLOSED"].includes(String(value.caseStatus)) || !integer(value.version) ||
      !time(value.effectiveAt) || !clock(value.clockMode) || value.isSynthetic !== true) return false;
  const checkin = value.checkin;
  if (checkin !== null && (!record(checkin) || !text(checkin.checkinConfirmationId) || !replacementId(checkin.replacementBookingId) ||
      checkin.status !== "CUSTOMER_CONFIRMED" || !time(checkin.checkinAt) || checkin.isSynthetic !== true || !clock(checkin.clockMode))) return false;
  const closure = value.closure;
  return closure === null || (record(closure) && text(closure.closureId) && closure.status === "CLOSED" && time(closure.closedAt) && closure.isSynthetic === true && clock(closure.clockMode));
}

export function counselorResolution(value: unknown): value is CounselorResolution {
  if (!customerResolution(value)) return false;
  const row = value as CustomerResolution & Record<string, unknown>;
  const coverage = row.coverage;
  if (!record(coverage) || typeof coverage.fullyCovered !== "boolean" || !dates(coverage.requiredStayDates) || !dates(coverage.coveredStayDates) || !dates(coverage.uncoveredStayDates) ||
      typeof row.canClose !== "boolean" || !resolutionBlockers(row.blockers) || !Array.isArray(row.requiredTasks)) return false;
  const closure: unknown = value.closure;
  if (closure !== null && (!record(closure) || !text(closure.reason) || !integer(closure.version))) return false;
  const ids = new Set<string>();
  return row.requiredTasks.every((task: unknown) => {
    if (!record(task) || !taskId(task.taskId) || ids.has(task.taskId) || !text(task.type) || !["TODO", "IN_PROGRESS", "DONE"].includes(String(task.status)) ||
        !(task.completionNote === null || text(task.completionNote)) || !(task.completedAt === null || time(task.completedAt)) ||
        !(task.followupOutcome === null || task.followupOutcome === "COMPLETED" || task.followupOutcome === "NOT_REQUIRED")) return false;
    ids.add(task.taskId); return true;
  });
}

function validCommand(value: unknown, caseId: string): value is ResolutionCommand {
  if (!record(value) || !record(value.body)) return false;
  const body = value.body;
  if (value.kind === "checkin") return replacementId(value.targetId) && Object.keys(body).length === 1 && body.checkedIn === true;
  if (value.kind === "closure") return value.targetId === caseId && Object.keys(body).length === 2 && text(body.reason) && body.reason.length <= 80 && integer(body.expectedVersion);
  return value.kind === "task" && taskId(value.targetId) && body.status === "DONE" && text(body.completionNote) && body.completionNote.length <= 1000 && integer(body.expectedVersion) &&
    Object.keys(body).every(key => ["status", "completionNote", "expectedVersion", "followupOutcome"].includes(key)) &&
    (body.followupOutcome === undefined || body.followupOutcome === "COMPLETED" || body.followupOutcome === "NOT_REQUIRED");
}

function storageKey(caseId: string, customer: boolean) { return `csro.resolution-intent.v1:${customer ? "customer" : "counselor"}:${caseId}`; }
export function loadResolutionIntent(caseId: string, customer: boolean): ResolutionIntent | undefined {
  const raw = sessionStorage.getItem(storageKey(caseId, customer));
  if (!raw) return;
  const value: unknown = JSON.parse(raw);
  if (!record(value) || value.version !== 1 || !text(value.key) || !/^[A-Za-z0-9._:-]{1,100}$/.test(value.key) || !validCommand(value.command, caseId) ||
      (customer ? value.command.kind !== "checkin" : value.command.kind === "checkin")) throw Error("보관된 처리 요청을 확인할 수 없습니다. 새 요청 전에 담당자에게 확인해 주세요.");
  return value as ResolutionIntent;
}
const recoveryKey = "csro.resolution-pending-cases.v1:counselor";
export function recoverableResolutionCases(): string[] {
  const value: unknown = JSON.parse(sessionStorage.getItem(recoveryKey) ?? "[]");
  if (!Array.isArray(value) || !value.every(item => typeof item === "string" && /^C-[0-9]{3,38}$/.test(item))) throw Error("보관된 사건 목록을 확인할 수 없습니다.");
  return [...new Set(value)];
}
export function saveResolutionIntent(caseId: string, customer: boolean, intent: ResolutionIntent) {
  sessionStorage.setItem(storageKey(caseId, customer), JSON.stringify(intent));
  if (!customer) sessionStorage.setItem(recoveryKey, JSON.stringify([...new Set([...recoverableResolutionCases(), caseId])]));
}
export function clearResolutionIntent(caseId: string, customer: boolean) {
  sessionStorage.removeItem(storageKey(caseId, customer));
  if (!customer) sessionStorage.setItem(recoveryKey, JSON.stringify(recoverableResolutionCases().filter(id => id !== caseId)));
}

export class ResolutionError extends Error {
  constructor(message: string, readonly outcomeUnknown = false, readonly code?: string, readonly blockers?: ResolutionBlocker[]) { super(message); this.name = "ResolutionError"; }
}

/** Writes frozen intent only; current resolution always comes from fresh API-004/011. */
export async function sendResolution(caseId: string, intent: ResolutionIntent, signal: AbortSignal) {
  const { command } = intent;
  const uncertain = "처리 결과를 확인하지 못했습니다. 새 요청을 만들지 말고 보관된 같은 요청으로 확인해 주세요.";
  const path = command.kind === "checkin" ? `/demo/replacement-bookings/${encodeURIComponent(command.targetId)}/checkin-confirmations`
    : command.kind === "task" ? `/demo/cases/${encodeURIComponent(caseId)}/tasks/${encodeURIComponent(command.targetId)}`
    : `/demo/cases/${encodeURIComponent(caseId)}/closures`;
  const response = await fetch(path, { method: command.kind === "task" ? "PATCH" : "POST",
    headers: { "Content-Type": "application/json", "Idempotency-Key": intent.key }, body: JSON.stringify(command.body), signal,
  }).catch(() => { throw new ResolutionError(uncertain, true); });
  if (response.status >= 500 || response.status === 401 || response.status === 403) throw new ResolutionError(uncertain, true);
  if (response.status !== (command.kind === "task" ? 200 : 201)) {
    const problem: unknown = await response.json().catch(() => null);
    throw new ResolutionError(record(problem) && text(problem.detail) ? problem.detail : `처리 실패 (HTTP ${response.status})`, false,
      record(problem) && text(problem.code) ? problem.code : undefined,
      record(problem) && resolutionBlockers(problem.blockers) ? problem.blockers : undefined);
  }
  const result: unknown = await response.json().catch(() => null);
  if (!record(result) || result.caseId !== caseId) throw new ResolutionError(uncertain, true);
  let valid: boolean;
  if (command.kind === "checkin") valid = text(result.checkinConfirmationId) && result.replacementBookingId === command.targetId && result.status === "CUSTOMER_CONFIRMED" && time(result.checkinAt) && result.isSynthetic === true && clock(result.clockMode);
  else if (command.kind === "task") valid = result.taskId === command.targetId && result.status === "DONE" && result.completionNote === command.body.completionNote && time(result.completedAt) && integer(result.version);
  else valid = text(result.closureId) && result.status === "CLOSED" && result.reason === command.body.reason && time(result.closedAt) && integer(result.version) && result.isSynthetic === true && clock(result.clockMode);
  if (!valid) throw new ResolutionError(uncertain, true);
}
