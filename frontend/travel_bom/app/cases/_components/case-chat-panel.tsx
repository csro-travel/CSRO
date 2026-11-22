"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { formatWon } from "@/lib/alternative-stays";
import { getCaseMessages } from "../_api/cases";
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

export function CaseChatPanel({ caseId }: { caseId: string }) {
  const [result, setResult] = useState<HistoryResult | null>(null);

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
  }, [caseId]);

  const history = result?.caseId === caseId ? result : null;
  const failed = history !== null && "failed" in history;
  const messages = history && "items" in history ? history.items : [];

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
        role="region"
        aria-label="대화 내역"
        aria-busy={history === null}
        className="flex max-h-[28rem] min-h-40 flex-1 flex-col overflow-y-auto bg-muted/50 px-5 py-4"
      >
        {messages.length > 0 ? (
          <ol className="space-y-3">
            {messages.map((message) => (
              <MessageItem key={message.messageId} message={message} />
            ))}
          </ol>
        ) : (
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

      <div className="space-y-3 border-t px-5 py-4">
        <label className="block space-y-2 text-sm font-medium">
          <span className="block">메시지</span>
          <textarea
            rows={3}
            placeholder="메시지를 입력하세요"
            className="block w-full resize-y rounded-lg border border-input bg-background px-3 py-2 text-sm font-normal placeholder:text-muted-foreground focus-visible:outline-2 focus-visible:outline-ring"
          />
        </label>
        <div className="flex justify-end">
          <Button type="button" size="lg" disabled>
            전송
          </Button>
        </div>
      </div>
    </section>
  );
}
