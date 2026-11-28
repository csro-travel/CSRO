import { CaseSection } from "./case-section";
import { Building2, Headset, Hotel, Info } from "lucide-react";
import { Badge, type BadgeTone } from "@/components/ui/badge";
import { evidenceSourceLabels, formatDateTime, formatDay, provisionLabels, reviewLabels, supplierStatusLabels } from "@/lib/case-labels";
import type { CaseDetail, EvidenceRecord } from "../_types/case";

type Booking = CaseDetail["booking"];
const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === "object" && value !== null && !Array.isArray(value);
const text = (value: unknown) => (typeof value === "string" && value.trim() ? value.trim() : null);

function parse(content: string | null): unknown {
  if (!content) return null;
  try { return JSON.parse(content); } catch { return null; }
}

/** Supplier lookup in one line: contract state, stay, party, and whether it matches our booking. */
function supplierSentence(payload: unknown, booking: Booking) {
  if (!isRecord(payload)) return null;
  const stay = isRecord(payload.stay) ? payload.stay : {};
  const status = text(payload.contractStatus);
  const checkIn = text(stay.checkInDate), checkOut = text(stay.checkOutDate);
  const adults = typeof stay.adults === "number" ? stay.adults : null;
  const rooms = typeof stay.rooms === "number" ? stay.rooms : null;
  if (!status) return null;
  const differences = [
    text(payload.referenceBookingId) && payload.referenceBookingId !== booking.bookingId ? "예약번호" : null,
    checkIn && booking.checkInDate && checkIn !== booking.checkInDate ? "체크인 날짜" : null,
    checkOut && booking.checkOutDate && checkOut !== booking.checkOutDate ? "체크아웃 날짜" : null,
    adults !== null && booking.adults !== undefined && adults !== booking.adults ? "인원" : null,
    rooms !== null && booking.rooms !== undefined && rooms !== booking.rooms ? "객실 수" : null,
  ].filter((item): item is string => item !== null);
  return {
    line: [
      `공급사는 이 예약을 "${supplierStatusLabels.get(status) ?? status}"으로 답했습니다`,
      checkIn && checkOut ? `${formatDay(checkIn)} ~ ${formatDay(checkOut)}` : null,
      adults !== null ? `성인 ${adults}명${rooms !== null ? ` · 객실 ${rooms}개` : ""}` : null,
    ].filter(Boolean).join(" · "),
    differences,
  };
}

/** What the hotel actually said for each night, when the reply carries a message. */
function hotelQuotes(payload: unknown) {
  const quotes = new Map<string, string>();
  if (isRecord(payload) && Array.isArray(payload.nights)) {
    for (const night of payload.nights) {
      if (isRecord(night) && text(night.stayDate) && text(night.message)) quotes.set(night.stayDate as string, night.message as string);
    }
  }
  return quotes;
}

const reviewTones: Record<string, BadgeTone> = { ACCEPTED: "success", PENDING_REVIEW: "outline", REJECTED: "neutral" };
const provisionTones: Record<string, string> = {
  UNAVAILABLE: "font-semibold text-destructive", AVAILABLE: "font-semibold text-[oklch(0.38_0.1_158)]", UNKNOWN: "text-muted-foreground",
};

