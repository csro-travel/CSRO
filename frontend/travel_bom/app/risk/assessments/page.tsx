"use client";

import { Badge } from "@/components/ui/badge";
import { ConsolePage, EmptyState, useConsoleData } from "@/components/console/use-console-data";
import { formatDateTime, formatDay, formatFeature, formatScore, policyLabels, riskFeatureLabels } from "@/lib/case-labels";
import { getRiskAssessments } from "@/lib/console-api";

const featureOrder = ["targetConfirmedBookingCount", "pastFailureRate90d", "reconfirmationOverdueRate7d", "inventoryUnavailableRate7d", "bookingVolumeGrowth7d", "isPeakDay"];

/** Risk signals per hotel and check-in date, with the inputs behind each score. */
export default function RiskAssessments() {
  const state = useConsoleData(getRiskAssessments);
  const items = state.data ?? [];

  return (
    <ConsolePage title="위험 평가" state={state}
      description="체크인 하루 전 기준으로 호텔×체크인 날짜마다 계산한 위험 신호입니다. 점수는 재확인할 예약을 고르는 데만 쓰며, 객실 미제공 확정이나 고객별 실패 확률이 아닙니다.">
      {state.data && (items.length === 0 ? <EmptyState>저장된 위험 평가가 없습니다. 위험 점검 배치를 실행하면 평가가 기록됩니다.</EmptyState> : (
        <ul className="space-y-3">
          {items.map(item => {
            const selection = item.selections[0];
            const scale = 1; // full 0–1 range, so a small score looks small
            return (
              <li key={item.assessmentId} className="panel grid gap-x-6 gap-y-3 p-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
                <div className="space-y-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <h2 className="text-base font-semibold">{item.hotelName}</h2>
                    <span className="num text-sm text-muted-foreground">{formatDay(item.targetCheckInDate)} 체크인</span>
                    <Badge tone={item.selected ? "info" : "outline"}>{item.selected ? "재확인 대상으로 선별" : "선별되지 않음"}</Badge>
                  </div>
                  <p className="text-sm">
                    위험 신호 <strong className="num text-2xl">{formatScore(selection?.rawScore ?? item.score)}</strong>
                    {selection && <span className="ml-2 text-xs text-muted-foreground">선별 기준 {formatScore(selection.threshold)} 이상 · {policyLabels.get(selection.policy) ?? selection.policy}</span>}
                  </p>
                  <div className="relative h-1.5 rounded-full bg-border" role="img" aria-label={`위험 신호 ${formatScore(item.score)}`}>
                    <span className="absolute inset-y-0 left-0 rounded-full bg-info" style={{ width: `${Math.min(100, (item.score / scale) * 100)}%` }} />
                    {selection && <span className="absolute -inset-y-1 w-0.5 bg-foreground" style={{ left: `${Math.min(100, (selection.threshold / scale) * 100)}%` }} />}
                  </div>
                  <p className="text-sm text-muted-foreground">
                    {item.selected
                      ? `이 호텔·날짜의 예약 ${item.recheckJobs}건을 재확인했고 ${item.completedRechecks}건이 끝났습니다. 대응 건으로 이어진 것은 ${item.casesLinked}건입니다.`
                      : "선별 기준에 못 미쳐 재확인하지 않았습니다."}
                  </p>
                  <p className="num text-[0.7rem] text-muted-foreground">
                    {formatDateTime(item.assessedAt)} 평가 · 모델 {item.modelVersion} · {item.featureSetVersion} · 합성 데이터로 학습
                  </p>
                </div>
                <dl className="grid content-start gap-x-4 text-sm sm:grid-cols-2">
                  {featureOrder.filter(name => name in item.features).map(name => (
                    <div key={name} className="flex items-baseline justify-between gap-2 border-b border-dashed py-1.5">
                      <dt className="text-muted-foreground">{riskFeatureLabels[name] ?? name}</dt>
                      <dd className="num font-medium">{formatFeature(name, item.features[name])}</dd>
                    </div>
                  ))}
                </dl>
              </li>
            );
          })}
        </ul>
      ))}
    </ConsolePage>
  );
}
