"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { clearResolutionIntent, loadResolutionIntent, ResolutionError, saveResolutionIntent, sendResolution } from "../_api/resolution";
import type { CounselorResolution, CustomerResolution, FollowupOutcome, ResolutionBlocker, ResolutionCommand, ResolutionIntent, ResolutionTask } from "../_types/resolution";
import type { ReplacementBooking } from "../_types/replacement";

const dateTime = new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Seoul" });
const dateParts = new Intl.DateTimeFormat("en-CA", { year: "numeric", month: "2-digit", day: "2-digit", timeZone: "Asia/Seoul" });
const taskLabels: Record<string, string> = { CONTACT_CUSTOMER: "고객 연락", VERIFY_HOTEL: "호텔 회신 확인", CHECKIN_CONFIRM: "입실 확인", ORIGINAL_FOLLOWUP: "원예약 후속 업무" };
const blockerLabels: Record<string, string> = {
  RECHECK_PENDING: "예약·호텔 재확인 작업이 진행 중입니다.",
  CASE_CLOSED: "이미 종료된 사건입니다.", REPLACEMENT_RESULT_UNRESOLVED: "대체 예약 결과를 먼저 확인해야 합니다.",
  CHECKIN_MISSING: "고객 직접 입실 확인이 필요합니다.", BOOKING_NIGHTS_INCOMPLETE: "원예약의 숙박일 기록이 빠져 있습니다.",
  STAY_NOT_COVERED: "아직 보장되지 않은 숙박일이 있습니다.", REQUIRED_CHECKIN_TASK_MISSING: "필수 입실 확인 업무가 없습니다.",
  REQUIRED_ORIGINAL_FOLLOWUP_MISSING: "필수 원예약 후속 업무가 없습니다.", REQUIRED_TASK_INCOMPLETE: "필수 업무가 완료되지 않았습니다.",
  TASK_COMPLETION_PROOF_MISSING: "업무 완료 내용이 필요합니다.", CHECKIN_TASK_PROOF_MISSING: "입실 업무에 연결된 고객 확인 근거가 필요합니다.",
  ORIGINAL_FOLLOWUP_NOT_CONFIRMED: "원예약 후속 조치 완료 또는 조치 불필요 확인이 필요합니다.",
  REPLACEMENT_SCOPE_INVALID: "대체 예약과 확정 견적의 숙박 조건을 다시 확인해야 합니다.",
};

function koreaDate(timestamp: string) {
  const parts = dateParts.formatToParts(new Date(timestamp));
  return ["year", "month", "day"].map(type => parts.find(part => part.type === type)?.value).join("-");
}

function Blockers({ items }: { items: ResolutionBlocker[] }) {
  return <ul className="space-y-1 text-sm">{items.map((item, index) => <li key={`${index}:${item.code}:${item.taskId ?? ""}`}>
    {blockerLabels[item.code] ?? item.code}{item.taskId ? ` (${item.taskId})` : ""}{item.stayDates?.length ? ` · ${item.stayDates.join(", ")}` : ""}
  </li>)}</ul>;
}

