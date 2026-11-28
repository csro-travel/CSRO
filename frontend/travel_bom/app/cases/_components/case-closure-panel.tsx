"use client";

import { useRef, useState } from "react";
import { CircleCheck, ClipboardCheck, Sparkles } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/input";
import { formatDateTime, formatDay, originLabels } from "@/lib/case-labels";
import { requestAssistDraft, type AssistDraft } from "../_api/assist";
import { ClosureError, closeCase, completeTask, confirmCheckin, type CloseBlocker } from "../_api/closure";
import type { CaseDetail, CaseTask } from "../_types/case";
import type { ReplacementBooking } from "../_types/replacement";
import { taskNames } from "./case-details";
import { CasePostprocessing } from "./case-postprocessing";

const closeReasons = new Map([["REPLACEMENT_CHECKED_IN", "대체 숙소 입실 확인 후 종료"]]);
const dateTime = new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Seoul" });

function blockerText(blocker: CloseBlocker) {
  switch (blocker.code) {
    case "REPLACEMENT_NOT_CONFIRMED": return "확정된 가상 대체 예약이 없습니다.";
    case "CHECKIN_NOT_CONFIRMED": return "고객의 대체 숙소 입실 확인이 아직 없습니다.";
    case "STAY_NOT_COVERED": return `보장되지 않은 숙박일이 있습니다: ${(blocker.stayDates ?? []).join(", ")}`;
    case "REQUIRED_TASKS_OPEN": return `완료되지 않은 필수 업무: ${(blocker.tasks ?? []).map(task => taskNames.get(task.type) ?? task.type).join(", ")}`;
    default: return blocker.code;
  }
}

function TaskRow({ caseId, task, closed, onDone }: { caseId: string; task: CaseTask; closed: boolean; onDone: () => void }) {
  const [note, setNote] = useState("");
  const [sending, setSending] = useState(false);
  const [error, setError] = useState("");
  const checkin = task.type === "CHECKIN_CONFIRM";

  async function submit() {
    if (!note.trim() || sending) return;
    setSending(true); setError("");
    try { await completeTask(caseId, task.taskId, note.trim()); onDone(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "업무 완료 처리 실패"); }
    finally { setSending(false); }
  }

  return (
    <li className="space-y-1.5 py-2 first:pt-0 last:pb-0" data-task-type={task.type}>
      <p className="text-sm font-medium">{taskNames.get(task.type) ?? task.type}</p>
      {checkin ? <p className="text-xs text-muted-foreground">고객이 입실을 확인하면 자동으로 완료됩니다.</p> : <>
        <Textarea aria-label={`${taskNames.get(task.type) ?? task.type} 처리 내용`} value={note} maxLength={1000} disabled={closed || sending}
          onChange={event => setNote(event.target.value)} rows={2}
          placeholder={task.type === "ORIGINAL_FOLLOWUP" ? "원예약 후속 처리 결과 (예: 원호텔에 대체 숙박 안내·취소 요청 접수)" : "처리한 내용을 적어 주세요"} />
        <Button type="button" variant="outline" disabled={closed || sending || !note.trim()} onClick={() => void submit()}>
          {sending ? "완료 처리 중…" : "업무 완료"}
        </Button>
      </>}
      {error && <p role="alert" className="text-sm">{error}</p>}
    </li>
  );
}

