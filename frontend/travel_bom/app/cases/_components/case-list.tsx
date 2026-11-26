import { Button } from "@/components/ui/button";
import type { CaseList, CaseStatus } from "../_types/case";

const caseStatuses = {
  OPEN: { label: "접수", tone: "bg-accent text-accent-foreground" },
  IN_PROGRESS: { label: "처리 중", tone: "bg-info/10 text-info" },
  WAITING: {
    label: "확인 대기",
    tone: "bg-secondary text-secondary-foreground",
  },
  CLOSED: { label: "종료", tone: "bg-muted text-muted-foreground" },
} satisfies Record<CaseStatus, { label: string; tone: string }>;

const statusOptions = [undefined, "OPEN", "IN_PROGRESS", "WAITING", "CLOSED"] as const;
const taskLabels = new Map([
  ["CONTACT_CUSTOMER", "고객 연락"], ["VERIFY_HOTEL", "호텔 직접 확인"],
  ["CHECKIN_CONFIRM", "입실 확인"], ["ORIGINAL_FOLLOWUP", "원예약 후속 처리"],
]);
const deadlineFormat = new Intl.DateTimeFormat("ko-KR", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
  timeZone: "Asia/Seoul",
});

type CaseListProps = {
  list: CaseList | undefined;
  status: CaseStatus | undefined;
  error: string;
  onStatusChange: (status: CaseStatus | undefined) => void;
  search: string;
  onSearch: (search: string) => void;
  onPageChange: (page: number) => void;
  onOpen: (caseId: string) => void;
  onRetry: () => void;
};

function formatDeadline(value: string) {
  const deadline = new Date(value);
  return Number.isNaN(deadline.getTime())
    ? value
    : deadlineFormat.format(deadline);
}

