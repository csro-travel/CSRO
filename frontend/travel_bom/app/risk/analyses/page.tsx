"use client";

import { Badge } from "@/components/ui/badge";
import { ConsolePage, EmptyState, useConsoleData } from "@/components/console/use-console-data";
import { formatDateTime, formatDay, provisionLabels } from "@/lib/case-labels";
import { getHotelReplyAnalyses } from "@/lib/console-api";

const statuses = new Map([["EXTRACTED", "날짜별 주장 추출"], ["AMBIGUOUS", "모호함 · 사람 확인 필요"]]);
const text = (value: unknown) => (typeof value === "string" ? value : "");

/** Sentence replies from hotels, the claims extracted from them, and what stayed unresolved. */
export default function HotelReplyAnalyses() {
  const state = useConsoleData(getHotelReplyAnalyses);
  const items = state.data ?? [];

  return (
    <ConsolePage title="호텔 회신 분석" state={state}
      description="호텔이 문장으로 보낸 회신에서 숙박일별 주장을 뽑은 기록입니다. 추출 결과는 사람이 확인해야 하는 주장이며, 이것만으로 숙박일 상태가 바뀌지 않습니다.">
      {state.data && (items.length === 0 ? (
        <EmptyState>
          저장된 분석이 없습니다. 지금 재확인 흐름의 호텔 응답은 정형 데이터라 문장 분석을 거치지 않습니다.<br />
          문장으로 된 호텔 회신이 저장되고 분석을 실행하면 여기에 기록됩니다.
        </EmptyState>
      ) : (
        <ul className="space-y-3">
          {items.map(item => (
            <li key={item.analysisId} className="panel space-y-3 p-4">
              <div className="flex flex-wrap items-center gap-2">
                <span className="num text-sm font-bold">{item.bookingId}</span>
                <span className="text-sm text-muted-foreground">{item.hotelName}</span>
                <Badge tone={item.status === "EXTRACTED" ? "info" : "warning"}>{statuses.get(item.status) ?? item.status}</Badge>
                <time dateTime={item.analyzedAt} className="num ml-auto text-xs text-muted-foreground">{formatDateTime(item.analyzedAt)} 분석 · 근거 {item.evidenceId}</time>
              </div>
              {item.originalMessage && (
                <blockquote className="border-l-2 pl-3 text-sm leading-relaxed whitespace-pre-wrap text-muted-foreground">{item.originalMessage}</blockquote>
              )}
              {item.claims.length > 0 && (
                <ul className="space-y-1.5 text-sm">
                  {item.claims.map((claim, index) => (
                    <li key={index} className="grid grid-cols-[5.5rem_5rem_minmax(0,1fr)] items-baseline gap-x-2">
                      <span className="num text-xs text-muted-foreground">{text(claim.stayDate) ? formatDay(text(claim.stayDate)) : "날짜 없음"}</span>
                      <span className="font-medium">{provisionLabels.get(text(claim.reportedProvisionStatus)) ?? text(claim.reportedProvisionStatus)}</span>
                      {text(claim.supportingText) && <q className="text-muted-foreground before:content-['“'] after:content-['”']">{text(claim.supportingText)}</q>}
                    </li>
                  ))}
                </ul>
              )}
              {item.unresolvedStayDates.length > 0 && <p className="text-sm">추출하지 못한 숙박일: <span className="num">{item.unresolvedStayDates.map(formatDay).join(", ")}</span></p>}
              {item.warnings.map(warning => <p key={warning} className="text-xs text-muted-foreground">{warning}</p>)}
              <p className="num text-[0.7rem] text-muted-foreground">{item.modelVersion} · 정규식 기반 추출기</p>
            </li>
          ))}
        </ul>
      ))}
    </ConsolePage>
  );
}