function useResolutionCommand(caseId: string, customer: boolean, onRefresh: () => Promise<boolean>) {
  const [pending, setPending] = useState<ResolutionIntent>();
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [reviewRequired, setReviewRequired] = useState(false);
  const [refreshRequired, setRefreshRequired] = useState(false);
  const [blockers, setBlockers] = useState<ResolutionBlocker[]>([]);
  const request = useRef<AbortController | null>(null);
  const running = useRef(false);
  const currentCase = useRef(caseId);
  currentCase.current = caseId;

  useEffect(() => {
    setPending(undefined); setReady(false); setBusy(false); setError(""); setNotice(""); setReviewRequired(false); setRefreshRequired(false); setBlockers([]);
    try { setPending(loadResolutionIntent(caseId, customer)); setReady(true); }
    catch { setError("보관된 처리 요청을 확인할 수 없습니다. 새 요청 전에 담당자에게 확인해 주세요."); }
    return () => request.current?.abort();
  }, [caseId, customer]);

  async function refresh() {
    const loaded = await onRefresh();
    if (currentCase.current === caseId) setRefreshRequired(!loaded);
    return loaded;
  }

  async function run(command?: ResolutionCommand) {
    if (running.current || !ready || (pending && command) || (!pending && (!command || reviewRequired || refreshRequired))) return;
    const intent: ResolutionIntent = pending ?? { version: 1, key: crypto.randomUUID(), command: command! };
    try { saveResolutionIntent(caseId, customer, intent); }
    catch { setError("요청을 안전하게 보관하지 못해 처리하지 않았습니다. 브라우저 저장소를 확인해 주세요."); return; }
    setPending(intent); setBusy(true); setError(""); setNotice(""); setBlockers([]); running.current = true;
    const controller = new AbortController(); request.current = controller;
    try {
      await sendResolution(caseId, intent, controller.signal);
      if (controller.signal.aborted || currentCase.current !== caseId) return;
      try { clearResolutionIntent(caseId, customer); setPending(undefined); }
      catch { setReady(false); }
      setNotice("처리 응답을 확인했습니다. 현재 상태는 서버에서 다시 조회합니다.");
      setRefreshRequired(true);
      await refresh();
    } catch (failure) {
      if (controller.signal.aborted || currentCase.current !== caseId) return;
      if (failure instanceof ResolutionError && !failure.outcomeUnknown) {
        try { clearResolutionIntent(caseId, customer); setPending(undefined); }
        catch { setReady(false); }
        setReviewRequired(failure.code === "CASE_VERSION_CONFLICT");
        setBlockers(failure.blockers ?? []); setRefreshRequired(true);
        await refresh();
      }
      setError(failure instanceof ResolutionError && failure.code === "CASE_VERSION_CONFLICT"
        ? "다른 처리가 먼저 반영되어 사건 버전이 바뀌었습니다. 최신 상태를 확인한 뒤 다시 기록해 주세요. 기존 요청을 새 버전으로 자동 재전송하지 않습니다."
        : failure instanceof Error ? failure.message : "처리 결과 확인 불가");
    } finally {
      running.current = false;
      if (!controller.signal.aborted && currentCase.current === caseId) setBusy(false);
    }
  }

  return { pending, busy, error, notice, blockers, ready, reviewRequired, refreshRequired, run, refresh,
    blocked: !ready || busy || !!pending || reviewRequired || refreshRequired,
    acknowledgeReview: () => { if (!refreshRequired) { setReviewRequired(false); setError(""); setNotice("최신 상태를 확인했습니다. 원하는 작업을 다시 선택해 명시적으로 기록해 주세요."); } } };
}

function CommandFeedback({ state }: { state: ReturnType<typeof useResolutionCommand> }) {
  return <div className="space-y-2 text-sm">
    {state.error && <p role="alert" className="text-destructive">{state.error}</p>}
    {state.notice && <p role="status">{state.notice}</p>}
    {state.blockers.length > 0 && <Blockers items={state.blockers} />}
    {state.pending && <><p>이전 처리 결과를 보관된 같은 요청 키·본문으로 확인해야 합니다. 확인 전에는 다른 처리를 보내지 않습니다.</p>
      <Button type="button" disabled={state.busy || !state.ready} onClick={() => void state.run()}>{state.busy ? "처리 결과 확인 중…" : "같은 요청으로 처리 결과 확인"}</Button></>}
    {state.refreshRequired && <p>현재 상태를 다시 읽어야 새 작업을 진행할 수 있습니다.</p>}
    {state.reviewRequired && <Button type="button" variant="outline" disabled={state.refreshRequired || state.busy} onClick={state.acknowledgeReview}>최신 상태를 확인했습니다</Button>}
  </div>;
}

