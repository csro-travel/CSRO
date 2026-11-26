export type CloseBlocker = { code: string; stayDates?: string[]; tasks?: { taskId: string; type: string }[] };

const record = (value: unknown): value is Record<string, unknown> => typeof value === "object" && value !== null && !Array.isArray(value);
const text = (value: unknown): value is string => typeof value === "string" && value.trim().length > 0;

export class ClosureError extends Error {
  constructor(message: string, readonly outcomeUnknown = false, readonly code?: string, readonly blockers: CloseBlocker[] = []) {
    super(message); this.name = "ClosureError";
  }
}

async function send(path: string, method: "POST" | "PATCH", body: unknown, expected: number, key?: string, signal?: AbortSignal) {
  const uncertain = "처리 결과를 확인하지 못했습니다. 같은 요청으로 다시 확인해 주세요.";
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (key) headers["Idempotency-Key"] = key;
  const response = await fetch(path, { method, headers, body: JSON.stringify(body), signal })
    .catch(() => { throw new ClosureError(uncertain, true); });
  if (response.status >= 500) throw new ClosureError(uncertain, true);
  const value: unknown = await response.json().catch(() => null);
  if (response.status !== expected) {
    const blockers = record(value) && Array.isArray(value.blockers) ? value.blockers.filter(item => record(item) && text(item.code)) as CloseBlocker[] : [];
    throw new ClosureError(record(value) && text(value.detail) ? value.detail : `처리 실패 (HTTP ${response.status})`, false,
      record(value) && text(value.code) ? value.code : undefined, blockers);
  }
  if (!record(value)) throw new ClosureError(uncertain, true);
  return value;
}

/** API-017 고객 입실 확인 (`POST /api/v1/replacement-bookings/{id}/checkin-confirmations`). */
export function confirmCheckin(replacementBookingId: string, key: string, signal?: AbortSignal) {
  return send(`/demo/replacement-bookings/${encodeURIComponent(replacementBookingId)}/checkin-confirmations`, "POST", {}, 201, key, signal);
}

/** API-008 필수 업무 완료 (`PATCH /api/v1/cases/{caseId}/tasks/{taskId}`). */
export function completeTask(caseId: string, taskId: string, completionNote: string, signal?: AbortSignal) {
  return send(`/demo/cases/${encodeURIComponent(caseId)}/tasks/${encodeURIComponent(taskId)}`, "PATCH",
    { status: "DONE", completionNote }, 200, undefined, signal);
}

/** API-018 사건 종료 (`POST /api/v1/cases/{caseId}/closures`). */
export function closeCase(caseId: string, key: string, signal?: AbortSignal) {
  return send(`/demo/cases/${encodeURIComponent(caseId)}/closures`, "POST", {}, 201, key, signal);
}
