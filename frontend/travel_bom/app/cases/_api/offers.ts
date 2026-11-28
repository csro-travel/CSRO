export type OfferBody = { quoteIds: string[] };
export type SelectionBody = { offerId: string; quoteId: string };
export type PendingIntent<T> = { version: 1; key: string; body: T };

export class OfferMutationError extends Error {
  constructor(message: string, readonly outcomeUnknown = false) {
    super(message);
    this.name = "OfferMutationError";
  }
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);
const isText = (value: unknown): value is string => typeof value === "string" && value.trim().length > 0;
const isTime = (value: unknown): value is string => isText(value) && /(Z|[+-]\d{2}:\d{2})$/.test(value) && Number.isFinite(Date.parse(value));

function storageKey(kind: "offer" | "selection", caseId: string) {
  return `csro.${kind}-intent.v1:${caseId}`;
}

/** Only non-secret stored IDs and the immutable request key/body are retained. */
export function loadPendingIntent<T extends OfferBody | SelectionBody>(kind: "offer" | "selection", caseId: string): PendingIntent<T> | undefined {
  const raw = sessionStorage.getItem(storageKey(kind, caseId));
  if (!raw) return;
  const value: unknown = JSON.parse(raw);
  if (!isRecord(value) || value.version !== 1 || !isText(value.key) || !/^[A-Za-z0-9._:-]{1,100}$/.test(value.key) || !isRecord(value.body)) {
    throw Error("보관된 요청을 확인할 수 없습니다. 새 요청을 보내기 전에 담당자에게 확인해 주세요.");
  }
  const body = value.body;
  const valid = kind === "offer"
    ? Object.keys(body).length === 1 && Array.isArray(body.quoteIds) && body.quoteIds.length >= 1 && body.quoteIds.length <= 3 && body.quoteIds.every(isText) && new Set(body.quoteIds).size === body.quoteIds.length
    : Object.keys(body).length === 2 && isText(body.offerId) && isText(body.quoteId);
  if (!valid) throw Error("보관된 요청 본문을 확인할 수 없습니다. 새 요청을 보내기 전에 담당자에게 확인해 주세요.");
  return value as PendingIntent<T>;
}

export function persistIntent<T>(kind: "offer" | "selection", caseId: string, intent: PendingIntent<T>) {
  // Persist before sending. If storage fails, do not issue a write we cannot safely retry after reload.
  sessionStorage.setItem(storageKey(kind, caseId), JSON.stringify(intent));
}

export function clearIntent(kind: "offer" | "selection", caseId: string) {
  sessionStorage.removeItem(storageKey(kind, caseId));
}

async function mutate(caseId: string, path: "offers" | "selection", intent: PendingIntent<OfferBody | SelectionBody>, signal: AbortSignal) {
  const uncertain = "처리 결과를 확인하지 못했습니다. 보관된 같은 요청으로 다시 확인해 주세요.";
  const response = await fetch(`/demo/cases/${encodeURIComponent(caseId)}/${path}`, {
    method: path === "offers" ? "POST" : "PUT",
    headers: { "Content-Type": "application/json", "Idempotency-Key": intent.key },
    body: JSON.stringify(intent.body), signal,
  }).catch(() => { throw new OfferMutationError(uncertain, true); });
  if (response.status >= 500) throw new OfferMutationError(uncertain, true);
  if (response.status !== (path === "offers" ? 201 : 200)) {
    const problem: unknown = await response.json().catch(() => null);
    throw new OfferMutationError(isRecord(problem) && isText(problem.detail) ? problem.detail : `요청 실패 (HTTP ${response.status})`);
  }
  const result: unknown = await response.json().catch(() => null);
  if (!isRecord(result) || result.caseId !== caseId || !isText(result.offerId)) throw new OfferMutationError(uncertain, true);
  if (path === "offers") {
    if (!isText(result.messageId) || !isTime(result.sentAt)) throw new OfferMutationError(uncertain, true);
  } else {
    const body = intent.body as SelectionBody;
    if (!isText(result.selectionId) || result.offerId !== body.offerId || result.quoteId !== body.quoteId || !isTime(result.selectedAt)) {
      throw new OfferMutationError(uncertain, true);
    }
  }
  // Immutable success is receipt only; the caller must read API-011 for current state.
}

export const sendCaseOffer = (caseId: string, intent: PendingIntent<OfferBody>, signal: AbortSignal) => mutate(caseId, "offers", intent, signal);
export const selectCaseOffer = (caseId: string, intent: PendingIntent<SelectionBody>, signal: AbortSignal) => mutate(caseId, "selection", intent, signal);
