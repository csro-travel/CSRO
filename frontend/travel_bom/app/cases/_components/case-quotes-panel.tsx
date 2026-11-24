"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { getCaseQuotes, QuoteSearchError, requestCaseQuotes } from "../_api/quotes";
import { clearIntent, loadPendingIntent, OfferMutationError, persistIntent, sendCaseOffer, type OfferBody, type PendingIntent } from "../_api/offers";
import type { CaseQuoteGroup, CaseQuoteList } from "../_types/quote";

const dateTime = new Intl.DateTimeFormat("ko-KR", {
  dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Seoul",
});
const amount = new Intl.NumberFormat("ko-KR", { style: "currency", currency: "KRW", maximumFractionDigits: 0 });
const activeStatuses = new Set(["PENDING", "RUNNING", "WAITING"]);
const statusLabels = { PENDING: "접수됨", RUNNING: "검색 중", WAITING: "재시도 대기", COMPLETED: "검색 완료", FAILED: "검색 실패" };
const MAX_POLLS = 30;

function formattedTime(value: string) {
  return dateTime.format(new Date(value));
}

function pendingKey(caseId: string) { return `csro.quote-request.v1:${caseId}`; }

function rememberedRequest(caseId: string): string | undefined {
  try {
    const saved: unknown = JSON.parse(sessionStorage.getItem(pendingKey(caseId)) ?? "null");
    if (typeof saved === "object" && saved !== null && "key" in saved &&
        typeof saved.key === "string" && /^[A-Za-z0-9._:-]{1,100}$/.test(saved.key)) return saved.key;
  } catch { /* Storage may be disabled; the mounted panel still keeps its key. */ }
}

function rememberRequest(caseId: string, key?: string) {
  try {
    if (key) sessionStorage.setItem(pendingKey(caseId), JSON.stringify({ key }));
    else sessionStorage.removeItem(pendingKey(caseId));
  } catch { /* No credentials or booking facts are written to browser storage. */ }
}

function QuoteGroup({ group, selectedIds, disabled, onToggle }: {
  group: CaseQuoteGroup; selectedIds: string[]; disabled: boolean; onToggle: (quoteId: string) => void;
}) {
  const nights = Math.round((Date.parse(group.stay.checkOutDate) - Date.parse(group.stay.checkInDate)) / 86400000);
  return (
    <section className="space-y-3 rounded-lg border p-4">
      <h4 className="font-semibold">{group.coverageMode === "FULL_STAY" ? "전체 숙박기간 후보" : "장애가 확인된 숙박일의 후보"}</h4>
      <p className="text-sm">{group.stay.checkInDate} ~ {group.stay.checkOutDate} · {nights}박 · 성인 {group.stay.adults}명 · 객실 {group.stay.rooms}개</p>
      {group.remainingUnconfirmedStayDates.length > 0 && <p className="text-sm text-muted-foreground">원래 호텔 확인 필요: {group.remainingUnconfirmedStayDates.join(", ")}</p>}
      {!group.rankingAvailable && group.quotes.length > 0 && <p className="text-sm text-muted-foreground">비교 결과 없이 공급사 원본 견적을 표시합니다.</p>}
      {group.quotes.length === 0 ? <p className="text-sm">이 기간과 조건에 맞는 견적이 없습니다.</p> : (
        <ul className="space-y-3">
          {group.quotes.map(quote => (
            <li key={quote.quoteId} data-quote-id={quote.quoteId} className="space-y-2 rounded-lg bg-muted/50 p-3">
              <label className="flex items-center gap-2 text-sm font-medium">
                <input type="checkbox" checked={selectedIds.includes(quote.quoteId)}
                  disabled={disabled || quote.expired === true || !quote.propertyId || (!selectedIds.includes(quote.quoteId) && (selectedIds.length >= 3 || group.quotes.some(other => selectedIds.includes(other.quoteId) && other.propertyId === quote.propertyId)))}
                  onChange={() => onToggle(quote.quoteId)} />
                {quote.propertyName} 고객 제안에 포함
              </label>
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <h5 className="font-medium wrap-anywhere">{quote.propertyName} · {quote.roomName}</h5>
                <span className="font-semibold tabular-nums">{nights}박 총액 {amount.format(quote.totalAmount)}</span>
              </div>
              <p className="text-sm">기존 호텔에서 {(quote.distanceMeters / 1000).toLocaleString("ko-KR", { maximumFractionDigits: 3 })}km · 가상 직선 거리</p>
              {quote.priceDifference !== null && <p className="text-sm">동일 기간 원예약 대비 견적 차이: {quote.priceDifference > 0 ? "+" : ""}{amount.format(quote.priceDifference)} <span className="text-muted-foreground">(추가 부담·환급 확정 금액 아님)</span></p>}
              {quote.reason && <p className="text-sm">{quote.reason}</p>}
              <p className="text-xs text-muted-foreground">조회 <time dateTime={quote.checkedAt}>{formattedTime(quote.checkedAt)}</time> · 유효 기한 <time dateTime={quote.expiresAt}>{formattedTime(quote.expiresAt)}</time></p>
              {quote.expired === true && <p className="text-sm font-medium text-destructive">견적 유효 기한 경과 · 새 검색 필요</p>}
            </li>
          ))}
        </ul>
      )}
      {group.warnings.length > 0 && <ul className="space-y-1 text-sm text-muted-foreground">{group.warnings.map((warning, index) => <li key={`${index}:${warning}`}>{warning}</li>)}</ul>}
    </section>
  );
}

