"use client";

import { useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { ClosureError, closeCase, completeTask, confirmCheckin, type CloseBlocker } from "../_api/closure";
import type { CaseDetail, CaseTask } from "../_types/case";
import type { ReplacementBooking } from "../_types/replacement";
import { taskNames } from "./case-details";

const card = "min-w-0 rounded-xl border bg-card px-4 py-3.5";
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
        <textarea aria-label={`${taskNames.get(task.type) ?? task.type} 처리 내용`} value={note} maxLength={1000} disabled={closed || sending}
          onChange={event => setNote(event.target.value)} rows={2}
          placeholder={task.type === "ORIGINAL_FOLLOWUP" ? "원예약 후속 처리 결과 (예: 원호텔에 대체 숙박 안내·취소 요청 접수)" : "처리 내용"}
          className="w-full rounded-lg border bg-background px-2.5 py-1.5 text-sm" />
        <Button type="button" size="sm" variant="outline" disabled={closed || sending || !note.trim()} onClick={() => void submit()}>
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
      setError(cause instanceof Error ? cause.message : "사건 종료 실패");
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
    <section className={`${card} mt-3 space-y-3`} aria-label="후속 업무와 사건 종료">
      <h3 className="text-sm font-semibold">후속 업무와 사건 종료</h3>
      {closed ? <p role="status" className="text-sm font-medium">종료된 사건입니다.</p> : <>
        {detail.tasks.length ? (
          <ul className="divide-y">{detail.tasks.map(task => <TaskRow key={task.taskId} caseId={detail.caseId} task={task} closed={closed} onDone={taskDone} />)}</ul>
        ) : <p className="text-sm text-muted-foreground">남은 업무 없음</p>}
        <div className="space-y-2 border-t pt-3">
          <p className="text-xs text-muted-foreground">입실 확인, 필요한 숙박일 보장, 필수 업무 완료를 모두 확인한 뒤에만 종료됩니다.</p>
          <Button type="button" disabled={closing} onClick={() => void close()}>{closing ? "종료 조건 확인 중…" : "사건 종료"}</Button>
          {error && <p role="alert" className="text-sm">{error}</p>}
          {blockers.length > 0 && <ul className="list-disc space-y-1 pl-5 text-sm">{blockers.map(item => <li key={item.code}>{blockerText(item)}</li>)}</ul>}
        </div>
      </>}
    </section>
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
