import { BellRing, ChevronRight, Radar, Search, UserRound } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NightStrip, nightsFromDateLists } from "@/components/console/night-strip";
import { caseStatusLabels, formatDateTime, formatElapsed, originLabels, taskLabels } from "@/lib/case-labels";
import type { ConsoleOverview } from "@/lib/console-api";
import type { CaseList, CaseStatus } from "../_types/case";

const statusOptions = [undefined, "OPEN", "IN_PROGRESS", "WAITING", "CLOSED"] as const;

type CaseListProps = {
  list: CaseList | undefined;
  overview: ConsoleOverview | undefined;
  status: CaseStatus | undefined;
  error: string;
  newCaseIds: ReadonlySet<string>;
  onStatusChange: (status: CaseStatus | undefined) => void;
  search: string;
  onSearch: (search: string) => void;
  onPageChange: (page: number) => void;
  onOpen: (caseId: string) => void;
  onRetry: () => void;
};

function Tile({ label, value, hint, emphasis = false }: { label: string; value: number | undefined; hint: string; emphasis?: boolean }) {
  return (
    <div className={`panel px-4 py-3 ${emphasis && value ? "border-destructive/40" : ""}`}>
      <p className="eyebrow">{label}</p>
      <p className={`num mt-0.5 text-2xl font-bold ${emphasis && value ? "text-destructive" : ""}`}>{value ?? "–"}</p>
      <p className="text-xs text-muted-foreground">{hint}</p>
    </div>
  );
}

