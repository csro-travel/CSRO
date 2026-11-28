"use client";

import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { NightStrip } from "@/components/console/night-strip";
import { ConsolePage, EmptyState, useConsoleData } from "@/components/console/use-console-data";
import { formatDay, supplierStatusLabels } from "@/lib/case-labels";
import { getBookings } from "@/lib/console-api";

/** All bookings with the contract state and the night-by-night state side by side. */
export default function Bookings() {
  const state = useConsoleData(getBookings);
  const items = state.data ?? [];

  return (
    <ConsolePage title="예약 관리" state={state}
      description="공급사의 계약 상태와 숙박일별 객실 제공 여부를 나란히 봅니다. 예약이 확정돼 있어도 날짜별 상태는 따로 확인합니다.">
      {state.data && (items.length === 0 ? <EmptyState>등록된 예약이 없습니다.</EmptyState> : (
        <div className="panel overflow-x-auto">
          <table className="w-full min-w-[980px] border-collapse text-left text-sm">
            <thead className="border-b text-xs text-muted-foreground">
              <tr>
                <th scope="col" className="px-4 py-2.5 font-medium">예약 · 고객</th>
                <th scope="col" className="px-4 py-2.5 font-medium">숙소 · 기간</th>
                <th scope="col" className="px-4 py-2.5 font-medium">공급사 계약</th>
                <th scope="col" className="w-72 px-4 py-2.5 font-medium">숙박일별 상태</th>
                <th scope="col" className="px-4 py-2.5 font-medium">자동 확인</th>
                <th scope="col" className="px-4 py-2.5 font-medium">대응 건</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {items.map(item => (
                <tr key={item.bookingId} className="align-top">
                  <th scope="row" className="px-4 py-3 font-normal">
                    <p className="num font-bold">{item.bookingId}</p>
                    <p className="text-xs text-muted-foreground">{item.customerName}</p>
                  </th>
                  <td className="px-4 py-3">
                    <p>{item.hotelName}</p>
                    <p className="num text-xs text-muted-foreground">
                      {formatDay(item.checkInDate)} ~ {formatDay(item.checkOutDate)} · 성인 {item.adults}명 · {item.originalPrice.amount.toLocaleString("ko-KR")}원
                    </p>
                  </td>
                  <td className="px-4 py-3">
                    <Badge tone={item.supplierStatus === "CONFIRMED" ? "outline" : "warning"}>{supplierStatusLabels.get(item.supplierStatus) ?? item.supplierStatus}</Badge>
                  </td>
                  <td className="px-4 py-3"><NightStrip size="sm" nights={item.nights} /></td>
                  <td className="px-4 py-3 text-xs text-muted-foreground">{item.recheckJobs > 0 ? `${item.recheckJobs}회 실행` : "실행 안 됨"}</td>
                  <td className="px-4 py-3">
                    {item.activeCaseId
                      ? <Link href={`/cases?case=${item.activeCaseId}`} className="num font-medium text-primary underline-offset-4 hover:underline">{item.activeCaseId} 진행 중</Link>
                      : item.latestCaseId
                        ? <Link href={`/cases?case=${item.latestCaseId}`} className="num text-muted-foreground underline-offset-4 hover:underline">{item.latestCaseId} 종료</Link>
                        : <span className="text-xs text-muted-foreground">없음</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
      {state.data && <p className="text-xs text-muted-foreground">모든 예약은 시연용 가상 데이터입니다.</p>}
    </ConsolePage>
  );
}
