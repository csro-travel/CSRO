import { CalendarDays, Users, Wallet } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { NightStrip } from "@/components/console/night-strip";
import { formatDateTime, formatDay, nightTone, supplierStatusLabels, taskLabels, type NightTone } from "@/lib/case-labels";
import type { CaseDetail } from "../_types/case";

/** Kept for the closure and chat panels that name tasks the same way. */
export const taskNames = taskLabels;

const reportNames = new Map([["CHECKIN_FAILED", "체크인 실패 신고"]]);

const verdicts: Record<NightTone, { label: string; unit: string; style: string }> = {
  unavailable: { label: "문제 확인됨", unit: "불가", style: "border-destructive/40 bg-destructive/10 text-destructive" },
  conflict: { label: "근거 상충", unit: "상충", style: "border-warning/50 bg-warning/15 text-[oklch(0.42_0.1_70)]" },
  // Hatched like an unconfirmed night: not yet a fact, and never to be read as "fine".
  unknown: { label: "확인 중", unit: "미확인", style: "border-dashed border-input bg-[repeating-linear-gradient(135deg,transparent_0_5px,var(--muted)_5px_10px)] text-foreground" },
  available: { label: "문제 없음", unit: "제공 가능", style: "border-success/40 bg-success/12 text-[oklch(0.38_0.1_158)]" },
};
const severity: NightTone[] = ["unavailable", "conflict", "unknown", "available"];

/** The one-line answer to "is this really a problem", read from the stored night states. The worst state leads. */
export function caseVerdict(nights: CaseDetail["nights"]) {
  const count = (tone: NightTone) => nights.filter(night => nightTone(night) === tone).length;
  const lead = severity.find(tone => count(tone) > 0) ?? "unknown";
  const rest = lead === "available"
    ? [`${nights.length}박 모두 제공 가능`]
    : severity.filter(tone => tone !== "available" && count(tone) > 0).map(tone => `${count(tone)}박 ${verdicts[tone].unit}`);
  return { tone: lead, ...verdicts[lead], detail: rest.join(", ") || "숙박일 정보 없음" };
}

/** Booking facts and the night-by-night state: the first thing a counselor reads. */
export function CaseDetails({ detail }: { detail: CaseDetail }) {
  const { booking, customerReports, nights } = detail;
  const unknown = nights.filter(night => night.factStatus === "UNCONFIRMED").length;
  const verdict = caseVerdict(nights);
  const atHotel = detail.status !== "CLOSED" && customerReports.some(report => report.atHotel);
  return (
    <section className="panel space-y-3 p-4" aria-label="예약과 대응 건 요약">
      <p data-verdict={verdict.tone} className={`flex flex-wrap items-center gap-x-2.5 gap-y-1 rounded-md border px-3.5 py-2.5 ${verdict.style}`}>
        <strong className="text-xl leading-tight font-bold">{verdict.label}</strong>
        <span className="num text-base font-semibold">· {verdict.detail}</span>
        {atHotel && <Badge tone="danger" className="ml-auto bg-destructive px-2.5 py-1 text-sm text-white">긴급 · 고객 호텔 현장</Badge>}
      </p>
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
              <span>{reportNames.get(report.issueType) ?? "고객 신고"}</span>
              <time dateTime={report.receivedAt} className="num text-xs text-muted-foreground">접수 {formatDateTime(report.receivedAt)}</time>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
