"use client";

import { Badge, type BadgeTone } from "@/components/ui/badge";
import { ConsolePage, EmptyState, useConsoleData } from "@/components/console/use-console-data";
import { evidenceSourceLabels, formatDateTime, formatDay, provisionLabels, reviewLabels, triggerLabels } from "@/lib/case-labels";
import { getEvidence } from "@/lib/console-api";

const reviewTones: Record<string, BadgeTone> = { ACCEPTED: "success", PENDING_REVIEW: "outline", REJECTED: "neutral" };

function pretty(content: string | null) {
  if (!content) return null;
  try { return JSON.stringify(JSON.parse(content), null, 2); } catch { return content; }
}

/** Every stored supplier and hotel answer, newest first. */
export default function EvidenceRecords() {
  const state = useConsoleData(getEvidence);
  const items = state.data ?? [];

  return (
    <ConsolePage title="재확인 결과" state={state}
      description="공급사·호텔이 답한 내용과 상담사가 직접 확인한 기록입니다. 호텔이 말한 내용은 주장이고, '사실로 채택'된 항목만 숙박일 상태에 반영됩니다.">
      {state.data && (items.length === 0 ? <EmptyState>저장된 재확인 결과가 없습니다.</EmptyState> : (
        <ul className="space-y-3">
          {items.map(item => (
            <li key={item.evidenceId} className="panel space-y-2 p-4">
              <div className="flex flex-wrap items-center gap-2">
                <span className="num text-sm font-bold">{item.bookingId}</span>
                <span className="text-sm text-muted-foreground">{item.hotelName}</span>
                <Badge tone="neutral">{evidenceSourceLabels.get(item.sourceType) ?? item.sourceType}</Badge>
                {item.trigger && <Badge tone="outline">{triggerLabels.get(item.trigger) ?? item.trigger}</Badge>}
                {item.sourceAuthenticated && <Badge tone="success">출처 인증됨</Badge>}
                <time dateTime={item.receivedAt} className="num ml-auto text-xs text-muted-foreground">{formatDateTime(item.receivedAt)} 수신 · {item.evidenceId}</time>
              </div>
              {item.assertions.length > 0 ? (
                <ul className="flex flex-wrap gap-x-6 gap-y-1 text-sm">
                  {item.assertions.map(assertion => (
                    <li key={assertion.stayDate} className="flex items-center gap-2">
                      <time dateTime={assertion.stayDate} className="num text-xs text-muted-foreground">{formatDay(assertion.stayDate)}</time>
                      <span className="font-medium">{provisionLabels.get(assertion.reportedProvisionStatus) ?? assertion.reportedProvisionStatus}</span>
                      <Badge tone={reviewTones[assertion.reviewStatus] ?? "outline"}>{reviewLabels.get(assertion.reviewStatus) ?? assertion.reviewStatus}</Badge>
                    </li>
                  ))}
                </ul>
              ) : <p className="text-sm text-muted-foreground">숙박일별 주장이 없는 기록입니다(예: 공급사 계약 상태 조회).</p>}
              {item.originalContent && (
                <details className="text-xs">
                  <summary className="cursor-pointer text-muted-foreground select-none hover:text-foreground">원본 데이터(감사용)</summary>
                  <pre className="num mt-2 max-h-56 overflow-auto rounded-md bg-muted p-3 leading-relaxed whitespace-pre-wrap wrap-anywhere">{pretty(item.originalContent)}</pre>
                </details>
              )}
            </li>
          ))}
        </ul>
      ))}
      {state.data && <p className="text-xs text-muted-foreground">가상 공급사·호텔 응답(시연용)이며 출처 인증 절차가 없습니다.</p>}
    </ConsolePage>
  );
}
