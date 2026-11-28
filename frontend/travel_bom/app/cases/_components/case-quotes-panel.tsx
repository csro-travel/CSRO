"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { MapPin, RefreshCw, Search, Send, Wallet } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { formatWon, type AlternativeStay } from "@/lib/alternative-stays";
import { sendAirbnbSearchGuide } from "../_api/cases";
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
  // A strength badge goes to one quote only when its value is strictly the best; a tie gets none.
  const only = (value: (quote: CaseQuoteGroup["quotes"][number]) => number) => {
    const best = Math.min(...group.quotes.map(value));
    const winners = group.quotes.filter(quote => value(quote) === best);
    return group.quotes.length > 1 && winners.length === 1 ? winners[0].quoteId : null;
  };
  const nearest = only(quote => quote.distanceMeters);
  const cheapest = only(quote => quote.totalAmount);
  return (
    <section className="space-y-3 rounded-lg border p-4">
      <h4 className="font-semibold">{group.coverageMode === "FULL_STAY" ? "전체 숙박기간 후보" : "장애가 확인된 숙박일의 후보"}</h4>
      <p className="text-sm">{group.stay.checkInDate} ~ {group.stay.checkOutDate} · {nights}박 · 성인 {group.stay.adults}명 · 객실 {group.stay.rooms}개</p>
      {group.remainingUnconfirmedStayDates.length > 0 && <p className="text-sm text-muted-foreground">원래 호텔 확인 필요: {group.remainingUnconfirmedStayDates.join(", ")}</p>}
      {!group.rankingAvailable && group.quotes.length > 0 && <p className="text-sm text-muted-foreground">비교 결과 없이 공급사 원본 견적을 표시합니다.</p>}
      {group.quotes.length === 0 ? <p className="text-sm">이 기간과 조건에 맞는 견적이 없습니다.</p> : (
        <ul className="grid gap-3 [grid-template-columns:repeat(auto-fit,minmax(15rem,1fr))]">
          {group.quotes.map((quote, index) => {
            const picked = selectedIds.includes(quote.quoteId);
            const original = quote.priceDifference === null ? null : quote.totalAmount - quote.priceDifference;
            return (
              <li key={quote.quoteId} data-quote-id={quote.quoteId}
                className={`flex flex-col gap-2.5 rounded-lg border bg-card p-3 ${picked ? "border-primary ring-2 ring-primary/25" : ""}`}>
                <div className="flex flex-wrap items-center gap-1.5">
                  {group.rankingAvailable && <Badge tone="brand" className="text-sm">비교 {index + 1}순위</Badge>}
                  {nearest === quote.quoteId && <Badge tone="info" className="text-sm"><MapPin aria-hidden="true" />최단 거리</Badge>}
                  {cheapest === quote.quoteId && <Badge tone="success" className="text-sm"><Wallet aria-hidden="true" />{quote.priceDifference === null ? "최저 총액" : "추가금 최소"}</Badge>}
                  {quote.expired === true && <Badge tone="danger" className="text-sm">견적 기한 경과</Badge>}
                </div>
                <div>
                  <h5 className="text-base font-bold wrap-anywhere">{quote.propertyName}</h5>
                  <p className="text-sm text-muted-foreground wrap-anywhere">{quote.roomName} · {nights}박 예약 가능</p>
                </div>
                <dl className="grid grid-cols-2 gap-2">
                  <div className="rounded-md bg-muted/60 px-2.5 py-2">
                    <dt className="text-xs text-muted-foreground">기존 숙소에서</dt>
                    <dd className="num text-xl font-bold">{(quote.distanceMeters / 1000).toLocaleString("ko-KR", { maximumFractionDigits: 1 })}km</dd>
                    <dd className="text-[0.7rem] text-muted-foreground">가상 직선 거리</dd>
                  </div>
                  <div className="rounded-md bg-muted/60 px-2.5 py-2">
                    <dt className="text-xs text-muted-foreground">{quote.priceDifference === null ? `${nights}박 총액` : quote.priceDifference > 0 ? "추가 비용" : quote.priceDifference < 0 ? "비용 절감" : "차액 없음"}</dt>
                    <dd className={`num text-xl font-bold ${quote.priceDifference !== null && quote.priceDifference > 0 ? "text-accent-foreground" : quote.priceDifference !== null && quote.priceDifference < 0 ? "text-[oklch(0.4_0.1_158)]" : ""}`}>
                      {quote.priceDifference === null ? amount.format(quote.totalAmount) : `${quote.priceDifference > 0 ? "+" : ""}${amount.format(quote.priceDifference)}`}
                    </dd>
                    <dd className="text-[0.7rem] text-muted-foreground">견적 차이 · 확정 금액 아님</dd>
                  </div>
                </dl>
                <p className="num text-sm">
                  {original !== null && <><span className="text-muted-foreground">기존 예약 {amount.format(original)} → </span></>}
                  <strong>{nights}박 총액 {amount.format(quote.totalAmount)}</strong>
                </p>
                {quote.reason && <p className="rounded-md border-l-4 border-primary bg-accent px-2.5 py-1.5 text-sm font-medium text-accent-foreground">{quote.reason}</p>}
                <p className="mt-auto text-xs text-muted-foreground">조회 <time dateTime={quote.checkedAt}>{formattedTime(quote.checkedAt)}</time> · 유효 기한 <time dateTime={quote.expiresAt}>{formattedTime(quote.expiresAt)}</time></p>
                <label className="flex items-center gap-2 border-t pt-2.5 text-sm font-semibold">
                  <Checkbox checked={picked}
                    disabled={disabled || quote.expired === true || !quote.propertyId || (!picked && (selectedIds.length >= 3 || group.quotes.some(other => selectedIds.includes(other.quoteId) && other.propertyId === quote.propertyId)))}
                    onCheckedChange={() => onToggle(quote.quoteId)} />
                  {quote.propertyName} 고객 제안에 포함
                </label>
              </li>
            );
          })}
        </ul>
      )}
      {group.warnings.length > 0 && <div className="space-y-1 text-sm text-muted-foreground">
        <p className="font-medium">견적 검색·비교 당시 안내</p>
        <ul className="space-y-1">{group.warnings.map((warning, index) => <li key={`${index}:${warning}`}>{warning}</li>)}</ul>
      </div>}
    </section>
  );
}