export function CaseListView({
  list,
  status,
  error,
  onStatusChange,
  search,
  onSearch,
  onPageChange,
  onOpen,
  onRetry,
}: CaseListProps) {
  const pagination = list?.pagination;
  const pageCount = pagination
    ? Math.ceil(pagination.totalElements / pagination.size)
    : 0;

  return (
    <section className="space-y-4">
      <form
        role="search"
        className="flex flex-wrap gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          onSearch(String(new FormData(event.currentTarget).get("q") ?? ""));
        }}
      >
        <input
          name="q"
          defaultValue={search}
          maxLength={40}
          aria-label="대응 건 검색"
          placeholder="대응 건·예약번호·숙소명·고객명"
          className="h-10 min-w-64 flex-1 rounded-lg border bg-background px-3 text-sm"
        />
        <Button type="submit" variant="outline" size="lg">검색</Button>
      </form>
      <div
        role="group"
        aria-label="처리 상태 필터"
        className="flex flex-wrap gap-2"
      >
        {statusOptions.map((option) => (
          <Button
            key={option ?? "all"}
            type="button"
            variant={status === option ? "default" : "outline"}
            size="lg"
            aria-pressed={status === option}
            onClick={() => onStatusChange(option)}
          >
            {option ? caseStatuses[option].label : "전체"}
          </Button>
        ))}
      </div>

      <div
        role="region"
        aria-label="상담 대응 목록"
        tabIndex={0}
        className="overflow-x-auto rounded-2xl border bg-card focus-visible:outline-2 focus-visible:outline-ring"
      >
        <table className="w-full min-w-[780px] border-collapse text-left text-sm">
          <thead className="border-b bg-muted text-xs text-muted-foreground">
            <tr>
              <th scope="col" className="w-20 whitespace-nowrap px-4 py-4">
                긴급도
              </th>
              <th scope="col" className="w-32 whitespace-nowrap px-4 py-4">
                대응 건·예약
              </th>
              <th scope="col" className="min-w-52 px-4 py-4">
                확인된 문제
              </th>
              <th scope="col" className="w-28 whitespace-nowrap px-4 py-4">
                처리 상태
              </th>
              <th scope="col" className="w-48 whitespace-nowrap px-4 py-4">
                다음 업무·기한
              </th>
              <th scope="col" className="w-28 whitespace-nowrap px-4 py-4 text-right">
                열기
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {(!list || list.items.length === 0) && (
              <tr>
                <td colSpan={6} className="px-4 py-12 lg:text-center">
                  <p
                    role={error ? "alert" : "status"}
                    className={error ? "text-destructive" : "text-muted-foreground"}
                  >
                    {error || (list
                      ? (search ? "검색 결과가 없습니다" : "현재 처리할 상담 대응 건이 없습니다")
                      : "대응 건 조회 중…")}
                  </p>
                  {error && (
                    <Button
                      type="button"
                      variant="outline"
                      className="mt-4"
                      onClick={onRetry}
                    >
                      다시 시도
                    </Button>
                  )}
                  {status && pagination?.totalElements === 0 && (
                    <Button
                      type="button"
                      variant="outline"
                      className="mt-4"
                      onClick={() => onStatusChange(undefined)}
                    >
                      전체 보기
                    </Button>
                  )}
                </td>
              </tr>
            )}
            {list?.items.map((item) => {
              const urgent = item.priority === "URGENT";
              const statusBadge = caseStatuses[item.status];
              const task = item.nextAction;

              return (
                <tr
                  key={item.caseId}
                  className="cursor-pointer hover:bg-muted/50"
                  onClick={() => onOpen(item.caseId)}
                >
                  <td className="px-4 py-4">
                    <span className={`inline-flex whitespace-nowrap rounded-md px-2.5 py-1 text-xs font-bold ${
                      urgent ? "bg-destructive/10 text-destructive" : "bg-secondary text-secondary-foreground"
                    }`}>
                      {urgent ? "긴급" : "일반"}
                    </span>
                  </td>
                  <th scope="row" className="px-4 py-4 font-normal tabular-nums">
                    <p className="whitespace-nowrap text-base font-bold">{item.caseId}</p>
                    <p className="mt-1 whitespace-nowrap text-xs text-muted-foreground">
                      {item.bookingId}
                    </p>
                  </th>
                  <td className="px-4 py-4">
                    <p className="font-medium">{item.summary}</p>
                    <p className="mt-1 text-xs text-muted-foreground">{item.hotelName}</p>
                  </td>
                  <td className="px-4 py-4">
                    <span className={`inline-flex whitespace-nowrap rounded-md px-2.5 py-1 text-xs font-medium ${statusBadge.tone}`}>
                      {statusBadge.label}
                    </span>
                  </td>
                  <td className="px-4 py-4">
                    {task ? (
                      <>
                        <p className="font-medium">{taskLabels.get(task.type) ?? task.type}</p>
                        {task.dueAt ? (
                          <time
                            dateTime={task.dueAt}
                            className="mt-1 block whitespace-nowrap text-xs text-muted-foreground tabular-nums"
                          >
                            {formatDeadline(task.dueAt)}
                          </time>
                        ) : (
                          <p className="mt-1 text-xs text-muted-foreground">기한 미정</p>
                        )}
                      </>
                    ) : (
                      <p className="whitespace-nowrap text-xs text-muted-foreground">미완료 업무 없음</p>
                    )}
                  </td>
                  <td className="px-4 py-4 text-right">
                    <Button
                      type="button"
                      aria-label={`${item.caseId} 대응 건 열기`}
                      onClick={(event) => {
                        event.stopPropagation();
                        onOpen(item.caseId);
                      }}
                    >
                      열기
                    </Button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {pagination && pageCount > 1 && (
        <nav
          aria-label="상담 대응 목록 페이지"
          className="flex flex-wrap items-center justify-end gap-3 text-sm"
        >
          <Button
            type="button"
            variant="outline"
            disabled={pagination.page === 0}
            onClick={() => onPageChange(pagination.page - 1)}
          >
            이전
          </Button>
          <span className="text-muted-foreground tabular-nums">
            {pagination.page + 1} / {pageCount} 페이지
          </span>
          <Button
            type="button"
            variant="outline"
            disabled={!pagination.hasNext}
            onClick={() => onPageChange(pagination.page + 1)}
          >
            다음
          </Button>
        </nav>
      )}
    </section>
  );
}