export function CustomerCheckinPanel({ caseId, booking, resolution, onRefresh }: {
  caseId: string; booking?: ReplacementBooking; resolution?: CustomerResolution; onRefresh: () => Promise<boolean>;
}) {
  const state = useResolutionCommand(caseId, true, onRefresh);
  const [confirmed, setConfirmed] = useState(false);
  useEffect(() => setConfirmed(false), [caseId, booking?.replacementBookingId]);
  const serverDate = resolution ? koreaDate(resolution.effectiveAt) : undefined;
  const inStay = !!booking && !!serverDate && serverDate >= booking.stay.checkInDate && serverDate < booking.stay.checkOutDate;
  const checkedIn = resolution?.checkin?.replacementBookingId === booking?.replacementBookingId;
  const canCheckin = !!resolution && resolution.caseStatus !== "CLOSED" && booking?.status === "CONFIRMED" && inStay && !checkedIn && confirmed && !state.blocked;
  return <section aria-label="고객 입실 확인" data-resolution-state={resolution?.caseStatus === "CLOSED" ? "CLOSED" : checkedIn ? "CHECKED_IN" : inStay ? "READY_FOR_CHECKIN" : "AWAITING_CHECKIN"}
    className="space-y-3 border-t pt-3">
    <p className="font-semibold">고객 직접 입실 확인</p>
    <p>시연 상황에서 고객 본인이 확인합니다. 호텔·공급사가 검증한 입실 기록이나 실제 결제·취소·환불 완료를 뜻하지 않습니다.</p>
    {resolution && <p className="text-xs text-muted-foreground">서버 판단 시각: {dateTime.format(new Date(resolution.effectiveAt))} (한국 시간)</p>}
    {resolution?.checkin && <p role="status">고객 입실 확인 기록: {dateTime.format(new Date(resolution.checkin.checkinAt))}</p>}
    {resolution?.closure && <p role="status">사건 종료 확인: {dateTime.format(new Date(resolution.closure.closedAt))}</p>}
    {!checkedIn && booking?.status === "CONFIRMED" && !inStay && <p>입실 확인은 서버 판단 날짜가 숙박 기간 {booking.stay.checkInDate} ~ {booking.stay.checkOutDate} (체크아웃일 제외) 안에 있을 때 가능합니다. 표시 시각과 숙박일을 확인한 뒤 새 메시지를 확인해 주세요.</p>}
    {!checkedIn && resolution?.caseStatus !== "CLOSED" && <><label className="flex items-start gap-2"><input type="checkbox" checked={confirmed} disabled={!inStay || state.blocked || booking?.status !== "CONFIRMED"} onChange={event => setConfirmed(event.target.checked)} />
      시연 상황에서 숙소에 입실했음을 확인했습니다</label>
      <Button type="button" disabled={!canCheckin} onClick={() => void state.run({ kind: "checkin", targetId: booking!.replacementBookingId, body: { checkedIn: true } })}>입실 확인</Button></>}
    <CommandFeedback state={state} />
  </section>;
}

function TaskCompletion({ task, version, blocked, onComplete }: {
  task: ResolutionTask; version: number; blocked: boolean; onComplete: (command: ResolutionCommand) => void;
}) {
  const [note, setNote] = useState("");
  const [outcome, setOutcome] = useState<FollowupOutcome | "">("");
  const [acknowledged, setAcknowledged] = useState(false);
  useEffect(() => setAcknowledged(false), [version]);
  const label = taskLabels[task.type] ?? task.type;
  const original = task.type === "ORIGINAL_FOLLOWUP";
  function submit(event: FormEvent) {
    event.preventDefault();
    if (blocked || !note.trim() || (original && (!outcome || !acknowledged))) return;
    onComplete({ kind: "task", targetId: task.taskId, body: { status: "DONE", completionNote: note.trim(), expectedVersion: version, ...(original && outcome ? { followupOutcome: outcome } : {}) } });
  }
  return <section className="space-y-2 rounded-lg border p-3" data-resolution-task-id={task.taskId}>
    <p className="font-semibold">{label} · {task.status === "DONE" ? "완료" : "미완료"}</p>
    {task.status === "DONE" ? <><p className="whitespace-pre-wrap wrap-anywhere">{task.completionNote ?? "연결된 완료 근거를 확인했습니다."}</p>
      {task.followupOutcome && <p>{task.followupOutcome === "COMPLETED" ? "시연·수동 조치 완료 확인" : "조치 불필요 사유 확인"}</p>}
      {task.completedAt && <p className="text-xs text-muted-foreground">완료 기록 {dateTime.format(new Date(task.completedAt))}</p>}</>
      : task.type === "CHECKIN_CONFIRM" ? <p>고객 본인의 직접 입실 확인으로만 완료됩니다. 상담사 메모로 대신 완료하지 않습니다.</p>
      : <form onSubmit={submit} className="space-y-3">
        <label className="block space-y-1"><span>{label} 완료 메모</span><textarea className="block w-full rounded-md border bg-background p-2" rows={3} maxLength={1000}
          value={note} disabled={blocked} onChange={event => setNote(event.target.value)} /></label>
        {original && <><p>시연·수동 후속 조치 확인을 기록합니다. 이 기능은 원예약의 실제 취소·결제·환불을 실행하지 않습니다. 요청·전달·회신 대기만 한 상태라면 완료로 기록하지 마세요.</p>
          <label className="block space-y-1"><span>{label} 후속 처리 결과</span><select className="block w-full rounded-md border bg-background p-2" value={outcome} disabled={blocked}
            onChange={event => setOutcome(event.target.value as FollowupOutcome | "")}><option value="">확인한 결과를 선택하세요</option><option value="COMPLETED">시연·수동 조치 완료 확인</option><option value="NOT_REQUIRED">조치 불필요 사유 확인</option></select></label>
          <label className="flex items-start gap-2"><input type="checkbox" checked={acknowledged} disabled={blocked} onChange={event => setAcknowledged(event.target.checked)} />시연·수동 후속 조치 기록임을 확인했습니다</label></>}
        <Button type="submit" disabled={blocked || !note.trim() || (original && (!outcome || !acknowledged))}>{label} 완료 기록</Button>
      </form>}
  </section>;
}

