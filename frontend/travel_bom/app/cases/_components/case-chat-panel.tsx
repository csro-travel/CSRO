"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { formatWon } from "@/lib/alternative-stays";
import { CaseMessageSendError, getCaseMessages, sendCaseMessage } from "../_api/cases";
import type { CaseChatMessage } from "../_types/message";

const senderLabels = {
  CUSTOMER: "고객",
  COUNSELOR: "상담사",
  SYSTEM: "시스템",
} satisfies Record<CaseChatMessage["senderType"], string>;

const messageTimeFormat = new Intl.DateTimeFormat("ko-KR", {
  dateStyle: "medium",
  timeStyle: "short",
  timeZone: "Asia/Seoul",
});

function formatMessageTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : messageTimeFormat.format(date);
}

function MessageItem({ message }: { message: CaseChatMessage }) {
  const { body, offer, senderType, sentAt } = message;

  return (
    <li className="space-y-2 rounded-lg border bg-card p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-sm font-semibold">{senderLabels[senderType]}</span>
        <time dateTime={sentAt} className="text-xs text-muted-foreground">
          {formatMessageTime(sentAt)}
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
                    {formatMessageTime(item.expiresAt)}
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
  caseId: string;
} & ({ items: CaseChatMessage[] } | { failed: true });

export function CaseChatPanel({ caseId, closed }: { caseId: string; closed: boolean }) {
  const [result, setResult] = useState<HistoryResult | null>(null);
  const [historyAttempt, setHistoryAttempt] = useState(0);
  const [sentMessages, setSentMessages] = useState<CaseChatMessage[]>([]);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState("");
  const sendingRequest = useRef(false);
  const transcript = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const request = new AbortController();
    void getCaseMessages(caseId, request.signal).then(
      (response) => {
        if (request.signal.aborted) return;
        if (response?.caseId !== caseId || !Array.isArray(response.items)) {
          setResult({ caseId, failed: true });
          return;
        }
        setResult({ caseId, items: response.items });
      },
      () => {
        if (!request.signal.aborted) setResult({ caseId, failed: true });
      },
    );
    return () => request.abort();
  }, [caseId, historyAttempt]);

  const history = result?.caseId === caseId ? result : null;
  const failed = history !== null && "failed" in history;
  const historyItems = history && "items" in history ? history.items : [];
  const messages = [...new Map(
    [...historyItems, ...sentMessages].map((message) => [message.messageId, message]),
  ).values()].sort((first, second) => Date.parse(first.sentAt) - Date.parse(second.sentAt));
  const lastSentId = sentMessages.at(-1)?.messageId;
  const canSend = !closed && !sending && draft.trim().length > 0;

  useEffect(() => {
    if (lastSentId && transcript.current) {
      transcript.current.scrollTop = transcript.current.scrollHeight;
    }
  }, [lastSentId]);

  async function submitMessage(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSend || sendingRequest.current) return;

    sendingRequest.current = true;
    setSending(true);
    setSendError("");
    try {
      const sent = await sendCaseMessage(caseId, draft);
      setSentMessages((messages) => [...messages, sent]);
      setDraft("");
    } catch (error) {
      setSendError(error instanceof Error ? error.message : "메시지를 전송하지 못했습니다.");
      if (error instanceof CaseMessageSendError && error.outcomeUnknown) {
        setHistoryAttempt((attempt) => attempt + 1);
      }
    } finally {
      sendingRequest.current = false;
      setSending(false);
    }
  }

  return (
    <section
      aria-labelledby="case-chat-heading"
      className="flex min-h-[26rem] min-w-0 flex-col overflow-hidden rounded-xl border bg-card text-card-foreground"
    >
      <header className="border-b px-5 py-4">
        <h2 id="case-chat-heading" className="text-lg font-bold">
          고객과의 대화
        </h2>
      </header>

      <div
        ref={transcript}
        role="region"
        aria-label="대화 내역"
        aria-busy={history === null}
        className="flex max-h-[28rem] min-h-40 flex-1 flex-col overflow-y-auto bg-muted/50 px-5 py-4"
      >
        {messages.length > 0 && (
          <ol className="space-y-3">
            {messages.map((message) => (
              <MessageItem key={message.messageId} message={message} />
            ))}
          </ol>
        )}
        {(failed || messages.length === 0) && (
          <p
            role={failed ? "alert" : "status"}
            className={`m-auto text-center text-sm ${failed ? "text-destructive" : "text-muted-foreground"}`}
          >
            {failed
              ? "대화 내역을 불러오지 못했습니다."
              : history
                ? "표시할 대화가 없습니다."
                : "대화 내역을 불러오는 중…"}
          </p>
        )}
      </div>

      <form onSubmit={submitMessage} className="space-y-3 border-t px-5 py-4">
        <label className="block space-y-2 text-sm font-medium">
          <span className="block">메시지</span>
          <textarea
            rows={3}
            placeholder="메시지를 입력하세요"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            readOnly={sending}
            disabled={closed}
            className="block w-full resize-y rounded-lg border border-input bg-background px-3 py-2 text-sm font-normal placeholder:text-muted-foreground focus-visible:outline-2 focus-visible:outline-ring"
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
