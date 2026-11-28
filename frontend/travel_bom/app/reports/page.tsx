"use client";

import { ConsolePage, useConsoleData } from "@/components/console/use-console-data";
import { getOverview, getRecheckJobs } from "@/lib/console-api";

const percent = (part: number, whole: number) => (whole > 0 ? `${Math.round((part / whole) * 100)}%` : "–");

function Row({ label, value, note }: { label: string; value: string | number; note: string }) {
  return (
    <tr className="align-top">
      <th scope="row" className="w-64 px-4 py-2.5 text-left font-medium">{label}</th>
      <td className="num w-28 px-4 py-2.5 text-right text-base font-bold">{value}</td>
      <td className="px-4 py-2.5 text-sm text-muted-foreground">{note}</td>
    </tr>
  );
}

/** Counts that can be checked against stored records. No measured time savings are claimed. */
export default function Reports() {
  const state = useConsoleData(async signal => {
    const [overview, jobs] = await Promise.all([getOverview(signal), getRecheckJobs(signal)]);
    return { overview, jobs };
  });
  const overview = state.data?.overview;
  const jobs = state.data?.jobs ?? [];
  const scheduled = jobs.filter(job => job.trigger === "SCHEDULED");
  const reported = jobs.filter(job => job.trigger === "CUSTOMER_REPORT");
  const cases = overview ? overview.cases.open + overview.cases.inProgress + overview.cases.waiting + overview.cases.closed : 0;

  return (
    <ConsolePage title="운영 리포트" state={state}
      description="자동 확인이 상담사에게 넘어오는 일을 얼마나 줄였는지 저장된 기록으로 셉니다. 상담 처리 시간 단축률이나 민원 감소율은 아직 측정하지 않았습니다.">
      {overview && <>
        <section className="panel overflow-x-auto" aria-label="자동 확인">
          <h2 className="border-b px-4 py-2.5 text-sm font-semibold">자동 확인</h2>
          <table className="w-full border-collapse text-sm">
            <tbody className="divide-y">
              <Row label="위험 평가" value={overview.risk.assessments} note={`호텔×체크인 날짜 단위. 이 중 ${overview.risk.selectedHotelDates}건이 재확인 대상으로 선별됐습니다.`} />
              <Row label="자동 재확인한 예약" value={overview.autoChecks.bookingsChecked} note={`사전 위험 점검 ${scheduled.length}회 · 고객 신고 ${reported.length}회 실행`} />
              <Row label="전 숙박일 정상으로 끝난 예약" value={overview.autoChecks.bookingsAllAvailable}
                note={`재확인한 예약의 ${percent(overview.autoChecks.bookingsAllAvailable, overview.autoChecks.bookingsChecked)}. 대응 건을 만들지 않아 상담사가 보지 않았습니다.`} />
              <Row label="제공 불가가 확인된 예약" value={overview.autoChecks.bookingsWithUnavailable} note="검증된 제공 불가 숙박일이 있는 예약" />
              <Row label="자동 확인 실패" value={overview.autoChecks.failed} note="재시도가 소진돼 상담사가 호텔에 직접 확인해야 하는 작업" />
            </tbody>
          </table>
        </section>

        <section className="panel overflow-x-auto" aria-label="상담 대응">
          <h2 className="border-b px-4 py-2.5 text-sm font-semibold">상담 대응</h2>
          <table className="w-full border-collapse text-sm">
            <tbody className="divide-y">
              <Row label="대응 건 합계" value={cases} note={`접수 ${overview.cases.open} · 처리 중 ${overview.cases.inProgress} · 확인 대기 ${overview.cases.waiting} · 종료 ${overview.cases.closed}`} />
              <Row label="사전 점검에서 발견한 대응 건" value={overview.autoChecks.casesOpenedByRecheck} note="고객이 신고하기 전에 재확인으로 문제를 확인해 만든 대응 건" />
              <Row label="대체 숙소 견적 비교" value={overview.analyses.quoteRanking} note="상담사가 후보를 직접 찾지 않고 받은 비교 결과" />
              <Row label="호텔 회신 문장 분석" value={overview.analyses.hotelReply} note="문장 회신에서 날짜별 주장을 추출한 횟수" />
              <Row label="등록된 예약" value={overview.bookings.total} note="업무 DB의 전체 예약" />
            </tbody>
          </table>
        </section>

        <p className="text-xs leading-relaxed text-muted-foreground">
          가상 공급사·호텔과 합성 데이터로 만든 시연 기록입니다. 실제 호텔의 장애율이나 실제 고객센터의 업무 절감 효과를 뜻하지 않습니다.
        </p>
      </>}
    </ConsolePage>
  );
}
