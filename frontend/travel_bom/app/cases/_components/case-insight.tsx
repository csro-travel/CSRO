"use client";

import Link from "next/link";
import { useState, type ReactNode } from "react";
import { BadgeCheck, ClipboardList, Cpu, Radar, UserRound } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import {
  evidenceSourceLabels, formatDateTime, formatDay, jobStatusLabels, originLabels, provisionLabels, supplierStatusLabels, triggerLabels,
} from "@/lib/case-labels";
import type { AnalysisRun, CaseDetail } from "../_types/case";

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
  return run.status ? analysisStatuses.get(run.status) ?? "" : "";
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

/**
 * What is a confirmed fact against what the system only estimated, and how the case arrived.
 * How the model chose the booking is not shown here; it lives on the risk assessment page.
 */
export function CaseRiskBasis({ detail }: { detail: CaseDetail }) {
  const origin = originLabels[detail.origin?.type ?? "UNKNOWN"] ?? originLabels.UNKNOWN;
  const OriginIcon = detail.origin?.type === "CUSTOMER_REPORT" ? UserRound : Radar;
  const list = steps(detail);
  const [open, setOpen] = useState(false);
  return (
    <section className="panel flex flex-col gap-3.5 p-4" aria-label="확인된 내용">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="flex items-center gap-1.5 text-base font-semibold"><ClipboardList className="size-4 text-muted-foreground" aria-hidden="true" />확인된 내용</h3>
        <Badge tone="outline"><OriginIcon aria-hidden="true" />{origin.label}</Badge>
      </div>

      <ul className="space-y-1.5 text-sm">
        <li className="grid grid-cols-[6.5rem_minmax(0,1fr)] items-baseline gap-x-2">
          <Badge tone="success"><BadgeCheck aria-hidden="true" />확인된 사실</Badge>
          <span>위 판정은 호텔·공급사 회신 중 사실로 채택된 내용만 반영합니다.</span>
        </li>
        <li className="grid grid-cols-[6.5rem_minmax(0,1fr)] items-baseline gap-x-2">
          <Badge tone="outline"><Cpu aria-hidden="true" />시스템 추정</Badge>
          {detail.risk
            ? <span>사전 점검이 먼저 확인하도록 고른 예약입니다. 장애 확정이 아닙니다. <Link href="/risk/assessments" className="text-primary underline-offset-4 hover:underline">선별 근거 보기</Link></span>
            : <span className="text-muted-foreground">이 예약에 연결된 위험 평가가 없습니다. 추정 없이 접수된 건입니다.</span>}
        </li>
      </ul>

      <div aria-label="처리 이력" className="flex min-h-0 flex-1 flex-col text-xs">
        <button type="button" aria-expanded={open} onClick={() => setOpen(value => !value)}
          className="w-fit cursor-pointer rounded font-medium text-primary outline-none select-none focus-visible:ring-3 focus-visible:ring-ring/30">
          {open ? "− 처리 이력 닫기" : "+ 처리 이력 보기"}
        </button>
        {open && (
          // Positioned inside a growing box: the list takes the remaining height of the panel instead of stretching it.
          <div className="relative mt-2.5 min-h-40 flex-1">
            <div className="scroll-area absolute inset-0 pr-1">
              {list.length ? (
                <ol className="space-y-2">
                  {list.map((step, index) => (
                    <li key={index} className="grid grid-cols-[5.5rem_minmax(0,1fr)] gap-x-2">
                      <time dateTime={step.at ?? undefined} className="num text-muted-foreground">{step.at ? formatDateTime(step.at) : "시각 없음"}</time>
                      <div className="min-w-0 space-y-1">
                        <p className={`flex flex-wrap items-center gap-1.5 text-sm font-medium ${step.danger ? "text-accent-foreground" : ""}`}>{step.title}</p>
                        {step.body}
                      </div>
                    </li>
                  ))}
                </ol>
              ) : <p className="text-muted-foreground">이력을 보여 줄 기록이 없습니다.</p>}
            </div>
          </div>
        )}
      </div>
    </section>
  );
}
