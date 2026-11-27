"use client";

import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { NightStrip } from "@/components/console/night-strip";
import { ConsolePage, EmptyState, useConsoleData } from "@/components/console/use-console-data";
import { formatDateTime, formatScore, jobStatusLabels, triggerLabels } from "@/lib/case-labels";
import { getRecheckJobs, type RecheckJob } from "@/lib/console-api";

function outcome(job: RecheckJob) {
  if (job.status === "FAILED") return { text: job.manualRequired ? "자동 확인 중단 · 상담사 직접 확인 필요" : "자동 확인 실패", tone: "danger" as const };
  if (job.status !== "COMPLETED") return { text: "확인 진행 중", tone: "neutral" as const };
  if (job.nights.some(night => night.factStatus === "VERIFIED" && night.fulfillmentStatus === "UNAVAILABLE")) return { text: "제공 불가 숙박일 확인", tone: "danger" as const };
  if (job.nights.length > 0 && job.nights.every(night => night.factStatus === "VERIFIED" && night.fulfillmentStatus === "AVAILABLE")) return { text: "전 숙박일 정상", tone: "success" as const };
  return { text: "일부 숙박일 미확인", tone: "warning" as const };
}

/** The result of every automatic re-check, including the ones that never became a case. */
export default function RecheckJobs() {
  const state = useConsoleData(getRecheckJobs);
  const jobs = state.data ?? [];
  const done = jobs.filter(job => job.status === "COMPLETED");
  const normal = done.filter(job => outcome(job).tone === "success").length;
  const withCase = jobs.filter(job => job.caseId).length;

  return (
    <ConsolePage title="자동 확인" state={state}
      description={<>
        <strong className="font-medium text-foreground">전 숙박일 제공 가능 시 자동 종료 · 1박 이상 제공 불가 검증 시 대응 건 생성</strong>
        <Link href="/risk/analyses" className="ml-3 text-primary underline-offset-4 hover:underline">호텔 회신 분석 기록</Link>
      </>}>
      {state.data && <>
        <dl className="grid gap-3 sm:grid-cols-4">
          {[
            ["자동 확인", jobs.length, "지금까지 실행한 재확인"],
            ["정상으로 끝남", normal, "상담사에게 올라가지 않음"],
            ["대응 건으로 연결", withCase, "문제 확인 또는 고객 신고"],
            ["사람 확인 필요", jobs.filter(job => job.status === "FAILED").length, "자동 확인 실패"],
          ].map(([label, value, hint]) => (
            <div key={label} className="panel px-4 py-3">
              <dt className="eyebrow">{label}</dt>
              <dd className="num text-2xl font-bold">{value}</dd>
              <p className="text-xs text-muted-foreground">{hint}</p>
            </div>
          ))}
        </dl>

        {jobs.length === 0 ? <EmptyState>아직 실행된 자동 확인이 없습니다. 위험 점검 배치가 예약을 고르거나 고객이 신고하면 여기에 기록됩니다.</EmptyState> : (
          <div className="panel overflow-x-auto">
            <table className="w-full min-w-[980px] border-collapse text-left text-sm">
              <thead className="border-b text-xs text-muted-foreground">
                <tr>
                  <th scope="col" className="px-4 py-2.5 font-medium">예약 · 숙소</th>
                  <th scope="col" className="px-4 py-2.5 font-medium">확인 계기</th>
                  <th scope="col" className="w-64 px-4 py-2.5 font-medium">숙박일별 결과</th>
                  <th scope="col" className="px-4 py-2.5 font-medium">결과</th>
                  <th scope="col" className="px-4 py-2.5 font-medium">대응 건</th>
                  <th scope="col" className="px-4 py-2.5 font-medium">완료 시각</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {jobs.map(job => {
                  const result = outcome(job);
                  const status = jobStatusLabels[job.status];
                  return (
                    <tr key={job.jobCode} className="align-top">
                      <th scope="row" className="px-4 py-3 font-normal">
                        <p className="num font-bold">{job.bookingId}</p>
                        <p className="text-xs text-muted-foreground">{job.hotelName}</p>
                      </th>
                      <td className="px-4 py-3">
                        <p>{triggerLabels.get(job.trigger) ?? job.trigger}</p>
                        {job.riskScore !== null && (
                          <p className="num text-xs text-muted-foreground">위험 신호 {formatScore(job.riskScore)}</p>
                        )}
                      </td>
                      <td className="px-4 py-3"><NightStrip size="sm" nights={job.nights} /></td>
                      <td className="px-4 py-3">
                        <Badge tone={result.tone}>{result.text}</Badge>
                        <p className="mt-1 text-xs text-muted-foreground">
                          {status?.label ?? job.status}
                          {job.failedAttempts > 0 && ` · 실패 ${job.failedAttempts}회`}
                          {job.errorCode && <span className="num"> · {job.errorCode}</span>}
                          {" · "}
                          <Link href={`/risk/evidence?booking=${encodeURIComponent(job.bookingId)}`} className="text-primary underline-offset-4 hover:underline">근거 보기</Link>
                        </p>
                      </td>
                      <td className="px-4 py-3">
                        {job.caseId
                          ? <Link href={`/cases?case=${job.caseId}`} className="num font-medium text-primary underline-offset-4 hover:underline">{job.caseId}</Link>
                          : <span className="text-xs text-muted-foreground">만들지 않음</span>}
                      </td>
                      <td className="num px-4 py-3 text-xs text-muted-foreground">{job.finishedAt ? formatDateTime(job.finishedAt) : "진행 중"}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        <p className="text-xs text-muted-foreground">공급사·호텔 응답은 시연용 가상 응답입니다. 미확인·응답 대기·오류는 객실 제공 불가로 처리하지 않습니다.</p>
      </>}
    </ConsolePage>
  );
}
