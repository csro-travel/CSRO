import { AlternativeSearchPanel } from '@/components/console/alternative-search-panel'

export default function Page() {
  return (
    <main className="mx-auto max-w-6xl px-5 py-8 md:px-9 md:py-10">
      <h1 className="text-2xl font-bold tracking-tight md:text-3xl">대체 숙소 탐색 시연</h1>
      <p className="mt-2 text-sm text-muted-foreground">
        예약 조회 권한과 외부 검색 서비스 연결이 필요합니다. 검색 결과는 상담사 검토용이며,
        고객 제안 발송과 예약은 실행하지 않습니다.
      </p>

      <div className="mt-7">
        <AlternativeSearchPanel bookingId="YB-001" />
      </div>
    </main>
  )
}
