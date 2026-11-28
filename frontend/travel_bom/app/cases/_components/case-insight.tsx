import Link from "next/link";
import type { ReactNode } from "react";
import { BadgeCheck, Cpu, Radar, ShieldAlert, UserRound } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import {
  evidenceSourceLabels, formatDateTime, formatDay, formatFeature, formatScore, formatSignal, jobStatusLabels, SIGNAL_LINE, signalPercent, originLabels, policyLabels, policyNotes, provisionLabels,
  riskFeatureLabels, supplierStatusLabels, triggerLabels,
} from "@/lib/case-labels";
import type { AnalysisRun, CaseDetail, RiskSignal } from "../_types/case";

const featureOrder = ["targetConfirmedBookingCount", "pastFailureRate90d", "reconfirmationOverdueRate7d", "inventoryUnavailableRate7d", "bookingVolumeGrowth7d", "isPeakDay"];

/** The model inputs behind the signal. Only shown inside "상세 근거 확인". */
function RiskDetails({ risk }: { risk: RiskSignal }) {
  return (
    <div className="space-y-2 rounded-md bg-muted/60 p-3">
      <div className="relative h-1.5 rounded-full bg-border" role="img" aria-label={`위험 신호 ${formatSignal(risk.score, risk.threshold)}, 선별 기준 ${SIGNAL_LINE}%`}>
        <span className="absolute inset-y-0 left-0 rounded-full bg-info" style={{ width: `${signalPercent(risk.score, risk.threshold)}%` }} />
        <span className="absolute -inset-y-1 w-0.5 bg-foreground" style={{ left: `${SIGNAL_LINE}%` }} />
      </div>
      <p className="num text-muted-foreground">선별 기준이 {SIGNAL_LINE}%가 되도록 환산한 값입니다. 원점수 {formatScore(risk.score)} · 원래 선별 기준 {formatScore(risk.threshold)}</p>
      {policyNotes.has(risk.policy) && <p className="leading-relaxed">{policyNotes.get(risk.policy)}</p>}
      <p className="leading-relaxed text-muted-foreground">
        이 호텔의 {risk.targetCheckInDate ? `${formatDay(risk.targetCheckInDate)} 체크인` : "체크인 날짜"}에 대한 신호입니다.
        객실 미제공이 확정됐다는 뜻도, 이 고객의 실패 확률도 아닙니다. 재확인할 예약을 고르는 데만 썼습니다.
      </p>
      {risk.features && (
        <dl className="grid gap-x-4 gap-y-1 sm:grid-cols-2">
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
  COUNSELOR_ASSIST: "고객 안내 초안 생성",
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

type Step = { at?: string | null; title: ReactNode; body?: ReactNode; danger?: boolean };

function contractStatus(content: string | null) {
  try {
    const value: unknown = JSON.parse(content ?? "");
    return typeof value === "object" && value !== null && "contractStatus" in value && typeof value.contractStatus === "string" ? value.contractStatus : null;
  } catch { return null; }
}

/** Everything stored about how the case came to be, as one ordered list of steps. */
function steps(detail: CaseDetail): Step[] {
  const origin = originLabels[detail.origin?.type ?? "UNKNOWN"] ?? originLabels.UNKNOWN;
  const list: Step[] = [];
  if (detail.risk) list.push({ at: detail.risk.assessedAt, title: "위험 점검이 이 예약을 재확인 대상으로 골랐습니다" });
  for (const report of detail.customerReports) {
    list.push({ at: report.receivedAt, danger: true, title: <>고객이 체크인 문제를 신고했습니다{report.atHotel && <Badge tone="danger">호텔 현장</Badge>}</> });
  }
  for (const record of detail.evidence) {
    const source = evidenceSourceLabels.get(record.sourceType) ?? "확인 기록";
    if (record.sourceType.startsWith("SUPPLIER")) {
      const status = contractStatus(record.originalContent);
      list.push({ at: record.receivedAt, title: status ? `${source}: "${supplierStatusLabels.get(status) ?? status}"이라고 답했습니다` : `${source} 응답을 받았습니다` });
    } else {
      list.push({
        at: record.receivedAt,
        danger: record.assertions.some(assertion => assertion.reportedProvisionStatus === "UNAVAILABLE"),
        title: record.assertions.length
          ? `${source}: ${record.assertions.map(assertion => `${formatDay(assertion.stayDate)} ${provisionLabels.get(assertion.reportedProvisionStatus) ?? "확인 필요"}`).join(" · ")}`
          : `${source}을 받았습니다`,
      });
    }
  }
  if (detail.createdAt) list.push({ at: detail.createdAt, title: <>상담 건이 접수됐습니다<Badge tone={origin.tone}>{origin.label}</Badge></> });
  for (const run of detail.analysisRuns ?? []) {
    const status = run.kind === "RECHECK" && run.status ? jobStatusLabels[run.status] : undefined;
    list.push({
      at: run.at,
      title: <>{runNames[run.kind] ?? run.kind}{status && <Badge tone={status.tone}>{status.label}</Badge>}</>,
      body: <>
        {runDetail(run) && <p className="text-muted-foreground">{runDetail(run)}</p>}
        {run.kind === "HOTEL_REPLY_ANALYSIS" && <Link href="/risk/analyses" className="text-primary underline-offset-4 hover:underline">분석 기록 보기</Link>}
      </>,
    });
  }
  // Stable sort: steps recorded at the same instant keep the order above; steps without a time go last.
  const time = (step: Step) => (step.at ? new Date(step.at).getTime() : Number.POSITIVE_INFINITY);
  return list.sort((left, right) => time(left) - time(right));
}

function Block({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="space-y-1.5">
      <p className="eyebrow">{title}</p>
      {children}
    </div>
  );
}

/**
 * Why the booking is treated as at risk, in the order a counselor needs it: the situation, the
 * factors, what is a confirmed fact against what the system estimated, and what to do next.
 * Model inputs and the processing history stay folded.
 */
export function CaseRiskBasis({ detail }: { detail: CaseDetail }) {
  const origin = originLabels[detail.origin?.type ?? "UNKNOWN"] ?? originLabels.UNKNOWN;
  const OriginIcon = detail.origin?.type === "CUSTOMER_REPORT" ? UserRound : Radar;
  const risk = detail.risk;
  const list = steps(detail);
  const versions = [...new Set((detail.analysisRuns ?? []).filter(run => run.version).map(run => `${runNames[run.kind] ?? run.kind} · ${run.version}`))];
  return (
    <section className="panel space-y-3.5 p-4" aria-label="위험 판단 및 탐지 근거">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="flex items-center gap-1.5 text-base font-semibold"><ShieldAlert className="size-4 text-destructive" aria-hidden="true" />위험 판단 및 탐지 근거</h3>
        <Badge tone={origin.tone}><OriginIcon aria-hidden="true" />{origin.label}</Badge>
      </div>

      <p className="text-sm leading-relaxed font-medium">{origin.detail}</p>

      <Block title="시스템 판단 이유">
        <ul className="space-y-1.5 text-sm">
          <li className="grid grid-cols-[6.5rem_minmax(0,1fr)] items-baseline gap-x-2">
            <Badge tone="success"><BadgeCheck aria-hidden="true" />확인된 사실</Badge>
            <span>위 판정은 호텔·공급사 회신 중 사실로 채택된 내용만 반영합니다.</span>
          </li>
          <li className="grid grid-cols-[6.5rem_minmax(0,1fr)] items-baseline gap-x-2">
            <Badge tone="info"><Cpu aria-hidden="true" />시스템 추정</Badge>
            {risk
              ? <span>위험 신호 <strong className="num">{formatSignal(risk.score, risk.threshold)}</strong>가 선별 기준 <span className="num">{SIGNAL_LINE}%</span> 이상이라 재확인 대상으로 골랐습니다. 장애 확정도 장애 확률도 아닙니다.</span>
              : <span className="text-muted-foreground">이 예약에 연결된 위험 평가가 없습니다. 추정 없이 접수된 건입니다.</span>}
          </li>
        </ul>
      </Block>

      <details className="group text-xs">
        <summary className="cursor-pointer list-none font-medium text-primary select-none">
          <span className="group-open:hidden">+ 상세 근거 확인</span><span className="hidden group-open:inline">− 상세 근거 닫기</span>
        </summary>
        <div className="mt-2.5 space-y-3">
          {risk && <RiskDetails risk={risk} />}
          {risk && <p className="num text-muted-foreground">선별 정책 {policyLabels.get(risk.policy) ?? risk.policy}{risk.assessedAt && ` · ${formatDateTime(risk.assessedAt)} 평가`}</p>}
          <Block title="처리 경위">
            {list.length ? (
              <ol className="space-y-2">
                {list.map((step, index) => (
                  <li key={index} className="grid grid-cols-[5.5rem_minmax(0,1fr)] gap-x-2">
                    <time dateTime={step.at ?? undefined} className="num text-muted-foreground">{step.at ? formatDateTime(step.at) : "시각 없음"}</time>
                    <div className="min-w-0 space-y-1">
                      <p className={`flex flex-wrap items-center gap-1.5 text-sm font-medium ${step.danger ? "text-destructive" : ""}`}>{step.title}</p>
                      {step.body}
                    </div>
                  </li>
                ))}
              </ol>
            ) : <p className="text-muted-foreground">경위를 보여 줄 기록이 없습니다.</p>}
          </Block>
          {versions.length > 0 && <ul className="num space-y-0.5 text-muted-foreground">{versions.map(version => <li key={version}>{version}</li>)}</ul>}
        </div>
      </details>
    </section>
  );
}
