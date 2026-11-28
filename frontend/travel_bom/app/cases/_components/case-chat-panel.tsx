"use client";

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from "react";
import { CircleCheck, SendHorizontal } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/input";
import { AirbnbDemoBookingCard } from "./airbnb-demo-booking-card";
import { parseAirbnbGuide } from "@/lib/airbnb-guide";
import { formatWon } from "@/lib/alternative-stays";
import { CaseMessageSendError, getCaseMessages, sendCaseMessage } from "../_api/cases";
import { clearIntent, loadPendingIntent, OfferMutationError, persistIntent, selectCaseOffer, type PendingIntent, type SelectionBody } from "../_api/offers";
import { replacementPointer } from "../_api/replacements";
import { CaseReplacementPanel, replacementStatusText, useCaseReplacement } from "./case-replacement-panel";
import type { CaseChatMessage, CaseChatState, CaseMessageList } from "../_types/message";

const senderLabels = {
  CUSTOMER: "고객",
  COUNSELOR: "상담사",
  SYSTEM: "시스템",
} satisfies Record<CaseChatMessage["senderType"], string>;

const messageBubbleStyles = {
  CUSTOMER: "mr-auto rounded-tl-sm border-border bg-card text-card-foreground",
  COUNSELOR: "ml-auto rounded-tr-sm border-accent bg-accent text-foreground",
  SYSTEM: "mx-auto border-border bg-secondary text-secondary-foreground",
} satisfies Record<CaseChatMessage["senderType"], string>;

const messageDateFormat = new Intl.DateTimeFormat("ko-KR", {
  dateStyle: "full",
  timeZone: "Asia/Seoul",
});

const messageTimeFormat = new Intl.DateTimeFormat("ko-KR", {
  timeStyle: "short",
  timeZone: "Asia/Seoul",
});

const offerExpiryFormat = new Intl.DateTimeFormat("ko-KR", {
  dateStyle: "medium",
  timeStyle: "short",
  timeZone: "Asia/Seoul",
});

function formatTimestamp(value: string, formatter: Intl.DateTimeFormat) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : formatter.format(date);
}

function groupMessagesByDate(messages: CaseChatMessage[]) {
  const groups = new Map<string | null, CaseChatMessage[]>();
  for (const message of messages) {
    const date = formatTimestamp(message.sentAt, messageDateFormat);
    const group = groups.get(date);
    if (group) group.push(message);
    else groups.set(date, [message]);
  }
  return groups;
}

