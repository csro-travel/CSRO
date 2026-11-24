import type { CaseQuoteList, QuoteSearchAcceptance } from "../_types/quote";

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function text(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function integer(value: unknown, minimum = 0): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= minimum;
}

function date(value: unknown): value is string {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(value + "T00:00:00Z");
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}

function timestamp(value: unknown): value is string {
  return typeof value === "string" && /(Z|[+-]\d{2}:\d{2})$/.test(value) &&
    !Number.isNaN(Date.parse(value));
}

function textList(value: unknown): value is string[] {
  return Array.isArray(value) && value.every(text);
}

function quoteList(value: unknown, caseId: string): value is CaseQuoteList {
  if (!record(value) || value.caseId !== caseId || value.isSynthetic !== true ||
      !(value.effectiveAt === null || timestamp(value.effectiveAt)) ||
      !(value.clockMode === undefined || value.clockMode === "REAL_TIME" || value.clockMode === "SYNTHETIC_DEMO") ||
      !Array.isArray(value.groups)) return false;
  if (value.searchJob !== null) {
    const job = value.searchJob;
    if (!record(job) || !text(job.searchJobId) ||
        !["PENDING", "RUNNING", "WAITING", "COMPLETED", "FAILED"].includes(String(job.status)) ||
        !(job.errorCode === null || text(job.errorCode))) return false;
  } else if (value.groups.length !== 0) return false;
  const ids = new Set<string>();
  return value.groups.every((group: unknown) => {
    if (!record(group) || !["FULL_STAY", "VERIFIED_FAILURE_ONLY"].includes(String(group.coverageMode)) ||
        !record(group.stay) || !date(group.stay.checkInDate) || !date(group.stay.checkOutDate) ||
        group.stay.checkOutDate <= group.stay.checkInDate || !integer(group.stay.adults, 1) ||
        !integer(group.stay.rooms, 1) || !Array.isArray(group.remainingUnconfirmedStayDates) ||
        !group.remainingUnconfirmedStayDates.every(date) || typeof group.rankingAvailable !== "boolean" ||
        !textList(group.warnings) || !Array.isArray(group.quotes)) return false;
    return group.quotes.every((quote: unknown) => {
      if (!record(quote) || !text(quote.quoteId) || ids.has(quote.quoteId) || !text(quote.propertyName) ||
          !(quote.propertyId === undefined || text(quote.propertyId)) ||
          !text(quote.roomName) || !integer(quote.distanceMeters) || !integer(quote.totalAmount) ||
          quote.currency !== "KRW" || !(quote.priceDifference === null ||
            (typeof quote.priceDifference === "number" && Number.isSafeInteger(quote.priceDifference))) ||
          (group.coverageMode === "VERIFIED_FAILURE_ONLY" && quote.priceDifference !== null) ||
          !(quote.expired === undefined || typeof quote.expired === "boolean") ||
          !timestamp(quote.checkedAt) || !timestamp(quote.expiresAt) ||
          Date.parse(quote.expiresAt) <= Date.parse(quote.checkedAt) ||
          !(quote.reason === null || text(quote.reason))) return false;
      ids.add(quote.quoteId);
      return true;
    });
  });
}

async function problemDetail(response: Response, fallback: string): Promise<{ message: string; code?: string }> {
  const problem: unknown = await response.json().catch(() => null);
  return record(problem) ? {
    message: text(problem.detail) ? problem.detail : fallback,
    code: text(problem.code) ? problem.code : undefined,
  } : { message: fallback };
}

/** API-009: reads saved quote snapshots without starting a search. */
export async function getCaseQuotes(caseId: string, signal: AbortSignal): Promise<CaseQuoteList> {
  const response = await fetch(`/demo/cases/${encodeURIComponent(caseId)}/quotes`, {
    headers: { "X-Demo-Read": "1" }, cache: "no-store", signal,
  });
  if (!response.ok) {
    throw new Error((await problemDetail(response, `견적 조회 실패 (HTTP ${response.status})`)).message);
  }
  const result: unknown = await response.json();
  if (!quoteList(result, caseId)) throw new Error("사건과 견적 응답을 확인하지 못했습니다. 다시 조회해 주세요.");
  return result;
}

export class QuoteSearchError extends Error {
  constructor(message: string, readonly outcomeUnknown = false, readonly code?: string) {
    super(message);
    this.name = "QuoteSearchError";
  }
}

/** API-010: repeats the same empty request with the same key if receipt is unknown. */
export async function requestCaseQuotes(
  caseId: string,
  idempotencyKey: string,
  signal: AbortSignal,
): Promise<QuoteSearchAcceptance> {
  const uncertain = "검색 접수 결과를 확인하지 못했습니다. 같은 요청으로 다시 확인해 주세요.";
  const response = await fetch(`/demo/cases/${encodeURIComponent(caseId)}/quote-searches`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "Idempotency-Key": idempotencyKey },
    body: "{}", signal,
  }).catch(() => { throw new QuoteSearchError(uncertain, true); });
  if (response.status >= 500) throw new QuoteSearchError(uncertain, true);
  if (response.status !== 202) {
    const problem = await problemDetail(response, `견적 검색 접수 실패 (HTTP ${response.status})`);
    throw new QuoteSearchError(problem.message, false, problem.code);
  }
  const result: unknown = await response.json().catch(() => null);
  if (!record(result) || result.caseId !== caseId || !text(result.searchJobId) || result.status !== "PENDING") {
    throw new QuoteSearchError(uncertain, true);
  }
  return result as QuoteSearchAcceptance;
}
