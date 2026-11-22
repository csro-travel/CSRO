import type { CaseDetail, CaseList, CaseStatus } from "../_types/case";
import type { CaseMessageList } from "../_types/message";

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
): Promise<CaseMessageList> {
  return read(`/demo/cases/${encodeURIComponent(caseId)}/messages`, signal);
}
