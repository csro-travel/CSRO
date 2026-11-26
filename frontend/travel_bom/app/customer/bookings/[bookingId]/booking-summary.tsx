"use client";

import { useEffect, useState } from "react";

type Booking = {
  bookingId: string;
  hotel: { name: string };
  stay: { checkInDate: string; checkOutDate: string; nights: number; adults: number };
  bookingStatus: string;
  originalPrice: { amount: number; currency: string };
  support: { activeCaseId: string | null; needsAttention: boolean };
};

const bookingStatuses = new Map([["CONFIRMED", "예약 확정"], ["CANCELLED", "예약 취소"], ["CHANGED", "예약 변경"], ["UNKNOWN", "예약 상태 확인 중"]]);

/** API-001: the owner customer's booking and the link to its active consultation, if any. */
export function BookingSummary({ bookingId }: { bookingId: string }) {
  const [booking, setBooking] = useState<Booking>();
  const [error, setError] = useState("");

  useEffect(() => {
    const request = new AbortController();
    setBooking(undefined); setError("");
    fetch(`/demo/bookings/${encodeURIComponent(bookingId)}`, { headers: { "X-Demo-Read": "1" }, cache: "no-store", signal: request.signal })
      .then(async response => {
        if (!response.ok) throw Error(response.status === 404 ? "예약을 찾을 수 없습니다." : `예약 조회 실패 (HTTP ${response.status})`);
        return response.json() as Promise<Booking>;
      })
      .then(value => { if (!request.signal.aborted) setBooking(value); })
      .catch(cause => { if (!request.signal.aborted) setError(cause instanceof Error ? cause.message : "예약 조회 실패"); });
    return () => request.abort();
  }, [bookingId]);

  if (error) return <p role="alert" className="text-sm text-destructive">{error}</p>;
  if (!booking) return <p role="status" className="text-sm text-muted-foreground">예약 조회 중…</p>;
  return (
    <section aria-label="내 예약" className="space-y-1 rounded-xl border bg-card px-4 py-3.5 text-sm">
      <p className="font-semibold">{booking.hotel.name}</p>
      <p>{booking.stay.checkInDate} ~ {booking.stay.checkOutDate} · {booking.stay.nights}박 · 성인 {booking.stay.adults}명</p>
      <p>{bookingStatuses.get(booking.bookingStatus) ?? booking.bookingStatus} · 결제 금액 {booking.originalPrice.amount.toLocaleString("ko-KR")}원</p>
      {booking.support.activeCaseId && (
        <p className="pt-1">진행 중인 상담이 있습니다. <a href={`/customer/cases/${encodeURIComponent(booking.support.activeCaseId)}`} className="font-medium underline">상담 내용 확인 ({booking.support.activeCaseId})</a></p>
      )}
    </section>
  );
}
