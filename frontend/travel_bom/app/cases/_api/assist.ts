export type AssistDraft = {
  caseId: string;
  kind: "GUIDANCE" | "CLOSURE_SUMMARY";
  source: "LLM" | "RULES";
  model: string | null;
  generatedAt: string;
  summary: string;
  nextActions: string[];
  customerMessageDraft: string | null;
  warnings: string[];
};

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === "object" && value !== null && !Array.isArray(value);

/** Counselor assist draft (`POST /api/v1/cases/{caseId}/assist-drafts`). A suggestion only; it changes no case state. */
export async function requestAssistDraft(caseId: string, kind: AssistDraft["kind"], signal?: AbortSignal): Promise<AssistDraft> {
  const response = await fetch(`/demo/cases/${encodeURIComponent(caseId)}/assist-drafts`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ kind }),
    signal,
  }).catch(() => { throw Error("초안 서비스에 연결하지 못했습니다."); });
  const value: unknown = await response.json().catch(() => null);
  if (!response.ok) {
    throw Error(isRecord(value) && typeof value.detail === "string" && value.detail.trim() ? value.detail : `초안을 받지 못했습니다 (HTTP ${response.status})`);
  }
  if (!isRecord(value) || typeof value.summary !== "string" || !Array.isArray(value.nextActions)) throw Error("초안 응답 형식을 확인하지 못했습니다.");
  return value as AssistDraft;
}