export function CaseQuotesPanel({ caseId, closed, onOfferSent, airbnbCandidates, airbnbSelectionSent }: {
  caseId: string;
  closed: boolean;
  onOfferSent?: () => void;
  airbnbCandidates: AlternativeStay[];
  airbnbSelectionSent: boolean;
}) {
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
  const airbnbRequest = useRef<AbortController | null>(null);
  const [airbnbSending, setAirbnbSending] = useState(false);
  const [airbnbError, setAirbnbError] = useState<{ selection: string; message: string }>();
  const [submittedAirbnbSelections, setSubmittedAirbnbSelections] = useState<string[]>([]);

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
    return () => { readRequest.current?.abort(); sendRequest.current?.abort(); offerRequest.current?.abort(); airbnbRequest.current?.abort(); };
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

  const airbnbSearchId = airbnbCandidates[0]?.searchId;
  const listingIds = airbnbCandidates.map(stay => stay.id).sort();
  const completeAirbnbSelection = !!airbnbSearchId && airbnbCandidates.length === 3 &&
    new Set(listingIds).size === 3 &&
    airbnbCandidates.every(stay => stay.searchId === airbnbSearchId && stay.listingUrl && stay.totalPrice !== null);
  const airbnbSelection = JSON.stringify([airbnbSearchId, listingIds]);
  const airbnbAlreadySubmitted = airbnbSelectionSent || submittedAirbnbSelections.includes(airbnbSelection);

  async function sendAirbnbGuide() {
    if (closed || !airbnbSearchId || !completeAirbnbSelection || airbnbAlreadySubmitted || airbnbRequest.current) return;
    const request = new AbortController();
    airbnbRequest.current = request;
    setAirbnbSending(true);
    setAirbnbError(undefined);
    try {
      await sendAirbnbSearchGuide(caseId, airbnbSearchId, listingIds, request.signal);
      if (request.signal.aborted) return;
      setSubmittedAirbnbSelections(current => [...current, airbnbSelection]);
      onOfferSent?.();
    } catch (error) {
      if (request.signal.aborted) return;
      setAirbnbError({ selection: airbnbSelection,
        message: error instanceof Error ? error.message : "숙소 안내를 전송하지 못했습니다." });
      onOfferSent?.();
    } finally {
      airbnbRequest.current = null;
      if (!request.signal.aborted) setAirbnbSending(false);
    }
  }

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
    <section aria-label="대체 숙소 추천" className="panel space-y-4 p-4">
      <div className="space-y-2 border-b pb-4">
        <h3 className="text-base font-semibold">Airbnb 탐색 결과에서 고른 숙소</h3>
      <p role="status" className="text-sm font-medium">고객에게 보낼 숙소 {airbnbCandidates.length}/3곳</p>
      {airbnbCandidates.length > 0 ? (
        <ul className="space-y-1 text-sm">
          {airbnbCandidates.map(stay => <li key={stay.id}>{stay.name} · {formatWon(stay.totalPrice)}</li>)}
        </ul>
      ) : <p className="text-sm text-muted-foreground">상단 자동 탐색 결과에서 숙소를 선택해 주세요.</p>}
      <p className="text-sm text-muted-foreground">숙소명, 조회 총액, 링크를 고객 채팅에 보냅니다.</p>
      {airbnbCandidates.length === 3 && !completeAirbnbSelection && (
        <p className="text-sm text-muted-foreground">저장된 검색에서 요금과 Airbnb 링크가 있는 숙소 3곳을 선택해 주세요.</p>
      )}
      <Button type="button" disabled={closed || !completeAirbnbSelection || airbnbSending || airbnbAlreadySubmitted}
        onClick={() => void sendAirbnbGuide()}>
        <Send aria-hidden="true" />
        {airbnbSending ? "안내 전송 중…" : "고객에게 제안"}
      </Button>
      {airbnbAlreadySubmitted && <p role="status">선택한 숙소 3곳을 고객 채팅에 안내했습니다.</p>}
      {airbnbError?.selection === airbnbSelection && <p role="alert">{airbnbError.message}</p>}
      </div>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div><h3 className="text-base font-semibold">{caseId} 공급사 견적 추천</h3><p className="text-sm text-muted-foreground">시연용 가상 견적 · 같은 기간의 서로 다른 숙소 3곳을 고객에게 제안합니다. 전송은 예약 확정이 아닙니다.</p></div>
        <div className="flex flex-wrap gap-2">
          <Button type="button" variant="outline" disabled={loading} onClick={() => { setPolls(0); void refresh(); }}><RefreshCw aria-hidden="true" />새로고침</Button>
          <Button type="button" disabled={sending || closed || offering || !!pendingOffer || (!retryKey && (loading || !!readError || inProgress))} onClick={() => void search()}><Search aria-hidden="true" />{sending ? "검색 접수 중…" : retryKey ? "같은 요청으로 접수 확인" : "대체 숙소 검색"}</Button>
        </div>
      </div>
      {closed && <p className="text-sm text-muted-foreground">종료된 상담은 견적 이력만 조회할 수 있습니다.</p>}
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
        <p className="text-xs text-muted-foreground">순위·강점 배지·추천 사유는 상담사용입니다. 고객 제안 카드에는 숙소명·기간·거리·가격만 나갑니다. 숙소 이미지·상세 페이지 링크·이동 시간은 견적 데이터에 없어 표시하지 않으며, 제안은 서로 다른 숙소 3곳 단위로만 전송됩니다.</p>
        <Button type="button" size="lg"
          disabled={offering || !offerStorageReady || (!pendingOffer && !canOffer)} onClick={() => void offer()}>
          <Send aria-hidden="true" />
          {offering ? "제안 처리 확인 중…" : pendingOffer ? "같은 요청으로 제안 전송 확인" : "추천 숙소 3곳 전체 전송"}
        </Button>
      </div>
    </section>
  );
}
