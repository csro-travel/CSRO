"use client";

import { useMemo } from "react";
import { AlternativeSearchPanel } from "@/components/console/alternative-search-panel";
import type { AlternativeStay, BookingCase } from "@/lib/alternative-stays";
import type { CaseDetail } from "../_types/case";

const DAY_MS = 86_400_000;

export function CaseAirbnbPanel({ detail, closed, onSelectionChange }: {
  detail: CaseDetail;
  closed: boolean;
  onSelectionChange: (stays: AlternativeStay[], sent: boolean) => void;
}) {
  const { bookingId, hotel, supplierStatus, checkInDate, checkOutDate, adults, originalPrice } = detail.booking;
  const hotelName = hotel.name;
  const { caseId } = detail;
  const currency = originalPrice?.currency;
  const amount = originalPrice?.amount;
  const booking = useMemo<BookingCase | null>(() => {
    if (!checkInDate || !checkOutDate || !adults || !Number.isSafeInteger(adults) || adults < 1 ||
        currency !== "KRW" || amount === undefined || !Number.isSafeInteger(amount) || amount < 0) return null;
    const nights = (Date.parse(checkOutDate) - Date.parse(checkInDate)) / DAY_MS;
    if (!Number.isSafeInteger(nights) || nights < 1) return null;
    return {
      caseId, reservationNo: bookingId, hotel: hotelName, bookingStatus: supplierStatus,
      checkInDate, checkOutDate, nights, adults, guests: "성인 " + adults + "명",
      period: checkInDate + " ~ " + checkOutDate, nightlyPrice: Math.round(amount / nights),
    };
  }, [caseId, bookingId, hotelName, supplierStatus, checkInDate, checkOutDate, adults, currency, amount]);

  return (
    <section aria-label="Airbnb 숙소 검색" className="panel space-y-4 p-4">
      <div>
        <h3 className="text-sm font-semibold">Airbnb 숙소 검색</h3>
        <p className="text-sm text-muted-foreground">원예약 전체 기간의 상담사 검토용 후보입니다. 고객 제안에는 아래 견적 목록을 사용합니다.</p>
      </div>
      {closed && <p className="text-sm text-muted-foreground">종료된 대응 건은 검색 이력만 조회할 수 있습니다.</p>}
      {booking ? <AlternativeSearchPanel caseId={caseId} bookingId={bookingId} caseBooking={booking}
        closed={closed} onSelectionChange={onSelectionChange} />
        : <p role="status">원예약 기간, 인원 또는 원화 금액을 확인할 수 없어 검색할 수 없습니다.</p>}
    </section>
  );
}
