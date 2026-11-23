"use client";

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { formatWon } from "@/lib/alternative-stays";
import { CaseMessageSendError, getCaseMessages, sendCaseMessage } from "../_api/cases";
import type { CaseChatMessage } from "../_types/message";

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

function MessageItem({ message }: { message: CaseChatMessage }) {
  const { body, offer, senderType, sentAt } = message;

  return (
    <li
      data-chat-message-id={message.messageId}
      className={`w-fit min-w-0 space-y-2 rounded-2xl border px-3.5 py-2.5 ${offer ? "max-w-full" : "max-w-[85%]"} ${messageBubbleStyles[senderType]}`}
    >
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <span className={`text-xs font-semibold ${senderType === "COUNSELOR" ? "text-accent-foreground" : ""}`}>
          {senderLabels[senderType]}
        </span>
        <time dateTime={sentAt} className="text-[11px] whitespace-nowrap text-muted-foreground tabular-nums">
          {formatTimestamp(sentAt, messageTimeFormat) ?? sentAt}
        </time>
      </div>
      {body && (
        <p className="whitespace-pre-wrap wrap-anywhere text-sm">{body}</p>
      )}
      {offer && (
        <div className="space-y-2">
          <p className="text-sm font-semibold">
            {offer.status === "SUPERSEDED" ? "이전 대체 숙소 제안" : "대체 숙소 제안"}
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
                <p className="text-xs text-muted-foreground">
                  견적 기한{" "}
                  <time dateTime={item.expiresAt}>
                    {formatTimestamp(item.expiresAt, offerExpiryFormat) ?? item.expiresAt}
                  </time>
                </p>
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

export function CaseChatPanel({ caseId, closed }: { caseId: string; closed: boolean }) {
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
      const nextBeforeMessageId = pagination?.hasMoreBefore ? pagination.nextBeforeMessageId : null;
      if (transcript.current) pendingScroll.current = captureScrollPosition(transcript.current, direction);
      setHistory((current) => {
        if (!current) return { items, beforeMessageId: nextBeforeMessageId };
        if (direction === "older") {
          return { items: mergeMessages(items, current.items), beforeMessageId: nextBeforeMessageId };
        }
        const knownIds = new Set(current.items.map((message) => message.messageId));
        const overlaps = items.some((message) => knownIds.has(message.messageId));
        return {
          items: mergeMessages(current.items, items),
          // When the latest page and cached messages are disjoint, start from its cursor to fill the gap.
          beforeMessageId: overlaps ? current.beforeMessageId : nextBeforeMessageId,
        };
      });
    } catch {
      if (!request.signal.aborted) setHistoryError(direction);
    } finally {
      if (historyRequest.current === request) {
        historyRequest.current = null;
        setHistoryLoading(null);
      }
    }
  }, [caseId]);

  useEffect(() => {
    mounted.current = true;
    void loadHistory();
    return () => {
      mounted.current = false;
      historyRequest.current?.abort();
      historyRequest.current = null;
    };
  }, [loadHistory]);

  const messages = useMemo(() => mergeMessages(history?.items ?? [], sentMessages), [history, sentMessages]);
  const dateGroups = useMemo(() => groupMessagesByDate(messages), [messages]);
  const canSend = !closed && !sending && draft.trim().length > 0;

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

  async function submitMessage(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSend || sendingRequest.current) return;

    sendingRequest.current = true;
    setSending(true);
    setSendError("");
    try {
      const sent = await sendCaseMessage(caseId, draft);
      if (!mounted.current) return;
      pendingScroll.current = { target: "latest" };
      setSentMessages((messages) => [...messages, sent]);
      setDraft("");
    } catch (error) {
      if (!mounted.current) return;
      setSendError(error instanceof Error ? error.message : "메시지를 전송하지 못했습니다.");
      if (error instanceof CaseMessageSendError && error.outcomeUnknown) {
        await loadHistory(undefined, true);
      }
    } finally {
      sendingRequest.current = false;
      if (mounted.current) setSending(false);
    }
  }

  // Leave space above and below the desktop panel for the case heading and page padding.
  return (
    <section
      aria-label="채팅"
      className="flex min-h-[26rem] min-w-0 flex-col overflow-hidden rounded-xl border bg-card text-card-foreground lg:sticky lg:top-6 lg:max-h-[calc(100dvh_-_12rem)] lg:min-h-[21rem]"
    >
      <div className="flex shrink-0 flex-wrap justify-end gap-1 border-b px-3 py-1.5">
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
        <p role="alert" className="shrink-0 border-b px-5 py-2 text-xs text-destructive">
          {historyError === "older" ? "이전 대화를 불러오지 못했습니다." : "새 메시지를 확인하지 못했습니다."}
        </p>
      )}
      <div
        ref={transcript}
        role="region"
        aria-label="대화 내역"
        aria-busy={historyLoading !== null}
        className="flex max-h-[28rem] min-h-40 flex-1 flex-col overflow-y-auto bg-muted/50 px-5 py-4 [overflow-anchor:none] lg:max-h-none lg:min-h-0 lg:overscroll-y-contain"
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
                    <MessageItem key={message.messageId} message={message} />
                  ))}
                </ol>
              </div>
            ))}
          </div>
        )}
        {((historyError && !history) || (!historyError && messages.length === 0)) && (
          <p
            role={historyError ? "alert" : "status"}
            className={`m-auto text-center text-sm ${historyError ? "text-destructive" : "text-muted-foreground"}`}
          >
            {historyError
              ? "대화 내역을 불러오지 못했습니다."
              : history
                ? "표시할 대화가 없습니다."
                : "대화 내역을 불러오는 중…"}
          </p>
        )}
      </div>

      <form onSubmit={submitMessage} className="shrink-0 space-y-3 border-t px-5 py-4">
        <label className="block space-y-2 text-sm font-medium">
          <span className="block">메시지</span>
          <textarea
            rows={3}
            placeholder="메시지를 입력하세요"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            readOnly={sending}
            disabled={closed}
            className="block w-full resize-y rounded-lg border border-input bg-background px-3 py-2 text-sm font-normal placeholder:text-muted-foreground focus-visible:outline-2 focus-visible:outline-ring lg:max-h-32"
          />
        </label>
        {closed && <p className="text-sm text-muted-foreground">종료된 사건에는 메시지를 보낼 수 없습니다.</p>}
        {sendError && <p role="alert" className="text-sm text-destructive">{sendError}</p>}
        <div className="flex justify-end">
          <Button type="submit" size="lg" disabled={!canSend}>
            {sending ? "전송 중…" : "전송"}
          </Button>
        </div>
      </form>
    </section>
  );
}
