"use client";

import Link from "next/link";
import { ArrowRight, Headset, ShieldCheck } from "lucide-react";
import { ConsolePage, useConsoleData } from "@/components/console/use-console-data";
import { getOverview } from "@/lib/console-api";

function Step({ value, label, note }: { value: number; label: string; note: string }) {
  return (
    <li className="min-w-0 flex-1 px-4 py-3">
      <p className="num text-3xl font-bold">{value}</p>
      <p className="mt-0.5 text-sm font-medium">{label}</p>
      <p className="text-xs leading-relaxed text-muted-foreground">{note}</p>
    </li>
  );
}

/** Where a counselor starts: what needs a person now, and how much was settled without one. */
export default function Home() {
  const state = useConsoleData(getOverview);
  const data = state.data;
  const active = data ? data.cases.open + data.cases.inProgress + data.cases.waiting : 0;

  return (
    <ConsolePage title="홈" state={state}
      description="체크인 전 위험 점검과 재확인은 자동으로 진행되고, 문제가 확인됐거나 고객이 신고한 예약만 상담사에게 올라옵니다.">
      {data && <>
        <section className="grid gap-3 lg:grid-cols-2">
          <Link href="/cases" className={`panel group flex items-center gap-4 px-5 py-4 outline-none hover:border-primary/50 focus-visible:ring-3 focus-visible:ring-ring/30 ${data.cases.urgentActive ? "border-destructive/40" : ""}`}>
            <Headset className="size-8 shrink-0 text-primary" aria-hidden="true" />
            <div className="min-w-0 flex-1">
              <p className="eyebrow">지금 사람이 봐야 하는 대응 건</p>
              <p className="mt-0.5 text-base font-semibold">
                <span className="num text-3xl font-bold">{active}</span>건
                {data.cases.urgentActive > 0 && <span className="ml-2 text-sm font-semibold text-destructive">긴급 {data.cases.urgentActive}건</span>}
              </p>
              <p className="text-xs text-muted-foreground">접수 {data.cases.open} · 처리 중 {data.cases.inProgress} · 확인 대기 {data.cases.waiting} · 종료 {data.cases.closed}</p>
            </div>
            <ArrowRight className="size-5 text-muted-foreground group-hover:text-primary" aria-hidden="true" />
          </Link>
          <Link href="/risk/rechecks" className="panel group flex items-center gap-4 px-5 py-4 outline-none hover:border-primary/50 focus-visible:ring-3 focus-visible:ring-ring/30">
            <ShieldCheck className="size-8 shrink-0 text-success" aria-hidden="true" />
            <div className="min-w-0 flex-1">
              <p className="eyebrow">자동 확인만으로 끝난 예약</p>
              <p className="mt-0.5 text-base font-semibold"><span className="num text-3xl font-bold">{data.autoChecks.bookingsAllAvailable}</span>건</p>
              <p className="text-xs text-muted-foreground">전 숙박일 제공 가능이 확인돼 대응 건을 만들지 않았습니다.</p>
            </div>
            <ArrowRight className="size-5 text-muted-foreground group-hover:text-primary" aria-hidden="true" />
          </Link>
        </section>

        <section className="panel" aria-label="자동 확인에서 상담까지">
          <h2 className="border-b px-4 py-2.5 text-sm font-semibold">자동 확인에서 상담까지</h2>
          <ol className="flex flex-col divide-y md:flex-row md:divide-x md:divide-y-0">
            <Step value={data.risk.assessments} label="위험 평가" note="호텔×체크인 날짜별로 계산한 위험 신호" />
            <Step value={data.autoChecks.bookingsChecked} label="재확인한 예약" note="공급사와 호텔에 예약 상태를 다시 물은 예약" />
            <Step value={data.autoChecks.bookingsAllAvailable} label="정상 확인" note="전 숙박일 제공 가능 · 상담사 개입 없음" />
            <Step value={data.autoChecks.bookingsWithUnavailable} label="문제 확인" note="제공 불가 숙박일이 검증된 예약" />
            <Step value={data.autoChecks.casesOpenedByRecheck} label="사전 발견 대응 건" note="재확인으로 만들어진 대응 건" />
          </ol>
          {(data.autoChecks.failed > 0 || data.autoChecks.pending > 0) && (
            <p className="border-t px-4 py-2.5 text-xs text-muted-foreground">
              진행 중인 재확인 {data.autoChecks.pending}건 · 자동 확인에 실패해 사람이 확인해야 하는 작업 {data.autoChecks.failed}건
            </p>
          )}
        </section>

        <p className="text-xs leading-relaxed text-muted-foreground">
          모든 수치는 업무 DB에 저장된 기록을 센 것입니다. 가상 공급사·호텔과 합성 데이터로 만든 시연이며, 실제 상담 시간 단축이나 민원 감소를 측정한 값이 아닙니다.
        </p>
      </>}
    </ConsolePage>
  );
}
