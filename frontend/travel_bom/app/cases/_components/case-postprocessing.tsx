"use client";

import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { getCaseMessages } from "../_api/cases";

const taskTypes = [
  ["CHECKIN_CONFIRM", "대체 숙소 입실 확인"],
  ["ORIGINAL_FOLLOWUP", "원예약 후속 처리"],
  ["TRANSPORT_REVIEW", "교통비 검토"],
  ["REPLACEMENT_COST_REVIEW", "대체 숙소 차액 검토"],
  ["POINTS_REVIEW", "사과 포인트 검토"],
  ["CONTACT_CUSTOMER", "고객 연락"],
  ["VERIFY_HOTEL", "숙소 확인"],
] as const;

type Suggestion = { description: string; evidence_refs: string[]; policy_refs: string[] };
type Draft = {
  draftId: string;
  draft: { draft_text: string; follow_up_suggestions: Suggestion[] };
  finalized: boolean;
  taskIds: string[];
};
type Choice = { type: string; assignToMe: boolean; required: boolean; dueAt: string };

async function readResult<T>(response: Response): Promise<T> {
  if (response.ok) return response.json();
  const problem = await response.json().catch(() => null);
  throw new Error(problem?.detail ?? `요청을 처리하지 못했습니다. (HTTP ${response.status})`);
}