export function CaseQuotesPanel({ caseId, closed, onOfferSent }: { caseId: string; closed: boolean; onOfferSent?: () => void }) {
  const [data, setData] = useState<CaseQuoteList>();
  const [loading, setLoading] = useState(true);
  const [readError, setReadError] = useState("");
  const [sendError, setSendError] = useState("");
  const [notice, setNotice] = useState("");
  const [sending, setSending] = useState(false);
  const [retryKey, setRetryKey] = useState<string>();
  const [polls, setPolls] = useState(0);
  const readRequest = useRef<AbortController | undefined>(undefined);
  const sendRequest = useRef<AbortController | undefined>(undefined);
  const requestNumber = useRef(0);
  const sendingNow = useRef(false);
  const retainedKey = useRef<string | undefined>(undefined);
  const [chosen, setChosen] = useState<{ group: number; ids: string[] }>({ group: -1, ids: [] });
  const [pendingOffer, setPendingOffer] = useState<PendingIntent<OfferBody>>();
  const [offering, setOffering] = useState(false);
  const [offerError, setOfferError] = useState("");
  const [offerStorageReady, setOfferStorageReady] = useState(false);
  const offerRequest = useRef<AbortController | undefined>(undefined);
  const offeringNow = useRef(false);

  const refresh = useCallback(async () => {
    readRequest.current?.abort();
    const controller = new AbortController();
    readRequest.current = controller;
    const number = ++requestNumber.current;
    setLoading(true);
    setReadError("");
    try {
      const result = await getCaseQuotes(caseId, controller.signal);
      if (!controller.signal.aborted && number === requestNumber.current) setData(result);
    } catch (error) {
      if (!controller.signal.aborted && number === requestNumber.current) {
        setData(undefined);
        setReadError(error instanceof Error ? error.message : "견적 조회 실패");
      }
    } finally {
      if (!controller.signal.aborted && number === requestNumber.current) setLoading(false);
    }
  }, [caseId]);

  useEffect(() => {
    setData(undefined); setReadError(""); setSendError(""); setNotice(""); setPolls(0);
    setChosen({ group: -1, ids: [] }); setPendingOffer(undefined); setOffering(false); setOfferError(""); setOfferStorageReady(false);
    try { setPendingOffer(loadPendingIntent<OfferBody>("offer", caseId)); setOfferStorageReady(true); }
    catch { setOfferError("요청 보관함을 확인할 수 없어 새 제안을 전송할 수 없습니다. 담당자에게 확인해 주세요."); }
    retainedKey.current = rememberedRequest(caseId);
    setRetryKey(retainedKey.current);
    void refresh();
    return () => { readRequest.current?.abort(); sendRequest.current?.abort(); offerRequest.current?.abort(); };
  }, [caseId, refresh]);

  const status = data?.searchJob?.status;
  const inProgress = status !== undefined && activeStatuses.has(status);

  useEffect(() => {
    if (!inProgress || loading || readError || polls >= MAX_POLLS) return;
    const timer = setTimeout(() => { setPolls(value => value + 1); void refresh(); }, 3000);
    return () => clearTimeout(timer);
  }, [inProgress, loading, readError, polls, refresh]);

  async function search() {
    if (sendingNow.current || closed || (inProgress && !retainedKey.current)) return;
    sendingNow.current = true;
    const controller = new AbortController();
    sendRequest.current = controller;
    const key = retainedKey.current ?? crypto.randomUUID();
    retainedKey.current = key;
    setRetryKey(key); rememberRequest(caseId, key);
    setSending(true); setSendError(""); setNotice("");
    try {
      await requestCaseQuotes(caseId, key, controller.signal);
      if (controller.signal.aborted) return;
      retainedKey.current = undefined; setRetryKey(undefined); rememberRequest(caseId);
      setNotice("검색을 접수했습니다. 완료되면 저장된 견적을 표시합니다.");
      setPolls(0);
      await refresh();
    } catch (error) {
      if (controller.signal.aborted) return;
      if (!(error instanceof QuoteSearchError) || !error.outcomeUnknown) {
        retainedKey.current = undefined; setRetryKey(undefined); rememberRequest(caseId);
      }
      setSendError(error instanceof Error ? error.message : "견적 검색 접수 실패");
      if (error instanceof QuoteSearchError && error.code === "QUOTE_SEARCH_IN_PROGRESS") await refresh();
    } finally {
      sendingNow.current = false;
      if (!controller.signal.aborted) setSending(false);
    }
  }

  function toggleQuote(groupIndex: number, quoteId: string) {
    if (offeringNow.current || pendingOffer) return;
    setChosen(current => {
      if (current.group !== groupIndex) return { group: groupIndex, ids: [quoteId] };
      return { group: groupIndex, ids: current.ids.includes(quoteId) ? current.ids.filter(id => id !== quoteId) : [...current.ids, quoteId] };
    });
  }

  const group = data?.groups[chosen.group];
  const selectedQuotes = group?.quotes.filter(quote => chosen.ids.includes(quote.quoteId)) ?? [];
  const canOffer = !closed && !loading && !readError && !inProgress && data?.searchJob?.status === "COMPLETED" &&
    selectedQuotes.length === 3 && selectedQuotes.every(quote => quote.propertyId && quote.expired !== true) &&
    new Set(selectedQuotes.map(quote => quote.propertyId)).size === 3;

  async function offer() {
    if (offeringNow.current || !offerStorageReady || (!pendingOffer && !canOffer)) return;
    const intent: PendingIntent<OfferBody> = pendingOffer ?? { version: 1, key: crypto.randomUUID(), body: { quoteIds: [...chosen.ids] } };
    try { persistIntent("offer", caseId, intent); }
    catch { setOfferError("요청을 안전하게 보관하지 못해 전송하지 않았습니다. 브라우저 저장소를 확인해 주세요."); return; }
    setPendingOffer(intent); setOffering(true); setOfferError(""); setNotice(""); offeringNow.current = true;
    const controller = new AbortController(); offerRequest.current = controller;
    try {
      await sendCaseOffer(caseId, intent, controller.signal);
      if (controller.signal.aborted) return;
      clearIntent("offer", caseId); setPendingOffer(undefined); setChosen({ group: -1, ids: [] });
      setNotice("제안 전송 처리를 확인했습니다. 채팅에서 서버의 현재 제안과 선택을 다시 확인합니다.");
      onOfferSent?.();
    } catch (error) {
      if (controller.signal.aborted) return;
      if (error instanceof OfferMutationError && !error.outcomeUnknown) {
        clearIntent("offer", caseId); setPendingOffer(undefined);
      }
      setOfferError(error instanceof Error ? error.message : "제안 전송 실패");
    } finally { offeringNow.current = false; if (!controller.signal.aborted) setOffering(false); }
  }

  return (
    <section aria-label="대체 숙소 견적" className="space-y-4 rounded-xl border bg-card p-4 sm:p-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div><h3 className="text-lg font-semibold">{caseId} 대체 숙소 견적</h3><p className="text-sm text-muted-foreground">시연용 가상 견적 · 같은 기간의 서로 다른 숙소 3곳을 고객에게 제안합니다. 전송은 예약 확정이 아닙니다.</p></div>
        <div className="flex flex-wrap gap-2">
          <button className="min-h-10 rounded-md border px-3 text-sm disabled:opacity-50" disabled={loading} onClick={() => { setPolls(0); void refresh(); }}>새로고침</button>
          <button className="min-h-10 rounded-md bg-primary px-3 text-sm text-primary-foreground disabled:opacity-50" disabled={sending || closed || offering || !!pendingOffer || (!retryKey && (loading || !!readError || inProgress))} onClick={() => void search()}>{sending ? "검색 접수 중…" : retryKey ? "같은 요청으로 접수 확인" : "대체 숙소 검색"}</button>
        </div>
      </div>
      {closed && <p className="text-sm text-muted-foreground">종료된 사건은 견적 이력만 조회할 수 있습니다.</p>}
      {loading && <p role="status">견적 조회 중…</p>}
      {readError && <p role="alert">{readError}</p>}
      {sendError && <p role="alert">{sendError}</p>}
      {notice && <p role="status">{notice}</p>}
      {offerError && <p role="alert">{offerError}</p>}
      {pendingOffer && <p className="text-sm text-muted-foreground">이전 제안의 처리 결과를 같은 요청으로 확인해야 합니다. 확인 전에는 제안 대상을 바꿀 수 없습니다.</p>}
      {retryKey && !sending && <p className="text-sm text-muted-foreground">이전 검색 접수 결과가 확인되지 않았습니다. 같은 요청으로 확인하면 중복 접수를 방지할 수 있습니다.</p>}
      {data && !readError && <>
        {data.effectiveAt && <p className="text-sm text-muted-foreground">{data.clockMode === "SYNTHETIC_DEMO" ? "시연용 가상 판단 시각" : "견적 판단 시각"}: <time dateTime={data.effectiveAt}>{formattedTime(data.effectiveAt)}</time> (한국 시간)</p>}
        {data.searchJob === null ? <p>아직 검색하지 않았습니다. 대체 숙소 검색을 눌러 주세요.</p> : <>
          <p role={inProgress ? "status" : undefined}>검색 상태: {statusLabels[data.searchJob.status]}</p>
          {data.searchJob.status === "FAILED" && <p role="alert">검색을 완료하지 못했습니다{data.searchJob.errorCode ? ` (${data.searchJob.errorCode})` : ""}. 원인을 확인한 뒤 다시 검색해 주세요.</p>}
          {data.searchJob.status === "COMPLETED" && data.groups.every(group => group.quotes.length === 0) && <p>조건에 맞는 대체 숙소 견적이 없습니다.</p>}
          {inProgress && polls >= MAX_POLLS && <p>자동 조회를 마쳤습니다. 검색 상태는 새로고침으로 다시 확인할 수 있습니다.</p>}
          {data.groups.map((group, index) => <QuoteGroup key={`${index}:${group.coverageMode}:${group.stay.checkInDate}:${group.stay.checkOutDate}`} group={group}
            selectedIds={chosen.group === index ? chosen.ids : []}
            disabled={closed || offering || !!pendingOffer || !offerStorageReady || loading || inProgress || data.searchJob?.status !== "COMPLETED"}
            onToggle={quoteId => toggleQuote(index, quoteId)} />)}
        </>}
      </>}
      <div className="space-y-2 border-t pt-4">
        <p className="text-sm">고객에게 보낼 숙소 {chosen.ids.length}/3곳 · 다른 기간 그룹을 선택하면 기존 선택이 바뀝니다.</p>
        <button className="min-h-10 rounded-md bg-primary px-4 text-sm text-primary-foreground disabled:opacity-50"
          disabled={offering || !offerStorageReady || (!pendingOffer && !canOffer)} onClick={() => void offer()}>
          {offering ? "제안 처리 확인 중…" : pendingOffer ? "같은 요청으로 제안 전송 확인" : "선택한 3곳 고객에게 제안"}
        </button>
      </div>
    </section>
  );
}