export function CaseListView({
  list, overview, status, error, newCaseIds, onStatusChange, search, onSearch, onPageChange, onOpen, onRetry,
}: CaseListProps) {
  const pagination = list?.pagination;
  const pageCount = pagination ? Math.ceil(pagination.totalElements / pagination.size) : 0;
  const counts: Record<string, number | undefined> = {
    OPEN: overview?.cases.open, IN_PROGRESS: overview?.cases.inProgress, WAITING: overview?.cases.waiting, CLOSED: overview?.cases.closed,
  };

  return (
    <section className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <Tile label="긴급 대응 건" value={overview?.cases.urgentActive} hint="지금 열려 있는 긴급 건" emphasis />
        <Tile label="새로 접수" value={overview?.cases.open} hint="아직 처리를 시작하지 않은 건" />
        <Tile label="확인 대기" value={overview?.cases.waiting} hint="호텔 확인이 끝나지 않은 건" />
        <Tile label="자동 확인으로 끝난 예약" value={overview?.autoChecks.bookingsAllAvailable}
          hint={overview ? `재확인한 ${overview.autoChecks.bookingsChecked}건 중 전 숙박일 정상 · 상담사에게 올라오지 않음` : "재확인 결과 전 숙박일 정상"} />
      </div>

      {newCaseIds.size > 0 && (
        <p role="status" className="panel flex items-center gap-2 border-primary/40 bg-accent px-4 py-2.5 text-sm font-medium text-accent-foreground">
          <BellRing className="size-4" aria-hidden="true" />
          새 대응 건 {newCaseIds.size}건이 접수됐습니다: {[...newCaseIds].join(", ")}
        </p>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <div role="group" aria-label="처리 상태 필터" className="flex flex-wrap gap-1.5">
          {statusOptions.map(option => (
            <Button key={option ?? "all"} type="button" variant={status === option ? "default" : "outline"} aria-pressed={status === option}
              onClick={() => onStatusChange(option)}>
              {option ? caseStatusLabels[option].label : "진행 중 전체"}
              {option && counts[option] !== undefined && <span className="num opacity-70">{counts[option]}</span>}
            </Button>
          ))}
        </div>
        <form role="search" className="ml-auto flex min-w-64 flex-1 gap-2 sm:max-w-sm"
          onSubmit={event => { event.preventDefault(); onSearch(String(new FormData(event.currentTarget).get("q") ?? "")); }}>
          <div className="relative flex-1">
            <Search className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
            <Input name="q" defaultValue={search} maxLength={40} aria-label="대응 건 검색" placeholder="대응 건·예약번호·숙소명·고객명" className="h-8 pl-8" />
          </div>
          <Button type="submit" variant="outline">검색</Button>
        </form>
      </div>

      <div role="region" aria-label="상담 대응 목록" tabIndex={0} className="panel overflow-x-auto">
        <table className="w-full min-w-[920px] border-collapse text-left text-sm">
          <thead className="border-b text-xs text-muted-foreground">
            <tr>
              <th scope="col" className="w-36 px-4 py-2.5 font-medium">대응 건</th>
              <th scope="col" className="px-4 py-2.5 font-medium">올라온 이유 · 확인된 문제</th>
              <th scope="col" className="w-64 px-4 py-2.5 font-medium">숙박일별 상태</th>
              <th scope="col" className="w-44 px-4 py-2.5 font-medium">다음 업무 · 기한</th>
              <th scope="col" className="w-28 px-4 py-2.5 font-medium">상태</th>
              <th scope="col" className="w-10 px-2 py-2.5"><span className="sr-only">열기</span></th>
            </tr>
          </thead>
          <tbody className="divide-y">
            {(!list || list.items.length === 0) && (
              <tr>
                <td colSpan={6} className="px-4 py-12 text-center">
                  <p role={error ? "alert" : "status"} className={error ? "text-destructive" : "text-muted-foreground"}>
                    {error || (list
                      ? (search ? "검색 결과가 없습니다." : status ? "이 상태의 대응 건이 없습니다." : "지금 처리할 대응 건이 없습니다. 새 건이 접수되면 이 화면에 바로 나타납니다.")
                      : "대응 건을 불러오는 중…")}
                  </p>
                  {error && <Button type="button" variant="outline" className="mt-3" onClick={onRetry}>다시 시도</Button>}
                  {!error && status && pagination?.totalElements === 0 && (
                    <Button type="button" variant="outline" className="mt-3" onClick={() => onStatusChange(undefined)}>진행 중 전체 보기</Button>
                  )}
                </td>
              </tr>
            )}
            {list?.items.map(item => {
              const urgent = item.priority === "URGENT";
              const state = caseStatusLabels[item.status];
              const origin = originLabels[item.origin?.type ?? "UNKNOWN"] ?? originLabels.UNKNOWN;
              const task = item.nextAction;
              const fresh = newCaseIds.has(item.caseId);
              const OriginIcon = item.origin?.type === "CUSTOMER_REPORT" ? UserRound : Radar;
              return (
                <tr key={item.caseId} onClick={() => onOpen(item.caseId)}
                  className={`cursor-pointer align-top hover:bg-muted/60 ${fresh ? "bg-accent/60" : ""} ${urgent && item.status !== "CLOSED" ? "shadow-[inset_3px_0_0_var(--destructive)]" : ""}`}>
                  <th scope="row" className="px-4 py-3 font-normal">
                    <p className="flex items-center gap-1.5">
                      <span className="num text-base font-bold">{item.caseId}</span>
                      {urgent && item.status !== "CLOSED" && <Badge tone="danger">긴급</Badge>}
                      {fresh && <Badge tone="brand">새 건</Badge>}
                    </p>
                    <p className="num mt-0.5 text-xs text-muted-foreground">{item.bookingId}</p>
                    {item.createdAt && <p className="mt-0.5 text-xs text-muted-foreground" title={formatDateTime(item.createdAt)}>{formatElapsed(item.createdAt)} 접수</p>}
                  </th>
                  <td className="px-4 py-3">
                    <Badge tone={origin.tone}><OriginIcon aria-hidden="true" />{origin.label}</Badge>
                    <p className="mt-1.5 font-medium">{item.summary}</p>
                    <p className="mt-0.5 text-xs text-muted-foreground">{item.hotelName}</p>
                  </td>
                  <td className="px-4 py-3">
                    <NightStrip size="sm" nights={nightsFromDateLists(item.checkInDate, item.checkOutDate,
                      { unavailable: item.unavailableStayDates, unknown: item.unknownStayDates, conflict: item.conflictStayDates })} />
                  </td>
                  <td className="px-4 py-3">
                    {task ? <>
                      <p className="font-medium">{taskLabels.get(task.type) ?? task.type}</p>
                      <p className="num mt-0.5 text-xs text-muted-foreground">{task.dueAt ? `기한 ${formatDateTime(task.dueAt)}` : "기한 미정"}</p>
                    </> : <p className="text-xs text-muted-foreground">남은 업무 없음</p>}
                  </td>
                  <td className="px-4 py-3"><Badge tone={state.tone}>{state.label}</Badge></td>
                  <td className="px-2 py-3 text-right">
                    <Button type="button" variant="ghost" size="icon" aria-label={`${item.caseId} 대응 건 열기`}
                      onClick={event => { event.stopPropagation(); onOpen(item.caseId); }}>
                      <ChevronRight aria-hidden="true" />
                    </Button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {pagination && pageCount > 1 && (
        <nav aria-label="상담 대응 목록 페이지" className="flex flex-wrap items-center justify-end gap-3 text-sm">
          <Button type="button" variant="outline" disabled={pagination.page === 0} onClick={() => onPageChange(pagination.page - 1)}>이전</Button>
          <span className="num text-muted-foreground">{pagination.page + 1} / {pageCount} 페이지</span>
          <Button type="button" variant="outline" disabled={!pagination.hasNext} onClick={() => onPageChange(pagination.page + 1)}>다음</Button>
        </nav>
      )}
    </section>
  );
}