function Record_({ record, booking }: { record: EvidenceRecord; booking: Booking }) {
  const payload = parse(record.originalContent);
  const supplier = record.sourceType.startsWith("SUPPLIER") ? supplierSentence(payload, booking) : null;
  const quotes = hotelQuotes(payload);
  // A manual record keeps the counselor's own call note as plain text.
  const note = payload === null && record.originalContent ? record.originalContent.trim() : isRecord(payload) ? text(payload.callNote) : null;
  const Icon = record.sourceType.startsWith("SUPPLIER") ? Building2 : record.sourceType === "MANUAL_HOTEL" ? Headset : Hotel;
  const raw = payload === null ? record.originalContent : JSON.stringify(payload, null, 2);

  return (
    <li className="space-y-2 py-3 first:pt-0 last:pb-0" data-evidence-id={record.evidenceId}>
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <p className="flex items-center gap-1.5 text-sm font-semibold">
          <Icon className="size-4 text-muted-foreground" aria-hidden="true" />
          {evidenceSourceLabels.get(record.sourceType) ?? "확인 기록"}
          {record.sourceAuthenticated && <Badge tone="success">출처 인증됨</Badge>}
        </p>
        {record.receivedAt && <time dateTime={record.receivedAt} className="num text-xs text-muted-foreground">{formatDateTime(record.receivedAt)} 수신</time>}
      </div>

      {supplier && (
        <p className="text-sm leading-relaxed">
          {supplier.line}.{" "}
          {supplier.differences.length === 0
            ? <span className="text-muted-foreground">우리 예약 정보와 일치합니다.</span>
            : <span className="font-medium text-destructive">우리 예약 정보와 다릅니다: {supplier.differences.join(", ")}</span>}
        </p>
      )}

      {record.assertions.length > 0 && (
        <ul className="space-y-1.5">
          {record.assertions.map(assertion => (
            <li key={assertion.stayDate} className="grid grid-cols-[5.5rem_minmax(0,1fr)_auto] items-baseline gap-x-2 text-sm">
              <time dateTime={assertion.stayDate} className="num text-xs text-muted-foreground">{formatDay(assertion.stayDate)}</time>
              <span className="min-w-0">
                <span className={provisionTones[assertion.reportedProvisionStatus] ?? ""}>{provisionLabels.get(assertion.reportedProvisionStatus) ?? "내용 확인 필요"}</span>
                {quotes.has(assertion.stayDate) && (
                  <q className="ml-2 text-muted-foreground before:content-['“'] after:content-['”']">{quotes.get(assertion.stayDate)}</q>
                )}
              </span>
              <Badge tone={reviewTones[assertion.reviewStatus] ?? "outline"}>{reviewLabels.get(assertion.reviewStatus) ?? "검토 상태 확인 필요"}</Badge>
            </li>
          ))}
        </ul>
      )}

      {note && <blockquote className="border-l-2 pl-3 text-sm leading-relaxed text-muted-foreground">{note}</blockquote>}
      {!supplier && record.assertions.length === 0 && !note && <p className="text-sm text-muted-foreground">문장으로 풀 수 있는 내용이 없습니다. 원본 데이터를 확인하세요.</p>}

      {raw && (
        <details className="group text-xs">
          <summary className="cursor-pointer text-muted-foreground select-none hover:text-foreground">원본 데이터(감사용)</summary>
          <pre className="num mt-2 max-h-56 overflow-auto rounded-md bg-muted p-3 leading-relaxed whitespace-pre-wrap wrap-anywhere">{raw}</pre>
        </details>
      )}
    </li>
  );
}

/** Evidence as sentences first; the stored payload stays available, folded, for audit. */
export function CaseEvidenceList({ detail }: { detail: CaseDetail }) {
  const { evidence, booking } = detail;
  const unauthenticated = evidence.some(record => !record.sourceAuthenticated && record.sourceType !== "MANUAL_HOTEL");
  return (
    <CaseSection title="검증 근거">
        {unauthenticated && (
          <p className="flex items-center gap-1 text-xs text-muted-foreground">
            <Info className="size-3.5" aria-hidden="true" />
            가상 공급사·호텔 응답(시연용) · 출처 인증 절차 없음
          </p>
        )}
      {evidence.length
        ? <ul className="mt-3 divide-y">{evidence.map(record => <Record_ key={record.evidenceId} record={record} booking={booking} />)}</ul>
        : <p className="mt-2 text-sm text-muted-foreground">아직 받은 호텔·공급사 근거가 없습니다.</p>}
    </CaseSection>
  );
}
