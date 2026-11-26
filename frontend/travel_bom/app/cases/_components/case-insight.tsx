import { Activity, Radar, UserRound } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import {
  formatDateTime, formatDay, formatFeature, formatScore, jobStatusLabels, originLabels, policyLabels, riskFeatureLabels, triggerLabels,
} from "@/lib/case-labels";
import type { AnalysisRun, CaseDetail, RiskSignal } from "../_types/case";

const featureOrder = ["targetConfirmedBookingCount", "pastFailureRate90d", "reconfirmationOverdueRate7d", "inventoryUnavailableRate7d", "bookingVolumeGrowth7d", "isPeakDay"];

function RiskBlock({ risk }: { risk: RiskSignal }) {
  // Drawn on the full 0–1 range so a small score looks small.
  const scale = 1;
  return (
    <div className="space-y-2 rounded-md bg-muted/60 p-3">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <p className="text-sm">
          위험 신호 <strong className="num text-base">{formatScore(risk.score)}</strong>
          <span className="ml-2 text-xs text-muted-foreground">선별 기준 {formatScore(risk.threshold)} 이상 · {policyLabels.get(risk.policy) ?? risk.policy}</span>
        </p>
        {risk.assessedAt && <time dateTime={risk.assessedAt} className="num text-xs text-muted-foreground">{formatDateTime(risk.assessedAt)} 평가</time>}
      </div>
      <div className="relative h-1.5 rounded-full bg-border" role="img" aria-label={`위험 신호 ${formatScore(risk.score)}, 선별 기준 ${formatScore(risk.threshold)}`}>
        <span className="absolute inset-y-0 left-0 rounded-full bg-info" style={{ width: `${Math.min(100, (risk.score / scale) * 100)}%` }} />
        <span className="absolute -inset-y-1 w-0.5 bg-foreground" style={{ left: `${Math.min(100, (risk.threshold / scale) * 100)}%` }} />
      </div>
      <p className="text-xs leading-relaxed text-muted-foreground">
        이 호텔의 {risk.targetCheckInDate ? `${formatDay(risk.targetCheckInDate)} 체크인` : "체크인 날짜"}에 대한 신호입니다.
        객실 미제공이 확정됐다는 뜻도, 이 고객의 실패 확률도 아닙니다. 재확인할 예약을 고르는 데만 썼습니다.
      </p>
      {risk.features && (
        <dl className="grid gap-x-4 gap-y-1 text-xs sm:grid-cols-2">
          {featureOrder.filter(name => name in risk.features!).map(name => (
            <div key={name} className="flex items-baseline justify-between gap-2 border-b border-dashed py-0.5">
              <dt className="text-muted-foreground">{riskFeatureLabels[name] ?? name}</dt>
              <dd className="num font-medium">{formatFeature(name, risk.features![name])}</dd>
            </div>
          ))}
        </dl>
      )}
      <p className="num text-[0.7rem] text-muted-foreground">모델 {risk.modelVersion} · 합성 데이터로 학습</p>
    </div>
  );
}

const runNames: Record<string, string> = {
  RECHECK: "공급사·호텔 자동 재확인",
  HOTEL_REPLY_ANALYSIS: "호텔 회신 문장 분석",
  QUOTE_RANKING: "대체 숙소 견적 비교",
  COUNSELOR_ASSIST: "상담 안내 초안 생성",
};
const analysisStatuses = new Map([
  ["EXTRACTED", "날짜별 주장 추출"], ["AMBIGUOUS", "모호함 · 사람 확인 필요"], ["VALID", "비교 결과 유효"], ["REJECTED", "비교 결과 제외"],
  ["LLM", "언어 모델 생성"], ["RULES", "규칙 기반 생성"],
]);

function runDetail(run: AnalysisRun) {
  if (run.kind === "RECHECK") {
    return [
      run.trigger ? triggerLabels.get(run.trigger) ?? run.trigger : null,
      run.failedAttempts ? `실패 ${run.failedAttempts}회` : null,
      run.manualRequired ? "자동 확인 중단 · 상담사 직접 확인 필요" : null,
    ].filter(Boolean).join(" · ");
  }
  return [run.status ? analysisStatuses.get(run.status) ?? run.status : null, run.evidenceId ? `근거 ${run.evidenceId}` : null].filter(Boolean).join(" · ");
}

/** Why this case reached a counselor, the risk signal behind it, and what ran automatically. */
export function CaseInsight({ detail }: { detail: CaseDetail }) {
  const origin = originLabels[detail.origin?.type ?? "UNKNOWN"] ?? originLabels.UNKNOWN;
  const runs = detail.analysisRuns ?? [];
  const OriginIcon = detail.origin?.type === "CUSTOMER_REPORT" ? UserRound : Radar;
  return (
    <section className="panel space-y-3 p-4" aria-label="대응 건이 올라온 이유와 자동 처리 이력">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="text-sm font-semibold">올라온 이유</h3>
        <Badge tone={origin.tone}><OriginIcon aria-hidden="true" />{origin.label}</Badge>
        {detail.createdAt && <time dateTime={detail.createdAt} className="num ml-auto text-xs text-muted-foreground">{formatDateTime(detail.createdAt)} 접수</time>}
      </div>
      <p className="text-sm leading-relaxed text-muted-foreground">{origin.detail}</p>
      {detail.risk
        ? <RiskBlock risk={detail.risk} />
        : detail.origin?.type === "CUSTOMER_REPORT" && <p className="text-xs text-muted-foreground">이 예약은 위험 점검으로 선별되지 않았습니다. 연결된 위험 평가가 없습니다.</p>}

      <div className="border-t pt-3">
        <h3 className="flex items-center gap-1.5 text-sm font-semibold"><Activity className="size-4 text-muted-foreground" aria-hidden="true" />자동 처리 이력</h3>
        {runs.length ? (
          <ol className="mt-2 space-y-2">
            {runs.map((run, index) => {
              const status = run.kind === "RECHECK" && run.status ? jobStatusLabels[run.status] : undefined;
              return (
                <li key={`${run.kind}:${run.at}:${index}`} className="grid grid-cols-[5.5rem_minmax(0,1fr)] gap-x-2 text-sm">
                  <time dateTime={run.at ?? undefined} className="num pt-0.5 text-xs text-muted-foreground">{run.at ? formatDateTime(run.at) : "시각 없음"}</time>
                  <div className="min-w-0">
                    <p className="flex flex-wrap items-center gap-1.5 font-medium">
                      {runNames[run.kind] ?? run.kind}
                      {status && <Badge tone={status.tone}>{status.label}</Badge>}
                    </p>
                    {runDetail(run) && <p className="text-xs text-muted-foreground">{runDetail(run)}</p>}
                    {run.version && <p className="num text-[0.7rem] text-muted-foreground">{run.version}</p>}
                  </div>
                </li>
              );
            })}
          </ol>
        ) : <p className="mt-1 text-sm text-muted-foreground">이 대응 건에 기록된 자동 처리가 없습니다.</p>}
      </div>
    </section>
  );
}