export function CaseResolutionPanel({ caseId, resolution, onRefresh }: { caseId: string; resolution?: CounselorResolution; onRefresh: () => Promise<boolean> }) {
  const state = useResolutionCommand(caseId, false, onRefresh);
  const [reason, setReason] = useState("");
  const [acknowledged, setAcknowledged] = useState(false);
  useEffect(() => setAcknowledged(false), [resolution?.version]);
  const closed = resolution?.caseStatus === "CLOSED";
  const blocked = state.blocked || !resolution || closed;
  return <section aria-label="입실·후속 업무·사건 종료" data-resolution-state={closed ? "CLOSED" : resolution?.canClose ? "READY_TO_CLOSE" : "OPEN"}
    data-resolution-version={resolution?.version} className="mt-5 space-y-4 rounded-xl border bg-card p-4 sm:p-6">
    <header className="flex flex-wrap items-center justify-between gap-3"><h3 className="font-semibold">입실·후속 업무·사건 종료</h3>
      <Button type="button" variant="outline" disabled={state.busy} onClick={() => void state.refresh()}>종료 조건 새로고침</Button></header>
    <CommandFeedback state={state} />
    {!resolution ? <p>서버의 종료 조건을 확인해야 업무를 기록할 수 있습니다. 종료 조건 새로고침을 눌러 주세요.</p> : <>
      <p className="text-xs text-muted-foreground">{resolution.clockMode === "SYNTHETIC_DEMO" ? "시연용 가상 판단 시각" : "서버 판단 시각"}: {dateTime.format(new Date(resolution.effectiveAt))} · 사건 버전 {resolution.version}</p>
      {resolution.checkin ? <p>고객 직접 입실 확인: {dateTime.format(new Date(resolution.checkin.checkinAt))}</p> : <p>고객 직접 입실 확인이 아직 없습니다. 예약 확정만으로 종료하지 않습니다.</p>}
      <p>{resolution.coverage.fullyCovered ? "필요한 전체 숙박일이 보장됩니다." : `미보장 숙박일: ${resolution.coverage.uncoveredStayDates.join(", ") || "숙박일 기록 확인 필요"}`}</p>
      {!closed && resolution.blockers.length > 0 && <div className="space-y-2"><p className="font-medium">종료 전에 필요한 조건</p><Blockers items={resolution.blockers} /></div>}
      <div className="space-y-3">{resolution.requiredTasks.map(task => <TaskCompletion key={task.taskId} task={task} version={resolution.version} blocked={blocked} onComplete={command => void state.run(command)} />)}</div>
      {resolution.closure ? <div className="space-y-1" role="status"><p className="font-semibold">사건 종료 완료</p><p>종료 시각 {dateTime.format(new Date(resolution.closure.closedAt))}</p><p className="whitespace-pre-wrap wrap-anywhere">종료 사유: {resolution.closure.reason}</p></div>
        : <form className="space-y-3 border-t pt-4" onSubmit={event => { event.preventDefault(); if (!blocked && resolution.canClose && acknowledged && reason.trim()) void state.run({ kind: "closure", targetId: caseId, body: { reason: reason.trim(), expectedVersion: resolution.version } }); }}>
          <label className="block space-y-1"><span>사건 종료 사유</span><textarea className="block w-full rounded-md border bg-background p-2" rows={2} maxLength={80} value={reason} disabled={blocked} onChange={event => setReason(event.target.value)} /></label>
          <label className="flex items-start gap-2"><input type="checkbox" checked={acknowledged} disabled={blocked || !resolution.canClose} onChange={event => setAcknowledged(event.target.checked)} />입실·전 숙박일 보장·필수 업무 상태를 확인했습니다</label>
          <Button type="submit" disabled={blocked || !resolution.canClose || !acknowledged || !reason.trim()}>사건 종료</Button>
        </form>}
    </>}
  </section>;
}
