import type { CaseDetail } from "../_types/case";

const caseStatuses = new Map([
  ["OPEN", "접수"], ["IN_PROGRESS", "처리 중"], ["WAITING", "확인 대기"], ["CLOSED", "종료"],
]);
const supplierStatuses = new Map([
  ["CONFIRMED", "예약 확정"], ["CANCELLED", "예약 취소"], ["CHANGED", "예약 변경"], ["UNKNOWN", "예약 상태 미확인"],
]);
const roomStatuses = new Map([
  ["AVAILABLE", "객실 제공 가능"], ["UNAVAILABLE", "객실 제공 불가"], ["UNKNOWN", "객실 제공 여부 미확인"],
]);
const evidenceSources = new Map([
  ["HOTEL_API", "호텔 회신"], ["HOTEL_EVENT", "호텔 통보"], ["MANUAL_HOTEL", "상담사 호텔 확인 기록"],
  ["SUPPLIER_API", "공급사 조회 응답"], ["SUPPLIER_EVENT", "공급사 통보"],
]);
const reviewStatuses = new Map([
  ["ACCEPTED", "근거 채택"], ["PENDING_REVIEW", "검토 대기"], ["REJECTED", "근거 제외"],
]);
export const taskNames = new Map([
  ["CONTACT_CUSTOMER", "고객에게 확인된 상황 안내"], ["VERIFY_HOTEL", "호텔 직접 확인"],
  ["CHECKIN_CONFIRM", "대체 숙소 입실 확인"], ["ORIGINAL_FOLLOWUP", "원예약 후속 처리"],
]);
const taskStatuses = new Map([["TODO", "할 일"], ["IN_PROGRESS", "진행 중"], ["DONE", "완료"]]);
const reportNames = new Map([["CHECKIN_FAILED", "체크인 실패 신고"]]);
const dayFormat = new Intl.DateTimeFormat("ko-KR", {
  month: "long", day: "numeric", weekday: "short", timeZone: "Asia/Seoul",
});
const dateTimeFormat = new Intl.DateTimeFormat("ko-KR", {
  month: "long", day: "numeric", hour: "2-digit", minute: "2-digit", hourCycle: "h23", timeZone: "Asia/Seoul",
});
const card = "min-w-0 rounded-xl border bg-card px-4 py-3.5";

function formatDay(value: string) {
  return dayFormat.format(new Date(`${value}T00:00:00+09:00`));
}

function DateTime({ value }: { value: string }) {
  return <time dateTime={value} className="tabular-nums">{dateTimeFormat.format(new Date(value))}</time>;
}

function problemSummary(detail: CaseDetail) {
  if (detail.customerReports.some((report) => report.issueType === "CHECKIN_FAILED")) {
    return "고객이 체크인하지 못해 상담을 요청했습니다.";
  }
  if (detail.nights.some((night) => night.factStatus === "VERIFIED" && night.fulfillmentStatus === "UNAVAILABLE")) {
    return "예약 기간에 객실을 제공할 수 없는 날짜가 확인됐습니다.";
  }
  if (detail.nights.some((night) => night.factStatus === "CONFLICT")) {
    return "객실 제공 여부에 대한 근거가 엇갈립니다.";
  }
  return "숙박일별 객실 제공 여부를 확인하고 있습니다.";
}

