"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { formatWon } from "@/lib/alternative-stays";
import { clearReplacement, getReplacement, pendingReplacement, ReplacementError, requestReplacement, saveReplacement } from "../_api/replacements";
import type { CaseCurrentSelection } from "../_types/message";
import type { ReplacementBooking, ReplacementIntent, ReplacementPointer } from "../_types/replacement";
import { CustomerCheckin } from "./case-closure-panel";

export const replacementStatusText = {
  PROCESSING: "가상 예약 처리 중",
  RESULT_UNKNOWN: "예약 결과 확인 중",
  CONFIRMED: "가상 예약 확정",
  FAILED: "예약 미생성 확인 · 새 견적 필요",
};
const dateTime = new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Seoul" });
const terminal = (booking: ReplacementBooking) => booking.status === "CONFIRMED" || booking.status === "FAILED";
const MAX_READS = 30;

export function useCaseReplacement({ caseId, customer, closed, selection, activeOfferId, pointer, refreshChat }: {
  caseId: string; customer: boolean; closed: boolean; selection: CaseCurrentSelection | null;
  activeOfferId: string | null;
  pointer: ReplacementPointer | null | undefined; refreshChat: () => Promise<boolean>;
}) {
  const [intent, setIntent] = useState<ReplacementIntent>();
  const [storageReady, setStorageReady] = useState(false);
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState("");
  const [notice, setNotice] = useState("");
  const [booking, setBooking] = useState<ReplacementBooking>();
  const [reading, setReading] = useState(false);
  const [readError, setReadError] = useState("");
  const [reads, setReads] = useState(0);
  const readRequest = useRef<AbortController | null>(null);
  const writeRequest = useRef<AbortController | null>(null);
  const sendingNow = useRef(false);
  const currentCase = useRef(caseId);
  currentCase.current = caseId;
  const intentRef = useRef(intent);
  intentRef.current = intent;

  useEffect(() => {
    setIntent(undefined); setStorageReady(false); setSending(false); setSendError(""); setNotice("");
    if (!customer) {
      try { setIntent(pendingReplacement(caseId)); setStorageReady(true); }
      catch { setSendError("보관된 예약 요청을 확인할 수 없습니다. 새 접수 전에 담당자에게 확인해 주세요."); }
    }
    return () => { writeRequest.current?.abort(); readRequest.current?.abort(); };
  }, [caseId, customer]);

  const target = pointer ?? intent?.resolved;
  const targetId = target?.replacementBookingId;
  const targetSelection = target?.selectionId;

  const read = useCallback(async () => {
    if (!targetId || !targetSelection) return;
    readRequest.current?.abort();
    const controller = new AbortController(); readRequest.current = controller;
    setReading(true); setReadError("");
    try {
      const result = await getReplacement(caseId, { replacementBookingId: targetId, selectionId: targetSelection }, controller.signal);
      if (!controller.signal.aborted && currentCase.current === caseId) setBooking(result);
    } catch (error) {
      if (!controller.signal.aborted && currentCase.current === caseId) setReadError(error instanceof Error ? error.message : "예약 처리 상태 조회 불가");
    } finally {
      if (readRequest.current === controller) { readRequest.current = null; setReading(false); }
    }
  }, [caseId, targetId, targetSelection]);

  useEffect(() => {
    setBooking(undefined); setReadError(""); setReads(targetId ? 1 : 0);
    if (targetId) void read();
    return () => { readRequest.current?.abort(); readRequest.current = null; };
  }, [read, targetId]);

  // A fresh API-011 pointer discovers a durable request even if the POST response was lost.
  useEffect(() => {
    const pending = intentRef.current;
    if (!pointer || !pending || (!pending.resolved && pending.body.selectionId !== pointer.selectionId)) return;
    try { clearReplacement(caseId); setIntent(undefined); }
    catch { setSendError("예약 접수는 확인했지만 요청 보관함을 갱신하지 못했습니다. 새 접수 전에 담당자에게 확인해 주세요."); setStorageReady(false); }
  }, [caseId, pointer?.replacementBookingId, pointer?.selectionId]);

  useEffect(() => {
    if (!booking || terminal(booking) || booking.manualRequired || reading || readError || reads >= MAX_READS) return;
    const timer = setTimeout(() => { setReads(value => value + 1); void read(); }, 3000);
    return () => clearTimeout(timer);
  }, [booking, reading, readError, reads, read]);

  const resolved = !!targetId && booking?.replacementBookingId === targetId && booking.selectionId === targetSelection;
  const readBlocked = !!targetId && (!resolved || !!readError || reading);
  const locked = !!targetId && (!resolved || booking.status !== "FAILED");
  const canRequest = !customer && !closed && storageReady && !sending && !intent && pointer !== undefined && !!selection &&
    !readBlocked && !locked && (!booking || (booking.selectionId !== selection.selectionId && booking.offerId !== selection.offerId));

  async function submit() {
    if (customer || sendingNow.current || !storageReady || intent?.resolved || (!intent && !canRequest)) return;
    const pending: ReplacementIntent = intent ?? { version: 1, key: crypto.randomUUID(), body: { selectionId: selection!.selectionId } };
    try { saveReplacement(caseId, pending); }
    catch { setSendError("요청을 안전하게 보관하지 못해 접수하지 않았습니다. 브라우저 저장소를 확인해 주세요."); return; }
    setIntent(pending); setSending(true); setSendError(""); setNotice(""); sendingNow.current = true;
    const controller = new AbortController(); writeRequest.current = controller;
    let known: ReplacementPointer | undefined;
    try {
      const receipt = await requestReplacement(caseId, pending, controller.signal);
      if (controller.signal.aborted || currentCase.current !== caseId) return;
      known = { replacementBookingId: receipt.replacementBookingId, selectionId: receipt.selectionId };
      setNotice(`예약 접수 확인: ${receipt.replacementBookingId} · 예약 확정 여부는 저장된 처리 상태에서 확인합니다.`);
    } catch (error) {
      if (controller.signal.aborted || currentCase.current !== caseId) return;
      if (error instanceof ReplacementError && error.code === "SELECTION_ALREADY_REQUESTED" && error.existingId) {
        known = { replacementBookingId: error.existingId, selectionId: pending.body.selectionId };
        setNotice("이미 접수된 선택입니다. 기존 접수번호의 처리 상태를 확인합니다.");
      } else {
        if (error instanceof ReplacementError && !error.outcomeUnknown) {
          try { clearReplacement(caseId); setIntent(undefined); }
          catch { setStorageReady(false); }
          await refreshChat();
        }
        setSendError(error instanceof Error ? error.message : "예약 접수 결과 확인 불가");
      }
    } finally {
      if (known && !controller.signal.aborted && currentCase.current === caseId) {
        const retained = { ...pending, resolved: known };
        try { saveReplacement(caseId, retained); }
        catch { setStorageReady(false); setSendError("접수번호는 확인했지만 보관함을 갱신하지 못했습니다. 대응 건 대화를 다시 조회해 주세요."); }
        setIntent(retained);
        const refreshed = await refreshChat();
        if (refreshed && !controller.signal.aborted && currentCase.current === caseId) {
          try { clearReplacement(caseId); setIntent(undefined); }
          catch { setStorageReady(false); setSendError("접수번호는 확인했지만 요청 보관함을 갱신하지 못했습니다. 새 접수 전에 담당자에게 확인해 주세요."); }
        }
      }
      sendingNow.current = false;
      if (!controller.signal.aborted && currentCase.current === caseId) setSending(false);
    }
  }

  function refresh() { setReads(1); void read(); }
  return { booking: resolved ? booking : undefined, target, reading, readError, sendError, notice, reads, intent, sending, canRequest,
    selectionBlocked: readBlocked || locked || (!!booking && booking.status === "FAILED" && booking.offerId === activeOfferId),
    submit, refresh, refreshChat };
}

