import type { CaseDetail, CaseList, CaseStatus } from "../_types/case";
import type { CaseChatMessage, CaseMessageList } from "../_types/message";
import { counselorResolution } from "./resolution";

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
): Promise<CaseList> {
  const query = new URLSearchParams({ page: String(page) });
  if (status) query.set("status", status);
  return read(`/demo/cases?${query}`, signal);
}

/** API-004 상담사 사건 상세 조회 (`GET /api/v1/cases/{caseId}`). */
export async function getCaseDetail(
  caseId: string,
  signal: AbortSignal,
): Promise<CaseDetail> {
  const result = await read<CaseDetail>(`/demo/cases/${encodeURIComponent(caseId)}`, signal);
  if (result.caseId !== caseId || (result.resolution !== undefined && !counselorResolution(result.resolution))) throw Error("사건의 종료 조건 응답을 확인하지 못했습니다.");
  return result;
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