export function CaseDetails({ detail }: { detail: CaseDetail }) {
  const { booking, customerReports, evidence, nextAction, nights } = detail;
  return (
    <article className="min-w-0 space-y-3">
      <section className={card} aria-label="예약과 사건 요약">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
          <p>예약번호 <strong className="tabular-nums">{booking.bookingId}</strong></p>
          <span className="rounded-md bg-accent px-2 py-0.5 font-medium text-accent-foreground">
            {caseStatuses.get(detail.status) ?? "사건 상태 확인 필요"}
          </span>
        </div>
        <p className="mt-2 text-sm font-semibold">{problemSummary(detail)}</p>
        <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
          {booking.checkInDate && booking.checkOutDate && (
            <span>숙박 {formatDay(booking.checkInDate)} ~ {formatDay(booking.checkOutDate)}</span>
          )}
          <span>공급사 · {supplierStatuses.get(booking.supplierStatus) ?? "예약 상태 미확인"}</span>
        </div>
      </section>

      <section className={card}>
        <h3 className="text-sm font-semibold">다음 업무</h3>
        {nextAction ? (
          <div className="mt-1 flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 text-sm">
            <p>{taskNames.get(nextAction.type) ?? nextAction.type}</p>
            <p className="text-xs text-muted-foreground">
              {taskStatuses.get(nextAction.status) ?? "상태 확인 필요"} ·{" "}
              {nextAction.dueAt ? <>기한 <DateTime value={nextAction.dueAt} /></> : "기한 미정"}
            </p>
          </div>
        ) : <p className="mt-1 text-sm text-muted-foreground">미완료 업무 없음</p>}
      </section>

      <section className={card}>
        <h3 className="text-sm font-semibold">숙박일별 객실 제공 여부</h3>
        {nights.length ? (
          <ul className="mt-2 grid gap-2 sm:grid-cols-3">
            {nights.map((night) => {
              const verified = night.factStatus === "VERIFIED" && roomStatuses.has(night.fulfillmentStatus)
                && night.fulfillmentStatus !== "UNKNOWN";
              const unavailable = verified && night.fulfillmentStatus === "UNAVAILABLE";
              return (
                <li key={night.stayDate} className={`rounded-lg px-2.5 py-2 text-sm ${unavailable ? "bg-destructive/10" : "bg-muted"}`}>
                  <time dateTime={night.stayDate} className="block text-xs font-medium">{formatDay(night.stayDate)}</time>
                  <span className={`mt-1 block font-medium ${unavailable ? "text-destructive" : "text-foreground"}`}>
                    {night.factStatus === "CONFLICT" ? "근거 상충 · 확인 필요" : verified
                      ? roomStatuses.get(night.fulfillmentStatus) : roomStatuses.get("UNKNOWN")}
                  </span>
                  {verified && <span className="text-xs text-muted-foreground">검증 완료</span>}
                </li>
              );
            })}
          </ul>
        ) : <p className="mt-1 text-sm text-muted-foreground">등록된 숙박일 없음</p>}
      </section>

      <section className={card}>
        <h3 className="text-sm font-semibold">고객 신고</h3>
        {customerReports.length ? (
          <ul className="mt-1 space-y-2 text-sm">
            {customerReports.map((report) => (
              <li key={report.reportId} className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
                <span>{reportNames.get(report.issueType) ?? "고객 신고 접수"}{report.atHotel && " · 호텔 현장"}</span>
                <span className="text-xs text-muted-foreground">접수 <DateTime value={report.receivedAt} /></span>
              </li>
            ))}
          </ul>
        ) : <p className="mt-1 text-sm text-muted-foreground">접수된 신고 없음</p>}
      </section>

      <section className={card}>
        <h3 className="text-sm font-semibold">확인 근거</h3>
        {evidence.length ? (
          <ul className="mt-2 divide-y">
            {evidence.map((record) => (
              <li key={record.evidenceId} className="space-y-2 py-2 first:pt-0 last:pb-0">
                <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
                  <p className="text-sm font-medium">
                    {evidenceSources.get(record.sourceType) ?? "확인 기록"}
                    <span className="ml-2 text-xs font-normal text-muted-foreground">
                      {record.sourceAuthenticated ? "출처 인증됨" : "출처 미인증"}
                    </span>
                  </p>
                  {record.receivedAt && <p className="text-xs text-muted-foreground">수신 <DateTime value={record.receivedAt} /></p>}
                </div>
                {record.assertions.length > 0 && (
                  <ul className="space-y-1 text-xs">
                    {record.assertions.map((assertion) => (
                      <li key={assertion.stayDate}>
                        {formatDay(assertion.stayDate)} · {roomStatuses.get(assertion.reportedProvisionStatus) ?? "회신 내용 확인 필요"}
                        <span className="ml-2 text-muted-foreground">{reviewStatuses.get(assertion.reviewStatus) ?? "검토 상태 확인 필요"}</span>
                      </li>
                    ))}
                  </ul>
                )}
                {record.originalContent && (
                  <details className="text-sm">
                    <summary className="cursor-pointer font-medium text-accent-foreground">응답 원문</summary>
                    <pre className="mt-2 rounded-lg bg-muted p-3 font-sans text-xs leading-relaxed whitespace-pre-wrap wrap-anywhere">{record.originalContent}</pre>
                  </details>
                )}
              </li>
            ))}
          </ul>
        ) : <p className="mt-1 text-sm text-muted-foreground">수신된 호텔·공급사 근거 없음</p>}
      </section>
    </article>
  );
}
