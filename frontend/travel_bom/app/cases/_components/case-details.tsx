import { CalendarDays, Users, Wallet } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { NightStrip } from "@/components/console/night-strip";
import { formatDateTime, formatDay, supplierStatusLabels, taskLabels } from "@/lib/case-labels";
import type { CaseDetail } from "../_types/case";

/** Kept for the closure and chat panels that name tasks the same way. */
export const taskNames = taskLabels;

const reportNames = new Map([["CHECKIN_FAILED", "체크인 실패 신고"]]);

export function problemSummary(detail: CaseDetail) {
  const unavailable = detail.nights.filter(night => night.factStatus === "VERIFIED" && night.fulfillmentStatus === "UNAVAILABLE");
  if (unavailable.length) return `${unavailable.map(night => formatDay(night.stayDate)).join(", ")} 객실을 제공할 수 없다는 사실이 확인됐습니다.`;
  if (detail.nights.some(night => night.factStatus === "CONFLICT")) return "객실 제공 여부에 대한 근거가 서로 엇갈립니다.";
  if (detail.customerReports.length) return "고객이 체크인 문제를 신고했고, 호텔 확인은 아직 끝나지 않았습니다.";
  return "숙박일별 객실 제공 여부를 확인하고 있습니다.";
}

/** Booking facts and the night-by-night state: the first thing a counselor reads. */
export function CaseDetails({ detail }: { detail: CaseDetail }) {
  const { booking, customerReports, nights } = detail;
  const unknown = nights.filter(night => night.factStatus === "UNCONFIRMED").length;
  return (
    <section className="panel space-y-3 p-4" aria-label="예약과 대응 건 요약">
      <p className="text-base leading-snug font-semibold">{problemSummary(detail)}</p>
      <NightStrip nights={nights} />
      {unknown > 0 && <p className="text-xs text-muted-foreground">빗금 친 {unknown}박은 아직 확인되지 않았습니다. 정상이라는 뜻이 아닙니다.</p>}
      <dl className="flex flex-wrap gap-x-5 gap-y-1.5 border-t pt-3 text-sm">
        <div className="flex items-center gap-1.5">
          <dt className="text-muted-foreground">예약</dt>
          <dd className="num font-medium">{booking.bookingId}</dd>
          <Badge tone={booking.supplierStatus === "CONFIRMED" ? "outline" : "warning"}>
            공급사 {supplierStatusLabels.get(booking.supplierStatus) ?? "상태 미확인"}
          </Badge>
        </div>
        {booking.checkInDate && booking.checkOutDate && (
          <div className="flex items-center gap-1.5">
            <dt><CalendarDays className="size-3.5 text-muted-foreground" aria-hidden="true" /><span className="sr-only">숙박 기간</span></dt>
            <dd className="num">{formatDay(booking.checkInDate)} ~ {formatDay(booking.checkOutDate)}</dd>
          </div>
        )}
        {booking.adults !== undefined && (
          <div className="flex items-center gap-1.5">
            <dt><Users className="size-3.5 text-muted-foreground" aria-hidden="true" /><span className="sr-only">인원</span></dt>
            <dd>성인 {booking.adults}명{booking.rooms !== undefined && ` · 객실 ${booking.rooms}개`}</dd>
          </div>
        )}
        {booking.originalPrice && (
          <div className="flex items-center gap-1.5">
            <dt><Wallet className="size-3.5 text-muted-foreground" aria-hidden="true" /><span className="sr-only">원예약 총액</span></dt>
            <dd className="num">{booking.originalPrice.amount.toLocaleString("ko-KR")}원</dd>
          </div>
        )}
      </dl>
      {customerReports.length > 0 && (
        <ul className="space-y-1 border-t pt-3 text-sm">
          {customerReports.map(report => (
            <li key={report.reportId} className="flex flex-wrap items-baseline justify-between gap-x-3">
              <span>
                {reportNames.get(report.issueType) ?? "고객 신고"}
                {report.atHotel && <Badge tone="danger" className="ml-2">고객이 호텔 현장에 있음</Badge>}
              </span>
              <time dateTime={report.receivedAt} className="num text-xs text-muted-foreground">접수 {formatDateTime(report.receivedAt)}</time>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
