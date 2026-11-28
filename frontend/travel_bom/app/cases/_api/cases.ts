import type { CaseDetail, CaseList, CaseStatus } from "../_types/case";
import type { CaseChatMessage, CaseMessageList } from "../_types/message";

async function read<T>(path: string, signal: AbortSignal): Promise<T> {
  const response = await fetch(path, {
    headers: { "X-Demo-Read": "1" },
    cache: "no-store",
    signal,
  });
  if (!response.ok) throw Error("조회 실패 (HTTP " + response.status + ")");
  return response.json();
}

/** API-003 상담사 사건 목록 조회 (`GET /api/v1/cases`). */
export function getCases(
  page: number,
  status: CaseStatus | undefined,
  signal: AbortSignal,
  search?: string,
): Promise<CaseList> {
  const query = new URLSearchParams({ page: String(page) });
  if (status) query.set("status", status);
  if (search?.trim()) query.set("q", search.trim());
  return read(`/demo/cases?${query}`, signal);
}

/** API-004 상담사 사건 상세 조회 (`GET /api/v1/cases/{caseId}`). */
export function getCaseDetail(
  caseId: string,
  signal: AbortSignal,
): Promise<CaseDetail> {
  return read(`/demo/cases/${encodeURIComponent(caseId)}`, signal);
}

/** API-011 사건별 상담 채팅 메시지 조회 (`GET /api/v1/cases/{caseId}/messages`). */
export function getCaseMessages(
  caseId: string,
  signal: AbortSignal,
  beforeMessageId?: string,
): Promise<CaseMessageList> {
  const path = `/demo/cases/${encodeURIComponent(caseId)}/messages`;
  const query = beforeMessageId ? `?${new URLSearchParams({ beforeMessageId })}` : "";
  return read(path + query, signal);
}

export class CaseMessageSendError extends Error {
  constructor(message: string, readonly outcomeUnknown = false) {
    super(message);
    this.name = "CaseMessageSendError";
  }
}

/** API-012 사건별 상담 채팅 메시지 전송 (`POST /api/v1/cases/{caseId}/messages`). */
export async function sendCaseMessage(
  caseId: string,
  body: string,
  signal?: AbortSignal,
): Promise<CaseChatMessage> {
  const unconfirmed = "전송 결과를 확인하지 못했습니다. 대화 내역을 확인해 주세요.";
  const response = await fetch(`/demo/cases/${encodeURIComponent(caseId)}/messages`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ body }),
    signal,
  }).catch(() => {
    throw new CaseMessageSendError(unconfirmed, true);
  });

  if (response.status === 502 || response.status === 504) {
    throw new CaseMessageSendError(unconfirmed, true);
  }

  if (response.status !== 201) {
    const problem: unknown = await response.json().catch(() => null);
    if (typeof problem === "object" && problem !== null &&
        "detail" in problem && typeof problem.detail === "string" && problem.detail.trim()) {
      throw new CaseMessageSendError(problem.detail);
    }
    throw new CaseMessageSendError(`메시지 전송 실패 (HTTP ${response.status})`);
  }

  const message: CaseChatMessage | null = await response.json().catch(() => null);
  if (
    typeof message?.messageId !== "string" || !message.messageId ||
    (message.senderType !== "CUSTOMER" && message.senderType !== "COUNSELOR") ||
    message.messageType !== "TEXT" || typeof message.body !== "string" ||
    typeof message.sentAt !== "string" || message.offer !== null
  ) {
    throw new CaseMessageSendError(unconfirmed, true);
  }
  return message;
}

export async function sendAirbnbSearchGuide(caseId: string, searchId: string, listingIds: string[], signal: AbortSignal) {
  // Reopening the same case and resubmitting the same snapshot and selection reuses the request ID.
  const bytes = new TextEncoder().encode(JSON.stringify([caseId, searchId, listingIds]));
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  const hex = Array.from(digest.slice(0, 16), value => value.toString(16).padStart(2, "0")).join("");
  const requestId = [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join("-");
  const response = await fetch(
    `/demo/cases/${encodeURIComponent(caseId)}/airbnb-demo-searches/${searchId}/guides/${requestId}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ listingIds }),
      signal: AbortSignal.any([signal, AbortSignal.timeout(10000)]),
    },
  ).catch(() => { throw new Error("안내 전송 결과를 확인하지 못했습니다. 같은 후보로 다시 눌러 확인할 수 있습니다."); });
  if (response.status !== 204) {
    if (response.status === 502 || response.status === 504) {
      throw new Error("안내 전송 결과를 확인하지 못했습니다. 같은 후보로 다시 눌러 확인할 수 있습니다.");
    }
    const problem = await response.json().catch(() => null);
    throw new Error(problem?.detail || `숙소 안내 전송 실패 (HTTP ${response.status})`);
  }
}
