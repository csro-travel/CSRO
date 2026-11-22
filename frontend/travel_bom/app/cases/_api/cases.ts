import type { CaseDetail, CaseList } from "../_types/case";

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
export function getCases(page: number, signal: AbortSignal): Promise<CaseList> {
  return read(`/demo/cases?page=${page}`, signal);
}

/** API-004 상담사 사건 상세 조회 (`GET /api/v1/cases/{caseId}`). */
export function getCaseDetail(
  caseId: string,
  signal: AbortSignal,
): Promise<CaseDetail> {
  return read(`/demo/cases/${encodeURIComponent(caseId)}`, signal);
}
