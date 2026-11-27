"use client";

import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { formatWon } from "@/lib/alternative-stays";

type Action = { propertyName: string; totalAmount: number; available: boolean; bookingId: string | null };

export function AirbnbDemoBookingCard({ caseId, offerId, closed, onCompose }: {
  caseId: string; offerId: string; closed: boolean; onCompose: (text: string) => void;
}) {
  const path = "/demo/cases/" + encodeURIComponent(caseId) + "/airbnb-demo-searches/simulations/" + offerId;
  const [action, setAction] = useState<Action | null>(null);
  const [bookingId, setBookingId] = useState<string | null>(null);
  const [booking, setBooking] = useState(false);
  const [error, setError] = useState("");
  const [attempt, setAttempt] = useState(0);
  const pending = useRef(false);

  useEffect(() => {
    const request = new AbortController();
    fetch(path, { headers: { "X-Demo-Read": "1" }, cache: "no-store", signal: request.signal })
      .then(async response => {
        if (!response.ok) throw Error();
        const value: Action = await response.json();
        if (!request.signal.aborted) setAction(value);
      })
      .catch(() => { if (!request.signal.aborted) setError("시연 예약 정보를 불러오지 못했습니다."); });
    return () => request.abort();
  }, [path, attempt]);

  const confirmed = bookingId ?? action?.bookingId;
  async function book() {
    if (!action?.available || closed || confirmed || pending.current) return;
    pending.current = true;
    setBooking(true);
    setError("");
    try {
      const response = await fetch(path + "/book", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
      if (!response.ok) throw Error(response.status === 409 ? "현재 제안 또는 견적 상태를 확인해 주세요." : "예약 결과를 확인하지 못했습니다. 다시 시도해 주세요.");
      const result: { bookingId?: string } = await response.json();
      if (!result.bookingId) throw Error("예약 결과를 확인하지 못했습니다. 다시 시도해 주세요.");
      setBookingId(result.bookingId);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "예약 진행에 실패했습니다.");
    } finally {
      pending.current = false;
      setBooking(false);
    }
  }

  if (!action) return error ? (
    <div>
      <p role="alert" className="text-sm text-destructive">{error}</p>
      <Button type="button" size="sm" variant="outline" onClick={() => { setError(""); setAttempt(value => value + 1); }}>다시 조회</Button>
    </div>
  ) : <p role="status" className="text-sm">선택한 숙소를 불러오는 중…</p>;
  return (
    <div className="space-y-2 rounded-xl border bg-card px-3 py-2 text-card-foreground">
      <div className="flex flex-wrap justify-between gap-x-6 gap-y-1 text-sm font-semibold">
        <span className="wrap-anywhere">{action.propertyName}</span>
        <span className="text-primary">{formatWon(action.totalAmount)}</span>
      </div>
      {confirmed ? (
        <div className="flex flex-wrap items-center gap-2">
          <p role="status" className="inline-block rounded-md border bg-card px-3 py-1.5 text-sm font-medium text-primary">
            예약 완료 <span className="sr-only">· {confirmed}</span>
          </p>
          <Button type="button" variant="outline" disabled={closed} onClick={() => onCompose(
            `${action.propertyName} 시연 예약이 완료되었습니다. 예약번호는 ${confirmed}입니다.`,
          )}>
            고객에게 안내
          </Button>
        </div>
      ) : (
        <Button type="button" className="h-11 min-w-32 px-6 text-base font-bold" disabled={closed || !action.available || booking} onClick={() => void book()}>
          {booking ? "예약 처리 중…" : closed || !action.available ? "예약 불가" : "예약 진행"}
        </Button>
      )}
      {error && !confirmed && <p role="alert" className="text-sm text-destructive">{error}</p>}
    </div>
  );
}
