import type { ReplacementBooking, ReplacementIntent, ReplacementPointer, ReplacementReceipt } from "../_types/replacement";

const record = (value: unknown): value is Record<string, unknown> => typeof value === "object" && value !== null && !Array.isArray(value);
const text = (value: unknown): value is string => typeof value === "string" && value.trim().length > 0;
const integer = (value: unknown, min = 0): value is number => typeof value === "number" && Number.isSafeInteger(value) && value >= min;
const time = (value: unknown): value is string => text(value) && /(Z|[+-]\d{2}:\d{2})$/.test(value) && Number.isFinite(Date.parse(value));
export const replacementId = (value: unknown): value is string => typeof value === "string" && /^BRQ-[a-f0-9]{64}$/.test(value);
const price = (value: unknown): boolean => record(value) && integer(value.totalAmount) && value.currency === "KRW";
function date(value: unknown): value is string {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(value + "T00:00:00Z");
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}

export function replacementPointer(value: unknown): value is ReplacementPointer {
  return record(value) && replacementId(value.replacementBookingId) && text(value.selectionId);
}

function validBooking(value: unknown, caseId: string, pointer: ReplacementPointer): value is ReplacementBooking {
  if (!record(value) || value.caseId !== caseId || value.replacementBookingId !== pointer.replacementBookingId || value.selectionId !== pointer.selectionId ||
      !text(value.offerId) || !text(value.quoteId) || !["PROCESSING", "RESULT_UNKNOWN", "CONFIRMED", "FAILED"].includes(String(value.status)) ||
      !record(value.stay) || !date(value.stay.checkInDate) || !date(value.stay.checkOutDate) || value.stay.checkOutDate <= value.stay.checkInDate ||
      !integer(value.stay.adults, 1) || !integer(value.stay.rooms, 1) || !price(value.expectedPrice) ||
      typeof value.manualRequired !== "boolean" || !(value.nextCheckAt === null || time(value.nextCheckAt)) ||
      !time(value.updatedAt) || !time(value.effectiveAt) || value.isSynthetic !== true ||
      !(value.clockMode === "REAL_TIME" || value.clockMode === "SYNTHETIC_DEMO")) return false;
  if (value.status === "CONFIRMED") {
    return text(value.supplierBookingId) && price(value.confirmedPrice) && time(value.confirmedAt) && value.failureCode === null && value.currentPrice === null &&
      (value.confirmedPrice as { totalAmount: number }).totalAmount === (value.expectedPrice as { totalAmount: number }).totalAmount;
  }
  if (value.supplierBookingId !== null || value.confirmedPrice !== null || value.confirmedAt !== null) return false;
  if (value.status === "FAILED") return text(value.failureCode) && (value.currentPrice === null || (value.failureCode === "PRICE_CHANGED" && price(value.currentPrice)));
  return value.failureCode === null && value.currentPrice === null;
}

export class ReplacementError extends Error {
  constructor(message: string, readonly outcomeUnknown = false, readonly code?: string, readonly existingId?: string) {
    super(message); this.name = "ReplacementError";
  }
}

async function problem(response: Response, fallback: string) {
  const value: unknown = await response.json().catch(() => null);
  return new ReplacementError(record(value) && text(value.detail) ? value.detail : fallback, false,
    record(value) && text(value.code) ? value.code : undefined,
    record(value) && replacementId(value.replacementBookingId) ? value.replacementBookingId : undefined);
}

/** API-016 is read-only; a read failure never means the booking failed. */
export async function getReplacement(caseId: string, pointer: ReplacementPointer, signal: AbortSignal): Promise<ReplacementBooking> {
  const response = await fetch(`/demo/replacement-bookings/${encodeURIComponent(pointer.replacementBookingId)}`, {
    headers: { "X-Demo-Read": "1" }, cache: "no-store", signal,
  });
  if (!response.ok) throw await problem(response, `예약 처리 상태 조회 불가 (HTTP ${response.status})`);
  const result: unknown = await response.json();
  if (!validBooking(result, caseId, pointer)) throw Error("예약 처리 상태 응답을 확인하지 못했습니다. 같은 접수번호로 다시 조회해 주세요.");
  return result;
}

/** API-015 freezes selection and client key before receipt; status always comes from API-016. */
export async function requestReplacement(caseId: string, intent: ReplacementIntent, signal: AbortSignal): Promise<ReplacementReceipt> {
  const uncertain = "예약 접수 결과를 확인하지 못했습니다. 새 예약을 만들지 말고 보관된 같은 요청으로 확인해 주세요.";
  const response = await fetch(`/demo/cases/${encodeURIComponent(caseId)}/replacement-bookings`, {
    method: "POST", headers: { "Content-Type": "application/json", "Idempotency-Key": intent.key }, body: JSON.stringify(intent.body), signal,
  }).catch(() => { throw new ReplacementError(uncertain, true); });
  if (response.status >= 500) throw new ReplacementError(uncertain, true);
  if (response.status !== 202) throw await problem(response, `예약 접수 실패 (HTTP ${response.status})`);
  const result: unknown = await response.json().catch(() => null);
  if (!record(result) || result.caseId !== caseId || result.selectionId !== intent.body.selectionId || !replacementId(result.replacementBookingId) || !time(result.acceptedAt) || "status" in result) {
    throw new ReplacementError(uncertain, true);
  }
  return result as ReplacementReceipt;
}

function storageKey(caseId: string) { return `csro.replacement-intent.v1:${caseId}`; }
export function pendingReplacement(caseId: string): ReplacementIntent | undefined {
  const raw = sessionStorage.getItem(storageKey(caseId));
  if (!raw) return;
  const value: unknown = JSON.parse(raw);
  if (!record(value) || value.version !== 1 || !text(value.key) || !/^[A-Za-z0-9._:-]{1,100}$/.test(value.key) ||
      !record(value.body) || Object.keys(value.body).length !== 1 || !text(value.body.selectionId) ||
      !(value.resolved === undefined || (replacementPointer(value.resolved) && value.resolved.selectionId === value.body.selectionId))) {
    throw Error("보관된 예약 요청을 확인할 수 없습니다. 새 접수 전에 담당자에게 확인해 주세요.");
  }
  return value as ReplacementIntent;
}
export function saveReplacement(caseId: string, intent: ReplacementIntent) { sessionStorage.setItem(storageKey(caseId), JSON.stringify(intent)); }
export function clearReplacement(caseId: string) { sessionStorage.removeItem(storageKey(caseId)); }