export function CaseReplacementPanel({ state, customer }: { state: ReturnType<typeof useCaseReplacement>; customer: boolean }) {
  const { booking, target, reading, readError, sendError, notice, reads, intent, sending, canRequest } = state;
  return (
    <section aria-label="대체 예약 처리" className="space-y-2 border-b px-5 py-3 text-sm">
      <p className="font-semibold">대체 예약 처리</p>
      {target && <p className="wrap-anywhere text-xs text-muted-foreground">접수번호 {target.replacementBookingId}</p>}
      {reading && <p role="status">예약 처리 상태 확인 중…</p>}
      {readError && <p role="alert">{readError} · 조회 오류는 예약 실패를 의미하지 않습니다. 같은 접수번호를 유지합니다.</p>}
      {booking && <div className="space-y-1" data-replacement-status={booking.status}>
        <p role="status" className="font-medium">{replacementStatusText[booking.status]}</p>
        <p>{booking.stay.checkInDate} ~ {booking.stay.checkOutDate} · 성인 {booking.stay.adults}명 · 객실 {booking.stay.rooms}개</p>
        <p>고객 선택 당시 견적 총액 {formatWon(booking.expectedPrice.totalAmount)}</p>
        {booking.status === "RESULT_UNKNOWN" && <p>성공 여부를 확인 중입니다. 새 요청번호로 예약하지 않습니다.</p>}
        {booking.status === "CONFIRMED" && <><p>가상 예약번호 {booking.supplierBookingId} · 확정 총액 {formatWon(booking.confirmedPrice!.totalAmount)}</p><p>가상 예약 확정입니다. 실제 입실·원예약 후속 처리·대응 종료는 별도로 확인해야 합니다.</p></>}
        {booking.status === "FAILED" && <><p>실패 사유: {booking.failureCode}</p>{booking.currentPrice && <p>확인된 변경 가격 {formatWon(booking.currentPrice.totalAmount)} · 변경 가격에 자동 동의하지 않습니다.</p>}<p>새 견적 검색 → 새 제안 → 고객 재선택 후 진행해 주세요.</p></>}
        {customer && <CustomerCheckin booking={booking} onDone={state.refresh} />}
        {booking.manualRequired &&<p className="font-medium">자동 확인을 마쳤습니다. 담당자가 같은 접수번호로 추가 확인해야 합니다. 새 예약을 만들지 않습니다.</p>}
        {booking.nextCheckAt && <p className="text-xs text-muted-foreground">다음 확인 예정: <time dateTime={booking.nextCheckAt}>{dateTime.format(new Date(booking.nextCheckAt))}</time></p>}
        <p className="text-xs text-muted-foreground">{booking.clockMode === "SYNTHETIC_DEMO" ? "시연용 가상 판단 시각" : "서버 판단 시각"}: {dateTime.format(new Date(booking.effectiveAt))}</p>
      </div>}
      {target && <Button type="button" variant="outline" size="sm" disabled={reading} onClick={state.refresh}>예약 처리 상태 새로고침</Button>}
      {booking && !terminal(booking) && reads >= MAX_READS && <p>자동 상태 조회를 마쳤습니다. 같은 접수번호로 수동 새로고침해 주세요.</p>}
      {!customer && <>
        {sendError && <p role="alert">{sendError}</p>}
        {notice && <p role="status" className="wrap-anywhere">{notice}</p>}
        {intent && !intent.resolved && <p>이전 접수 결과가 확인되지 않았습니다. 선택과 요청 키를 바꾸지 않고 같은 요청으로 확인합니다.</p>}
        {intent?.resolved && <p>접수번호를 확인했습니다. 대응 건의 최신 접수를 다시 읽어야 새 작업을 판단할 수 있습니다.</p>}
        <Button type="button" disabled={sending || (!canRequest && (!intent || !!intent.resolved))} onClick={() => void state.submit()}>
          {sending ? "예약 접수 확인 중…" : intent && !intent.resolved ? "같은 요청으로 예약 접수 확인" : "대체 예약 진행"}
        </Button>
        {intent?.resolved && <Button type="button" variant="outline" onClick={() => void state.refreshChat()}>대응 건의 최신 접수 확인</Button>}
      </>}
    </section>
  );
}