/** Counselor-side API-008 task completion and API-018 guarded closure. */
export function CaseClosurePanel({ detail, onChanged }: { detail: CaseDetail; onChanged: () => void }) {
  const closed = detail.status === "CLOSED";
  const [closing, setClosing] = useState(false);
  const [error, setError] = useState("");
  const [blockers, setBlockers] = useState<CloseBlocker[]>([]);
  const key = useRef<string | null>(null);

  async function close() {
    if (closing || closed) return;
    key.current ??= crypto.randomUUID();
    setClosing(true); setError(""); setBlockers([]);
    try { await closeCase(detail.caseId, key.current); key.current = null; onChanged(); }
    catch (cause) {
      if (cause instanceof ClosureError && !cause.outcomeUnknown) key.current = null;
      setError(cause instanceof Error ? cause.message : "대응 종료 실패");
      if (cause instanceof ClosureError) setBlockers(cause.blockers);
      // Blockers may name tasks created after this detail was loaded.
      onChanged();
    } finally { setClosing(false); }
  }

  function taskDone() {
    setError(""); setBlockers([]);
    onChanged();
  }

  return (
    <section className="panel space-y-3 p-4" aria-label="후속 업무와 대응 종료">
      <h3 className="flex items-center gap-1.5 text-sm font-semibold"><ClipboardCheck className="size-4 text-muted-foreground" aria-hidden="true" />{closed ? "처리 결과" : "후속 업무와 대응 종료"}</h3>
      {closed ? <ClosureSummary detail={detail} /> : <>
        <CompletedTasks detail={detail} />
        {detail.tasks.length ? (
          <ul className="divide-y">{detail.tasks.map(task => <TaskRow key={task.taskId} caseId={detail.caseId} task={task} closed={closed} onDone={taskDone} />)}</ul>
        ) : <p className="text-sm text-muted-foreground">남은 업무 없음</p>}
        <CasePostprocessing key={detail.caseId} caseId={detail.caseId} closed={closed} onSaved={onChanged} />
        <div className="space-y-2 border-t pt-3">
          <p className="text-xs text-muted-foreground">입실 확인, 필요한 숙박일 보장, 필수 업무 완료를 모두 확인한 뒤에만 종료됩니다.</p>
          <Button type="button" disabled={closing} onClick={() => void close()}>{closing ? "종료 조건 확인 중…" : "대응 건 종료"}</Button>
          {error && <p role="alert" className="text-sm">{error}</p>}
          {blockers.length > 0 && <ul className="list-disc space-y-1 pl-5 text-sm">{blockers.map(item => <li key={item.code}>{blockerText(item)}</li>)}</ul>}
        </div>
      </>}
      {closed && <CasePostprocessing key={detail.caseId} caseId={detail.caseId} closed onSaved={onChanged} />}
    </section>
  );
}

function CompletedTasks({ detail }: { detail: CaseDetail }) {
  const done = detail.completedTasks ?? [];
  if (!done.length) return null;
  return (
    <ul className="space-y-2">
      {done.map(task => (
        <li key={task.taskId} className="grid grid-cols-[1rem_minmax(0,1fr)] gap-x-2 text-sm">
          <CircleCheck className="mt-0.5 size-4 text-success" aria-hidden="true" />
          <div className="min-w-0">
            <p className="flex flex-wrap items-baseline justify-between gap-x-3">
              <span className="font-medium">{taskNames.get(task.type) ?? task.type}</span>
              <time dateTime={task.completedAt} className="num text-xs text-muted-foreground">{formatDateTime(task.completedAt)} 완료</time>
            </p>
            {task.completionNote && <p className="text-muted-foreground wrap-anywhere">{task.completionNote}</p>}
            {task.completionEvidenceId && <p className="num text-xs text-muted-foreground">근거 {task.completionEvidenceId}</p>}
          </div>
        </li>
      ))}
    </ul>
  );
}

