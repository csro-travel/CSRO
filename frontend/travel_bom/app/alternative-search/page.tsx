import { AlternativeSearchPanel } from '@/components/console/alternative-search-panel'

/** Live alternative-stay search demo. It needs the separate search service and is not part of the case flow. */
export default function Page() {
  return (
    <main className="mx-auto max-w-6xl px-5 py-6">
      <h1 className="text-xl font-bold">대체 숙소 탐색 시연</h1>
      <p className="mt-1 text-sm text-muted-foreground">
        예약 조회 권한과 외부 검색 서비스 연결이 필요합니다. 검색 결과는 상담사 검토용이며,
        고객 제안 발송과 예약은 실행하지 않습니다.
      </p>

      <div className="mt-6">
        <AlternativeSearchPanel bookingId="YB-001" />
      </div>
    </main>
  )
}
