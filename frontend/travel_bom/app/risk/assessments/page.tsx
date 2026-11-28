"use client";

import { Badge } from "@/components/ui/badge";
import { ConsolePage, EmptyState, useConsoleData } from "@/components/console/use-console-data";
import { formatDateTime, formatDay, formatFeature, formatScore, formatSignal, policyLabels, SIGNAL_LINE, signalPercent, policyNotes, riskFeatureLabels } from "@/lib/case-labels";
import { getRiskAssessments } from "@/lib/console-api";

const featureOrder = ["targetConfirmedBookingCount", "pastFailureRate90d", "reconfirmationOverdueRate7d", "inventoryUnavailableRate7d", "bookingVolumeGrowth7d", "isPeakDay"];

/** Risk signals per hotel and check-in date, with the inputs behind each score. */
export default function RiskAssessments() {
  const state = useConsoleData(getRiskAssessments);
  const items = state.data ?? [];

  return (
    <ConsolePage title="위험 평가" state={state}
      description="호텔×체크인 날짜별 위험 신호입니다. 재확인할 예약을 고르는 데만 쓰며, 객실 미제공 확정이나 고객별 실패 확률이 아닙니다.">
      {state.data && (items.length === 0 ? <EmptyState>저장된 위험 평가가 없습니다. 위험 점검 배치를 실행하면 평가가 기록됩니다.</EmptyState> : (
        <ul className="space-y-3">
          {items.map(item => {
            const selection = item.selections[0];
            return (
              <li key={item.assessmentId} className="panel space-y-2.5 p-4">
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className="text-lg font-semibold">{item.hotelName}</h2>
                  <span className="num text-sm text-muted-foreground">{formatDay(item.targetCheckInDate)} 체크인</span>
                  <Badge tone={item.selected ? "info" : "outline"} className="text-sm">{item.selected ? "재확인 대상으로 선별" : "선별되지 않음"}</Badge>
                  {selection && (
                    <Badge tone="outline" className="num text-sm">
                      위험 신호 {formatSignal(selection.rawScore, selection.threshold)} · 선별 기준 {SIGNAL_LINE}% · {policyLabels.get(selection.policy) ?? selection.policy}
                    </Badge>
                  )}
                  <span className="ml-auto text-sm font-medium">
                    {item.selected
                      ? `재확인 ${item.recheckJobs}건 · 완료 ${item.completedRechecks}건 · 대응 건 ${item.casesLinked}건`
                      : "선별 기준 미달 · 재확인 안 함"}
                  </span>
                </div>
                <div className="relative h-1.5 rounded-full bg-border" role="img" aria-label={selection ? `위험 신호 ${formatSignal(selection.rawScore, selection.threshold)}, 선별 기준 ${SIGNAL_LINE}%` : `위험 신호 원점수 ${formatScore(item.score)}`}>
                  <span className="absolute inset-y-0 left-0 rounded-full bg-info" style={{ width: `${selection ? signalPercent(selection.rawScore, selection.threshold) : Math.min(100, item.score * 100)}%` }} />
                  {selection && <span className="absolute -inset-y-1 w-0.5 bg-foreground" style={{ left: `${SIGNAL_LINE}%` }} />}
                </div>
                <details className="text-sm">
                  <summary className="cursor-pointer text-muted-foreground select-none hover:text-foreground">기준 보기</summary>
                  <div className="mt-2 space-y-2">
                    <p className="num text-muted-foreground">
                      {selection && `선별 기준이 ${SIGNAL_LINE}%가 되도록 환산한 값입니다. `}원점수 {formatScore(selection?.rawScore ?? item.score)}{selection && ` · 원래 선별 기준 ${formatScore(selection.threshold)}`}
                    </p>
                    {selection && policyNotes.has(selection.policy) && <p className="leading-relaxed">{policyNotes.get(selection.policy)}</p>}
                    <dl className="grid gap-x-6 sm:grid-cols-2 xl:grid-cols-3">
                      {featureOrder.filter(name => name in item.features).map(name => (
                        <div key={name} className="flex items-baseline justify-between gap-2 border-b border-dashed py-1.5">
                          <dt className="text-muted-foreground">{riskFeatureLabels[name] ?? name}</dt>
                          <dd className="num font-medium">{formatFeature(name, item.features[name])}</dd>
                        </div>
                      ))}
                    </dl>
                    <p className="num text-xs text-muted-foreground">
                      {formatDateTime(item.assessedAt)} 평가 · 모델 {item.modelVersion} · {item.featureSetVersion} · 합성 데이터로 학습
                    </p>
                  </div>
                </details>
              </li>
            );
          })}
        </ul>
      ))}
    </ConsolePage>
  );
}