/** What happened on a closed case, built only from stored facts. */
function ClosureSummary({ detail }: { detail: CaseDetail }) {
  const unavailable = detail.nights.filter(night => night.factStatus === "VERIFIED" && night.fulfillmentStatus === "UNAVAILABLE").map(night => formatDay(night.stayDate));
  const origin = originLabels[detail.origin?.type ?? "UNKNOWN"] ?? originLabels.UNKNOWN;
  const [assist, setAssist] = useState<AssistDraft>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function summarize() {
    if (loading) return;
    setLoading(true); setError("");
    try { setAssist(await requestAssistDraft(detail.caseId, "CLOSURE_SUMMARY")); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "요약을 받지 못했습니다."); }
    finally { setLoading(false); }
  }

  return (
    <div className="space-y-3">
      <p role="status" className="flex flex-wrap items-center gap-2 text-sm font-medium">
        <Badge tone="neutral">종료</Badge>
        {detail.closure ? <>{closeReasons.get(detail.closure.reason) ?? detail.closure.reason}<time dateTime={detail.closure.closedAt} className="num text-xs font-normal text-muted-foreground">{formatDateTime(detail.closure.closedAt)}</time></> : "종료된 대응 건입니다."}
      </p>
      <dl className="space-y-1 text-sm">
        <div className="flex gap-2"><dt className="w-20 shrink-0 text-muted-foreground">올라온 이유</dt><dd>{origin.label}</dd></div>
        <div className="flex gap-2"><dt className="w-20 shrink-0 text-muted-foreground">확인된 문제</dt><dd>{unavailable.length ? `${unavailable.join(", ")} 객실 제공 불가` : "객실 제공 불가로 확인된 숙박일 없음"}</dd></div>
        <div className="flex gap-2"><dt className="w-20 shrink-0 text-muted-foreground">완료한 업무</dt><dd>{(detail.completedTasks ?? []).length}건</dd></div>
      </dl>
      <CompletedTasks detail={detail} />
      <div className="space-y-2 rounded-md bg-muted/60 p-3">
        <div className="flex flex-wrap items-center gap-2">
          <p className="eyebrow">상담 요약</p>
          {assist && <Badge tone={assist.source === "LLM" ? "info" : "outline"}>{assist.source === "LLM" ? `AI 초안 · ${assist.model ?? "모델"}` : "규칙 기반"}</Badge>}
          <Button type="button" size="sm" variant="ghost" className="ml-auto" disabled={loading} onClick={() => void summarize()}>
            <Sparkles aria-hidden="true" />{loading ? "요약 만드는 중…" : assist ? "다시 받기" : "AI 요약 받기"}
          </Button>
        </div>
        {assist
          ? <p className="text-sm leading-relaxed whitespace-pre-wrap">{assist.summary}</p>
          : <p className="text-sm text-muted-foreground">위 기록을 인계용 문장으로 정리한 요약을 받을 수 있습니다. 대응 건 기록은 바뀌지 않습니다.</p>}
        {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
      </div>
      <p className="text-xs text-muted-foreground">결제·환불·원예약 자동 취소는 이 시연 범위에 없습니다. 위 기록은 상담사가 남긴 처리 내용입니다.</p>
    </div>
  );
}

/** Customer-side API-017 check-in on a CONFIRMED replacement booking. */
export function CustomerCheckin({ booking, onDone }: { booking: ReplacementBooking; onDone: () => void }) {
  const [sending, setSending] = useState(false);
  const [error, setError] = useState("");
  const key = useRef<string | null>(null);
  if (booking.status !== "CONFIRMED") return null;
  if (booking.checkinConfirmation) {
    return <p role="status" className="font-medium">입실 확인 완료 · {dateTime.format(new Date(booking.checkinConfirmation.checkedInAt))}</p>;
  }

  async function submit() {
    if (sending) return;
    key.current ??= crypto.randomUUID();
    setSending(true); setError("");
    try { await confirmCheckin(booking.replacementBookingId, key.current); key.current = null; onDone(); }
    catch (cause) {
      if (cause instanceof ClosureError && !cause.outcomeUnknown) key.current = null;
      setError(cause instanceof Error ? cause.message : "입실 확인 실패");
    } finally { setSending(false); }
  }

  return (
    <div className="space-y-1.5">
      <p>대체 숙소에 실제로 입실하셨다면 아래 버튼을 눌러 주세요.</p>
      <Button type="button" disabled={sending} onClick={() => void submit()}>{sending ? "입실 확인 중…" : "입실 완료 확인"}</Button>
      {error && <p role="alert">{error}</p>}
    </div>
  );
}
