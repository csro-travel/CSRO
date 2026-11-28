"use client";

import Link from "next/link";
import { ChevronRight } from "lucide-react";
import { NightStrip } from "@/components/console/night-strip";
import { ConsolePage, EmptyState, useConsoleData } from "@/components/console/use-console-data";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import { formatDateTime, taskLabels } from "@/lib/case-labels";
import { getOverview, type NightView } from "@/lib/console-api";
import { getCases } from "./cases/_api/cases";
import type { CaseListItem } from "./cases/_types/case";

const priorityCaseLimit = 3;
const queueColumns = "lg:grid-cols-[minmax(0,1fr)_minmax(0,1.6fr)_minmax(0,0.9fr)_1.25rem]";
const countFormat = new Intl.NumberFormat("ko-KR");

async function loadPriorityCases(signal: AbortSignal) {
  const priorityCases = new Map<string, CaseListItem>();

  // The default list is active and sorted by the server. There is no next-action filter.
  for (let page = 0; priorityCases.size < priorityCaseLimit; page += 1) {
    const { items, pagination } = await getCases(page, undefined, signal);
    for (const item of items) {
      if (item.priority === "URGENT" || item.nextAction) priorityCases.set(item.caseId, item);
      if (priorityCases.size === priorityCaseLimit) break;
    }
    if (!pagination.hasNext) break;
  }

  return [...priorityCases.values()];
}

const load = (signal: AbortSignal) =>
  Promise.all([loadPriorityCases(signal), getOverview(signal)]).then(([priorityCases, overview]) => ({ priorityCases, overview }));

function problemNights({ unavailableStayDates, unknownStayDates, conflictStayDates }: CaseListItem): NightView[] {
  const unavailable = new Set(unavailableStayDates);
  const conflict = new Set(conflictStayDates);
  const dates = new Set([...unavailable, ...(unknownStayDates ?? []), ...conflict]);

  return [...dates].sort().map(stayDate => {
    if (conflict.has(stayDate)) return { stayDate, fulfillmentStatus: "UNKNOWN", factStatus: "CONFLICT" };
    if (unavailable.has(stayDate)) return { stayDate, fulfillmentStatus: "UNAVAILABLE", factStatus: "VERIFIED" };
    return { stayDate, fulfillmentStatus: "UNKNOWN", factStatus: "UNCONFIRMED" };
  });
}

function PriorityCase({ item }: { item: CaseListItem }) {
  const urgent = item.priority === "URGENT";
  const nights = problemNights(item);
  const task = item.nextAction;

  return (
    <li>
      <Link
        href={`/cases?case=${encodeURIComponent(item.caseId)}`}
        aria-label={`${item.caseId} ${item.hotelName} 대응 건 열기`}
        className={`grid grid-cols-[minmax(0,1fr)_auto] items-start gap-x-4 gap-y-2.5 px-4 py-4 outline-none hover:bg-muted/60 focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:ring-inset ${queueColumns}`}
      >
        <div className="min-w-0">
          <p className="font-semibold wrap-anywhere">{item.hotelName}</p>
          <div className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1">
            <span className="num text-sm text-muted-foreground">{item.caseId}</span>
            <Badge tone={urgent ? "urgent" : "neutral"}>{urgent ? "긴급" : "일반"}</Badge>
          </div>
        </div>
        <div className="col-span-2 min-w-0 lg:col-span-1">
          <p className="mb-1 text-xs font-medium text-muted-foreground lg:sr-only">숙박일별 객실 상태</p>
          {nights.length > 0 ? (
            <NightStrip nights={nights} className="flex-wrap [&>li]:min-w-22 [&>li]:flex-none" />
          ) : <p className="text-sm text-muted-foreground">표시할 문제 숙박일 없음</p>}
        </div>
        <div className="col-span-2 min-w-0 lg:col-span-1">
          <p className="mb-0.5 text-xs font-medium text-muted-foreground lg:sr-only">다음 업무 · 기한</p>
          {task ? (
            <>
              <p className="font-semibold wrap-anywhere">{taskLabels.get(task.type) ?? task.type}</p>
              <p className="num mt-0.5 text-sm text-muted-foreground">
                {task.dueAt ? <>기한 <time dateTime={task.dueAt}>{formatDateTime(task.dueAt)}</time></> : "기한 미정"}
              </p>
            </>
          ) : <p className="text-sm text-muted-foreground">등록된 다음 업무 없음</p>}
        </div>
        <ChevronRight className="col-start-2 row-start-1 mt-1 size-5 text-primary lg:col-start-4" aria-hidden="true" />
      </Link>
    </li>
  );
}

export default function Home() {
  const state = useConsoleData(load);
  const { data } = state;
  const autoChecks = data?.overview.autoChecks;
  const urgent = data?.overview.cases.urgentActive;
  const priorityCases = data?.priorityCases;

  return (
    <ConsolePage title="홈" state={state}>
      {autoChecks && urgent !== undefined && (
        <section aria-label="상담 운영 현황" className="panel flex flex-wrap items-center justify-between gap-x-5 gap-y-2 px-4 py-2.5">
          <div role="group" aria-labelledby="auto-check-overview" className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
            <h2 id="auto-check-overview" className="text-sm font-semibold text-muted-foreground">자동 재확인 현황</h2>
            <ul role="list" className="flex flex-wrap gap-x-2 gap-y-1 text-base sm:[&>li+li]:before:mr-2 sm:[&>li+li]:before:text-muted-foreground sm:[&>li+li]:before:content-['·']">
              {([
                ["재확인한 예약", autoChecks.bookingsChecked],
                ["전 숙박일 정상 확인", autoChecks.bookingsAllAvailable],
                ["사전 발견 대응", autoChecks.casesOpenedByRecheck],
              ] as const).map(([label, value]) => (
                <li key={label}>{label} <strong className="num">{countFormat.format(value)}건</strong></li>
              ))}
            </ul>
            {autoChecks.pending > 0 && (
              <span className="text-sm text-muted-foreground">진행 중인 확인 작업 <strong className="num">{countFormat.format(autoChecks.pending)}건</strong></span>
            )}
          </div>
          <Badge tone={urgent > 0 ? "urgent" : "neutral"} className="px-2.5 py-1 text-sm">
            긴급 대응 <strong className="num">{countFormat.format(urgent)}건</strong>
          </Badge>
        </section>
      )}

      <section aria-labelledby="priority-cases" className="space-y-2">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 id="priority-cases" className="text-lg font-bold">먼저 처리할 대응 건</h2>
          <Link href="/cases" className={buttonVariants({ variant: "outline" })}>
            상담 대응 전체 보기 <ChevronRight aria-hidden="true" />
          </Link>
        </div>

        {priorityCases && (priorityCases.length > 0 ? (
          <div className="panel overflow-hidden">
            <div aria-hidden="true" className={`hidden gap-x-4 border-b px-4 py-2.5 text-sm font-medium text-muted-foreground lg:grid ${queueColumns}`}>
              <span>숙소 · 대응 건</span>
              <span>숙박일별 객실 상태</span>
              <span>다음 업무 · 기한</span>
            </div>
            <ul role="list" className="divide-y">
              {priorityCases.map(item => <PriorityCase key={item.caseId} item={item} />)}
            </ul>
          </div>
        ) : <EmptyState>지금 먼저 처리할 대응 건이 없습니다</EmptyState>
        )}
      </section>
    </ConsolePage>
  );
}