export function CasePostprocessing({ caseId, closed, onSaved }: {
  caseId: string;
  closed: boolean;
  onSaved: () => void;
}) {
  const path = `/demo/cases/${encodeURIComponent(caseId)}/postprocessing`;
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const operation = useRef(false);
  const requestId = useRef<string | null>(null);
  const [draft, setDraft] = useState<Draft>();
  const [reviewedText, setReviewedText] = useState("");
  const [choices, setChoices] = useState<Partial<Record<number, Choice>>>({});
  const [busy, setBusy] = useState<"draft" | "finalize" | "download">();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [messageEvidence, setMessageEvidence] = useState<Map<string, string>>(new Map());

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      try {
        const response = await fetch(path, { headers: { "X-Demo-Read": "1" }, signal: controller.signal });
        if (response.status === 204) return;
        const data = await readResult<Draft>(response);
        if (!controller.signal.aborted) {
          setDraft(data);
          setReviewedText(data.draft.draft_text);
        }
      } catch {
        if (!controller.signal.aborted) setError("저장된 후처리 문서를 조회하지 못했습니다.");
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    })();
    return () => controller.abort();
  }, [path]);

  useEffect(() => {
    setMessageEvidence(new Map());
    if (!draft || draft.finalized) return;
    const pending = new Set(
      draft.draft.follow_up_suggestions.flatMap(task => task.evidence_refs)
        .filter(reference => reference.startsWith("MSG-")),
    );
    if (!pending.size) return;

    const controller = new AbortController();
    void (async () => {
      const quotes = new Map<string, string>();
      let cursor: string | undefined;
      try {
        while (pending.size && !controller.signal.aborted) {
          const page = await getCaseMessages(caseId, controller.signal, cursor);
          for (const message of page.items) {
            if (pending.delete(message.messageId) && message.body?.trim()) {
              quotes.set(message.messageId, '"' + message.body.trim() + '"');
            }
          }
          const next = page.pagination.hasMoreBefore ? page.pagination.nextBeforeMessageId : null;
          if (!next || next === cursor) break;
          cursor = next;
        }
      } finally {
        if (!controller.signal.aborted) setMessageEvidence(quotes);
      }
    })().catch(() => {
      // Keep the available quotes and use the existing reference for unresolved messages.
    });
    return () => controller.abort();
  }, [caseId, draft]);

  async function run(kind: NonNullable<typeof busy>, action: () => Promise<void>) {
    if (operation.current) return;
    operation.current = true;
    setBusy(kind);
    setError("");
    try {
      await action();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "후처리 요청에 실패했습니다.");
    } finally {
      operation.current = false;
      setBusy(undefined);
    }
  }

  function generateDraft() {
    return run("draft", async () => {
      requestId.current ??= crypto.randomUUID();
      const response = await fetch(path + "/drafts", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ requestId: requestId.current }),
      });
      const data = await readResult<Draft>(response);
      setDraft(data);
      setReviewedText(data.draft.draft_text);
      setChoices({});
    });
  }

  function finalize(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!draft) return;
    return run("finalize", async () => {
      const tasks = Object.entries(choices).flatMap(([key, choice]) => choice ? [{
        ...choice, index: Number(key), dueAt: choice.dueAt ? `${choice.dueAt}:00+09:00` : null,
      }] : []);
      const data = await readResult<{ taskIds: string[] }>(await fetch(
        `${path}/drafts/${draft.draftId}/finalize`, {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ reviewedText, tasks }),
        },
      ));
      setDraft({ ...draft, finalized: true, taskIds: data.taskIds });
      dialog.current?.close();
      onSaved();
    });
  }

  function download() {
    if (!draft) return;
    return run("download", async () => {
      const response = await fetch(`${path}/drafts/${draft.draftId}/document`, { headers: { "X-Demo-Read": "1" } });
      if (!response.ok) await readResult(response);
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a");
      link.href = url;
      link.download = `${caseId}-postprocessing.docx`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
  }

  function updateChoice(index: number, changes: Partial<Choice>) {
    setChoices(current => ({ ...current, [index]: { ...current[index]!, ...changes } }));
  }

  return (
    <div className="mt-3 space-y-2 border-t pt-3">
      <div className="flex flex-wrap items-center gap-2">
        {draft?.finalized ? (
          <>
            <Button variant="outline" disabled={!!busy} onClick={download}>DOCX 다운로드</Button>
            <span role="status" className="text-xs text-muted-foreground">문서 저장 완료 · 연결 업무 {draft.taskIds.length}건</span>
          </>
        ) : draft ? (
          <Button variant="outline" disabled={closed || !!busy} onClick={() => {
            setError("");
            dialog.current?.showModal();
          }}>초안 열기</Button>
        ) : !closed && (
          <Button variant="outline" disabled={loading || !!busy} onClick={generateDraft}>초안 생성</Button>
        )}
      </div>
      {busy === "draft" && <p role="status" className="text-xs">초안을 생성하고 있습니다…</p>}
      {error && busy !== "finalize" && <p role="alert" className="text-sm text-destructive">{error}</p>}

      <dialog ref={dialog} aria-labelledby={titleId}
        onCancel={event => { if (operation.current) event.preventDefault(); }}
        className="fixed inset-0 m-auto max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] max-w-2xl overflow-auto rounded-xl border bg-card p-5 text-card-foreground shadow-xl backdrop:bg-black/50">
        <form onSubmit={finalize} className="space-y-4">
          <h2 id={titleId} className="text-lg font-semibold">후속 업무 문서 초안</h2>
          <fieldset disabled={!!busy || closed} className="space-y-4">
            <label className="block space-y-1 text-sm font-medium">
              <span>문서 본문</span>
              <textarea required maxLength={30000} value={reviewedText}
                onChange={event => setReviewedText(event.target.value)}
                className="min-h-56 w-full rounded-lg border bg-background p-3 font-normal" />
            </label>
            <section aria-label="등록할 후속 업무" className="space-y-2">
              <h3 className="text-sm font-semibold">등록할 후속 업무</h3>
              {draft?.draft.follow_up_suggestions.map((task, index) => {
                const choice = choices[index];
                return (
                  <div key={index} className="space-y-2 rounded-lg border p-3 text-sm">
                    <label className="flex gap-2">
                      <input type="checkbox" checked={!!choice} onChange={event => {
                        const value = event.target.checked ? { type: "", assignToMe: true, required: false, dueAt: "" } : undefined;
                        setChoices(current => ({ ...current, [index]: value }));
                      }} />
                      {task.description}
                    </label>
                    <p className="text-xs text-muted-foreground">{[...task.evidence_refs.map(reference => messageEvidence.get(reference) ?? reference), ...task.policy_refs].join(" · ")}</p>
                    {choice && <div className="flex flex-wrap gap-3">
                      <label className="space-x-2">유형
                        <select required value={choice.type} onChange={event => updateChoice(index, { type: event.target.value, required: event.target.value === "CHECKIN_CONFIRM" || choice.required })}
                          className="rounded border bg-background p-1">
                          <option value="">선택</option>
                          {taskTypes.map(([type, name]) => <option key={type} value={type}>{name}</option>)}
                        </select>
                      </label>
                      <label className="space-x-2">기한(KST)
                        <input type="datetime-local" value={choice.dueAt}
                          onChange={event => updateChoice(index, { dueAt: event.target.value })}
                          className="rounded border bg-background p-1" />
                      </label>
                      <label className="flex items-center gap-1">
                        <input type="checkbox" checked={choice.assignToMe}
                          onChange={event => updateChoice(index, { assignToMe: event.target.checked })} /> 나에게 배정
                      </label>
                      <label className="flex items-center gap-1">
                        <input type="checkbox" checked={choice.required} disabled={choice.type === "CHECKIN_CONFIRM"}
                          onChange={event => updateChoice(index, { required: event.target.checked })} /> 종료 전 필수
                      </label>
                    </div>}
                  </div>
                );
              })}
            </section>
          </fieldset>
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" disabled={!!busy} onClick={() => dialog.current?.close()}>닫기</Button>
            <Button type="submit" disabled={!!busy || closed || !reviewedText.trim()}>
              {busy === "finalize" ? "문서 생성 중…" : "문서 생성"}
            </Button>
          </div>
        </form>
      </dialog>
    </div>
  );
}