function MessageItem({ message, state, customer, selecting, selectionBlocked, onSelect, caseId, closed, onCompose }: {
  caseId: string; closed: boolean; onCompose: (text: string) => void;
  message: CaseChatMessage; state: CaseChatState | null; customer: boolean; selecting: boolean;
  selectionBlocked: boolean; onSelect: (offerId: string, quoteId: string) => void;
}) {
  const { body, offer, senderType, sentAt } = message;
  const demoCheckin = senderType === "CUSTOMER" && message.messageId.startsWith("MSG-ADB-CHECKIN-");
  const airbnbGuide = body && senderType === "COUNSELOR" && message.messageType === "TEXT"
    ? parseAirbnbGuide(body) : null;
  const active = offer !== null && (state ? state.activeOfferId === offer.offerId : offer.status === "ACTIVE");

  return (
    <li
      data-chat-message-id={message.messageId}
      className={`w-fit min-w-0 space-y-2 rounded-2xl border px-3.5 py-2.5 ${offer || airbnbGuide ? "max-w-full" : "max-w-[85%]"} ${messageBubbleStyles[senderType]}`}
    >
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <span className={`text-xs font-semibold ${senderType === "COUNSELOR" ? "text-accent-foreground" : ""}`}>
          {senderLabels[senderType]}
        </span>
        <time dateTime={sentAt} className="text-[11px] whitespace-nowrap text-muted-foreground tabular-nums">
          {formatTimestamp(sentAt, messageTimeFormat) ?? sentAt}
        </time>
      </div>
      {demoCheckin ? (
        <div className="flex min-w-36 flex-col items-center gap-2 rounded-xl border bg-background px-5 py-4 text-center">
          <CircleCheck className="size-8 text-primary" aria-hidden="true" />
          <p className="font-semibold">입실 완료!</p>
          <span className="text-xs text-muted-foreground">앱 자동 알림 · 시연</span>
        </div>
      ) : airbnbGuide ? (
        <div className="space-y-3 text-sm">
          <p>{airbnbGuide.introduction}</p>
          <ol className="space-y-2">
            {airbnbGuide.items.map((stay, index) => (
              <li key={index} className="space-y-2 rounded-xl border bg-card p-3 text-card-foreground">
                <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
                  <h3 className="min-w-0 font-semibold wrap-anywhere">{stay.name}</h3>
                  <span className="font-semibold text-primary tabular-nums">{stay.price}</span>
                </div>
                {stay.highlight && <p className="leading-relaxed text-muted-foreground">{stay.highlight}</p>}
                <a href={stay.url} target="_blank" rel="noopener noreferrer"
                  aria-label={`${stay.name} Airbnb에서 보기`}
                  className="inline-block rounded-md border px-3 py-1.5 text-sm font-medium text-primary hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring">
                  Airbnb에서 보기
                </a>
              </li>
            ))}
          </ol>
          <p className="text-xs leading-relaxed text-muted-foreground">{airbnbGuide.reminder}</p>
        </div>
      ) : body && (
        <p className="whitespace-pre-wrap wrap-anywhere text-sm">
          {body.split("\n").map((line, index) => (
            <span key={index}>
              {index > 0 && "\n"}
              {/^https:\/\/[^\s]+$/.test(line)
                ? <a href={line} target="_blank" rel="noopener noreferrer" className="underline">{line}</a>
                : line}
            </span>
          ))}
        </p>
      )}
      {!customer && senderType === "COUNSELOR" && /^ACT-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(message.messageId) && (
        <AirbnbDemoBookingCard caseId={caseId} offerId={message.messageId.slice(4)} closed={closed} onCompose={onCompose} />
      )}
      {offer && (
        <div className="space-y-2">
          <p className="text-sm font-semibold">
            {active ? "대체 숙소 제안" : "이전 대체 숙소 제안"}
          </p>
          <ul className="space-y-2">
            {offer.items.map((item) => (
              <li key={item.quoteId} className="space-y-1 rounded-md bg-muted/60 p-2">
                <div className="flex flex-wrap justify-between gap-1 text-sm font-medium">
                  <span className="wrap-anywhere">{item.propertyName}</span>
                  <span>{formatWon(item.totalAmount)}</span>
                </div>
                <p className="text-xs text-muted-foreground">
                  {item.checkInDate} ~ {item.checkOutDate} · 성인 {item.adults}명 · 객실 {item.rooms}개
                </p>
                <p className="text-xs text-muted-foreground">기존 호텔에서 {(item.distanceMeters / 1000).toLocaleString("ko-KR", { maximumFractionDigits: 3 })}km</p>
                <p className="text-xs text-muted-foreground">{item.priceDifference === null ? "동일 기간 원예약 차액 비교 없음" : `동일 기간 원예약 대비 견적 차이: ${item.priceDifference > 0 ? "+" : ""}${formatWon(item.priceDifference)} (추가 부담·환급 확정 금액 아님)`}</p>
                <p className="text-xs text-muted-foreground">조회 <time dateTime={item.checkedAt}>{formatTimestamp(item.checkedAt, offerExpiryFormat) ?? item.checkedAt}</time></p>
                <p className="text-xs text-muted-foreground">
                  견적 기한{" "}
                  <time dateTime={item.expiresAt}>
                    {formatTimestamp(item.expiresAt, offerExpiryFormat) ?? item.expiresAt}
                  </time>
                </p>
                {customer && (
                  <Button type="button" size="sm"
                    disabled={!state || !active || selecting || selectionBlocked || (state.currentSelection?.offerId === offer.offerId && state.currentSelection.quoteId === item.quoteId) || Date.parse(item.expiresAt) <= Date.parse(state.effectiveAt)}
                    onClick={() => onSelect(offer.offerId, item.quoteId)}>
                    {state?.currentSelection?.offerId === offer.offerId && state.currentSelection.quoteId === item.quoteId ? "현재 선택한 숙소" : !active ? "이전 제안 · 선택 불가" : state && Date.parse(item.expiresAt) <= Date.parse(state.effectiveAt) ? "견적 기한 경과" : `${item.propertyName} 선택`}
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </li>
  );
}

type HistoryResult = {
  items: CaseChatMessage[];
  beforeMessageId: string | null;
};

function readChatState(response: CaseMessageList): CaseChatState | null {
  const isText = (value: unknown): value is string => typeof value === "string" && value.trim().length > 0;
  const isTime = (value: unknown): value is string => isText(value) && /(Z|[+-]\d{2}:\d{2})$/.test(value) && Number.isFinite(Date.parse(value));
  if (!(response.activeOfferId === null || isText(response.activeOfferId)) || !isTime(response.effectiveAt) ||
      !(response.clockMode === "REAL_TIME" || response.clockMode === "SYNTHETIC_DEMO")) return null;
  const choice = response.currentSelection;
  if (choice !== null && (!choice || !isText(choice.selectionId) || !isText(choice.offerId) || !isText(choice.quoteId) || !isTime(choice.selectedAt) || choice.offerId !== response.activeOfferId)) return null;
  if (!(response.latestReplacementBooking === undefined || response.latestReplacementBooking === null || replacementPointer(response.latestReplacementBooking))) return null;
  return { activeOfferId: response.activeOfferId, currentSelection: choice, effectiveAt: response.effectiveAt, clockMode: response.clockMode, latestReplacementBooking: response.latestReplacementBooking };
}

type HistoryDirection = "latest" | "older";
type ScrollPosition = { target: "latest" } | { target: "message"; id: string; offset: number };

function mergeMessages(first: CaseChatMessage[], second: CaseChatMessage[]) {
  const byId = new Map([...first, ...second].map((message) => [message.messageId, message]));
  return [...byId.values()].sort((a, b) => Date.parse(a.sentAt) - Date.parse(b.sentAt));
}

function captureScrollPosition(view: HTMLDivElement, direction: HistoryDirection): ScrollPosition | null {
  if (direction === "latest" && view.scrollHeight - view.clientHeight - view.scrollTop <= 24) {
    return { target: "latest" };
  }
  const top = view.getBoundingClientRect().top;
  const anchor = Array.from(view.querySelectorAll<HTMLElement>("[data-chat-message-id]"))
    .find((message) => message.getBoundingClientRect().bottom > top);
  if (!anchor?.dataset.chatMessageId) return null;
  return { target: "message", id: anchor.dataset.chatMessageId, offset: anchor.getBoundingClientRect().top - top };
}

export function CaseChatPanel({ caseId, closed, customer = false, refreshVersion = 0, insertDraft, composerTop }: {
  caseId: string; closed: boolean; customer?: boolean; refreshVersion?: number;
  /** Shown directly above the composer, e.g. the message draft. */
  composerTop?: ReactNode;
  /** A suggested message to place in the composer. A new `nonce` re-applies the same text. */
  insertDraft?: { text: string; nonce: number };
}) {
  const composer = useRef<HTMLTextAreaElement>(null);
  const [history, setHistory] = useState<HistoryResult | null>(null);
  const [historyLoading, setHistoryLoading] = useState<HistoryDirection | null>("latest");
  const [historyError, setHistoryError] = useState<HistoryDirection | null>(null);
  const [sentMessages, setSentMessages] = useState<CaseChatMessage[]>([]);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState("");
  const sendingRequest = useRef(false);
  const historyRequest = useRef<AbortController | null>(null);
  const mounted = useRef(false);
  const transcript = useRef<HTMLDivElement>(null);
  const pendingScroll = useRef<ScrollPosition | null>(null);
  const [chatState, setChatState] = useState<CaseChatState | null>(null);
  const [pendingSelection, setPendingSelection] = useState<PendingIntent<SelectionBody>>();
  const [selecting, setSelecting] = useState(false);
  const [selectionError, setSelectionError] = useState("");
  const [selectionNotice, setSelectionNotice] = useState("");
  const [selectionStorageReady, setSelectionStorageReady] = useState(false);
  const selectionRequest = useRef<AbortController | null>(null);
  const textRequest = useRef<AbortController | null>(null);
  const selectingNow = useRef(false);
  const currentCase = useRef(caseId);
  currentCase.current = caseId;
  const previousRefresh = useRef(refreshVersion);

  const loadHistory = useCallback(async (beforeMessageId?: string, replacePending = false) => {
    if (historyRequest.current && !replacePending) return;
    historyRequest.current?.abort();
    const request = new AbortController();
    historyRequest.current = request;
    const direction = beforeMessageId ? "older" : "latest";
    setHistoryLoading(direction);
    setHistoryError(null);

    try {
      const response = await getCaseMessages(caseId, request.signal, beforeMessageId);
      if (request.signal.aborted) return;
      if (response?.caseId !== caseId || !Array.isArray(response.items)) {
        setHistoryError(direction);
        return;
      }
      const { items, pagination } = response;
      const state = readChatState(response);
      setChatState(state);
      const nextBeforeMessageId = pagination?.hasMoreBefore ? pagination.nextBeforeMessageId : null;
      if (transcript.current) pendingScroll.current = captureScrollPosition(transcript.current, direction);
      setHistory((current) => {
        if (!current) return { items, beforeMessageId: nextBeforeMessageId };
        if (direction === "older") {
          return { items: mergeMessages(current.items, items), beforeMessageId: nextBeforeMessageId };
        }
        const knownIds = new Set(current.items.map((message) => message.messageId));
        const overlaps = items.some((message) => knownIds.has(message.messageId));
        return {
          items: mergeMessages(current.items, items),
          // When the latest page and cached messages are disjoint, start from its cursor to fill the gap.
          beforeMessageId: overlaps ? current.beforeMessageId : nextBeforeMessageId,
        };
      });
      return state;
    } catch {
      if (!request.signal.aborted) setHistoryError(direction);
    } finally {
      if (historyRequest.current === request) {
        historyRequest.current = null;
        setHistoryLoading(null);
      }
    }
  }, [caseId]);

  const refreshChat = useCallback(async () => !!(await loadHistory(undefined, true)), [loadHistory]);
  const replacement = useCaseReplacement({ caseId, customer, closed, selection: chatState?.currentSelection ?? null,
    activeOfferId: chatState?.activeOfferId ?? null, pointer: chatState?.latestReplacementBooking, refreshChat });

  useEffect(() => {
    mounted.current = true;
    setHistory(null); setChatState(null); setSentMessages([]); setDraft(""); setSendError(""); setSending(false);
    setPendingSelection(undefined); setSelecting(false); setSelectionError(""); setSelectionNotice(""); setSelectionStorageReady(false);
    if (customer) {
      try { setPendingSelection(loadPendingIntent<SelectionBody>("selection", caseId)); setSelectionStorageReady(true); }
      catch { setSelectionError("보관된 선택 요청을 확인할 수 없습니다. 새 요청 전에 담당자에게 확인해 주세요."); }
    }
    void loadHistory();
    return () => {
      mounted.current = false;
      historyRequest.current?.abort();
      historyRequest.current = null;
      selectionRequest.current?.abort(); textRequest.current?.abort();
    };
  }, [caseId, customer, loadHistory]);

  useEffect(() => {
    if (previousRefresh.current === refreshVersion) return;
    previousRefresh.current = refreshVersion;
    void loadHistory(undefined, true);
    const timer = window.setTimeout(() => void loadHistory(), 5700);
    return () => window.clearTimeout(timer);
  }, [refreshVersion, loadHistory]);

  // New customer messages and selections appear without a manual refresh while the tab is visible.
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") void loadHistory();
    }, 8000);
    return () => window.clearInterval(timer);
  }, [loadHistory]);

  useEffect(() => {
    if (!insertDraft?.text) return;
    setDraft(insertDraft.text);
    composer.current?.focus();
  }, [insertDraft]);

  const messages = useMemo(() => mergeMessages(sentMessages, history?.items ?? []), [history, sentMessages]);
  const dateGroups = useMemo(() => groupMessagesByDate(messages), [messages]);
  function composeBookingNotice(text: string) {
    setDraft(current => current.trim() ? `${current.trimEnd()}\n\n${text}` : text);
    composer.current?.focus();
  }

  const canSend = !customer && !closed && !sending && draft.trim().length > 0;

  useLayoutEffect(() => {
    const view = transcript.current;
    const position = pendingScroll.current;
    if (!view || !position) return;
    pendingScroll.current = null;
    if (position.target === "latest") {
      view.scrollTop = view.scrollHeight;
    } else {
      const anchor = view.querySelector<HTMLElement>(`[data-chat-message-id="${CSS.escape(position.id)}"]`);
      if (anchor) view.scrollTop += anchor.getBoundingClientRect().top - view.getBoundingClientRect().top - position.offset;
    }
  }, [messages]);

  async function submitMessage(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();
    if (!canSend || sendingRequest.current) return;

    sendingRequest.current = true;
    const controller = new AbortController(); textRequest.current = controller;
    setSending(true);
    setSendError("");
    try {
      const sent = await sendCaseMessage(caseId, draft, controller.signal);
      if (!mounted.current || controller.signal.aborted || currentCase.current !== caseId) return;
      pendingScroll.current = { target: "latest" };
      setSentMessages((messages) => [...messages, sent]);
      setDraft("");
    } catch (error) {
      if (!mounted.current || controller.signal.aborted || currentCase.current !== caseId) return;
      setSendError(error instanceof Error ? error.message : "메시지를 전송하지 못했습니다.");
      if (error instanceof CaseMessageSendError && error.outcomeUnknown) {
        await loadHistory(undefined, true);
      }
    } finally {
      sendingRequest.current = false;
      if (mounted.current && !controller.signal.aborted && currentCase.current === caseId) setSending(false);
    }
  }

  async function choose(offerId?: string, quoteId?: string) {
    if (!customer || selectingNow.current || !selectionStorageReady || (!pendingSelection && (!offerId || !quoteId || !chatState || replacement.selectionBlocked))) return;
    const intent: PendingIntent<SelectionBody> = pendingSelection ?? { version: 1, key: crypto.randomUUID(), body: { offerId: offerId!, quoteId: quoteId! } };
    try { persistIntent("selection", caseId, intent); }
    catch { setSelectionError("요청을 안전하게 보관하지 못해 선택하지 않았습니다. 브라우저 저장소를 확인해 주세요."); return; }
    setPendingSelection(intent); setSelecting(true); setSelectionError(""); setSelectionNotice(""); selectingNow.current = true;
    const controller = new AbortController(); selectionRequest.current = controller;
    try {
      await selectCaseOffer(caseId, intent, controller.signal);
      if (controller.signal.aborted || currentCase.current !== caseId) return;
      clearIntent("selection", caseId); setPendingSelection(undefined); setChatState(null);
      setSelectionNotice("선택 처리 응답을 확인했습니다. 아래 현재 선택은 서버에서 다시 조회합니다. 예약 확정은 별도입니다.");
      await loadHistory(undefined, true);
    } catch (error) {
      if (controller.signal.aborted || currentCase.current !== caseId) return;
      if (error instanceof OfferMutationError && !error.outcomeUnknown) {
        clearIntent("selection", caseId); setPendingSelection(undefined);
        await loadHistory(undefined, true);
      }
      setSelectionError(error instanceof Error ? error.message : "선택 요청 실패");
    } finally { selectingNow.current = false; if (!controller.signal.aborted && currentCase.current === caseId) setSelecting(false); }
  }

  // The parent fixes the height; only the transcript scrolls and the composer stays at the bottom.
  return (
    <section
      aria-label="채팅"
      className="flex h-full min-h-[24rem] min-w-0 flex-col overflow-hidden rounded-lg border bg-card text-card-foreground"
    >
      <div className="scroll-area max-h-[42%] shrink-0">
      {chatState && <div className="space-y-1 border-b px-4 py-3 text-sm">
        <p className="text-xs text-muted-foreground">{chatState.clockMode === "SYNTHETIC_DEMO" ? "시연용 합성 데이터 · 가상 판단 시각" : "서버 판단 시각"}: <time dateTime={chatState.effectiveAt}>{formatTimestamp(chatState.effectiveAt, offerExpiryFormat)}</time></p>
        <p role="status">{chatState.currentSelection ? `현재 선택: ${messages.flatMap(message => message.offer?.items ?? []).find(item => item.quoteId === chatState.currentSelection?.quoteId)?.propertyName ?? chatState.currentSelection.quoteId} · ${replacement.booking?.selectionId === chatState.currentSelection.selectionId ? replacementStatusText[replacement.booking.status] : replacement.target && !replacement.booking ? "예약 처리 상태 확인 필요" : "선택 완료 · 예약 진행 전"}` : "현재 선택 없음 · 고객 선택은 예약 확정이 아닙니다."}</p>
      </div>}
      <CaseReplacementPanel state={replacement} customer={customer} />
      {customer && !chatState && history && <p className="border-b px-5 py-3 text-sm">현재 제안·선택과 서버 판단 시각을 확인해야 선택할 수 있습니다. 새 메시지 확인을 눌러 주세요.</p>}
      {customer && <div className="space-y-2 border-b px-5 py-3 text-sm">
        <p>고객 본인에게 전송된 현재 제안에서 숙소를 선택할 수 있습니다. 일반 메시지 전송은 이 시연에 포함되지 않습니다.</p>
        {selectionError && <p role="alert" className="text-accent-foreground">{selectionError}</p>}
        {selectionNotice && <p role="status">{selectionNotice}</p>}
        {pendingSelection && <><p>이전 선택의 결과가 확인되지 않았습니다. 다른 선택 전에 보관된 같은 요청으로 확인해 주세요.</p><Button type="button" disabled={selecting || !selectionStorageReady} onClick={() => void choose()}>{selecting ? "선택 처리 확인 중…" : "같은 요청으로 선택 결과 확인"}</Button></>}
      </div>}
      </div>
      <div className="flex shrink-0 flex-wrap justify-end gap-1 border-b px-3 py-1">
        {history?.beforeMessageId && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={historyLoading !== null}
            onClick={() => void loadHistory(history.beforeMessageId ?? undefined)}
          >
            {historyLoading === "older" ? "이전 대화 불러오는 중…" : "이전 대화 불러오기"}
          </Button>
        )}
        <Button type="button" variant="ghost" size="sm" disabled={historyLoading !== null} onClick={() => void loadHistory()}>
          {historyLoading === "latest" && history ? "새 메시지 확인 중…" : "새 메시지 확인"}
        </Button>
      </div>
      {historyError && history && (
        <p role="alert" className="shrink-0 border-b px-5 py-2 text-xs text-accent-foreground">
          {historyError === "older" ? "이전 대화를 불러오지 못했습니다." : "새 메시지를 확인하지 못했습니다."}
        </p>
      )}
      <div
        ref={transcript}
        role="region"
        aria-label="대화 내역"
        aria-busy={historyLoading !== null}
        className="scroll-area flex flex-1 flex-col bg-surface px-4 py-4 [overflow-anchor:none]"
      >
        {messages.length > 0 && (
          <div className="space-y-5">
            {Array.from(dateGroups, ([date, dayMessages]) => (
              <div key={date ?? ""} className="space-y-3">
                {date && (
                  <p className="flex items-center gap-3 text-center text-xs font-medium text-muted-foreground">
                    <span aria-hidden="true" className="h-px flex-1 bg-border" />
                    {date}
                    <span aria-hidden="true" className="h-px flex-1 bg-border" />
                  </p>
                )}
                <ol className="space-y-3">
                  {dayMessages.map((message) => (
                    <MessageItem key={message.messageId} caseId={caseId} closed={closed} message={message} state={chatState} customer={customer} selecting={selecting}
                      onCompose={composeBookingNotice}
                      selectionBlocked={closed || !!pendingSelection || !selectionStorageReady || replacement.selectionBlocked} onSelect={(offerId, quoteId) => void choose(offerId, quoteId)} />
                  ))}
                </ol>
              </div>
            ))}
          </div>
        )}
        {((historyError && !history) || (!historyError && messages.length === 0)) && (
          <p
            role={historyError ? "alert" : "status"}
            className={`m-auto text-center text-sm ${historyError ? "text-accent-foreground" : "text-muted-foreground"}`}
          >
            {historyError
              ? "대화 내역을 불러오지 못했습니다."
              : history
                ? "표시할 대화가 없습니다."
                : "대화 내역을 불러오는 중…"}
          </p>
        )}
      </div>

      {!customer && <form onSubmit={submitMessage} className="shrink-0 space-y-2 border-t px-3 py-3">
        {sendError && <p role="alert" className="text-sm text-accent-foreground">{sendError}</p>}
        {!closed && composerTop}
        {closed ? <p className="px-1 text-sm text-muted-foreground">종료된 상담에는 메시지를 보낼 수 없습니다.</p> : (
          <div className="flex items-end gap-2">
            <Textarea
              ref={composer}
              rows={draft.length > 90 || draft.includes("\n") ? 4 : 2}
              aria-label="메시지"
              placeholder="고객에게 보낼 메시지 · Enter로 전송, Shift+Enter로 줄바꿈"
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
                  event.preventDefault();
                  void submitMessage();
                }
              }}
              readOnly={sending}
              className="max-h-40"
            />
            <Button type="submit" size="icon-lg" disabled={!canSend} aria-label={sending ? "전송 중" : "메시지 전송"} title="전송">
              <SendHorizontal aria-hidden="true" />
            </Button>
          </div>
        )}
      </form>}
    </section>
  );
}
