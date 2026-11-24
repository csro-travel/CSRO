import { AlternativeSearchPanel } from '@/components/console/alternative-search-panel'

export default async function Page({ params }: {
  params: Promise<{ caseId: string; bookingId: string }>
}) {
  const { caseId, bookingId } = await params
  return (
    <main className="mx-auto max-w-6xl px-5 py-8 md:px-9 md:py-10">
      <h1 className="text-2xl font-bold tracking-tight md:text-3xl">대체 숙소 후보 탐색</h1>
      <p className="mt-2 text-sm text-muted-foreground">
        연결된 사건과 예약을 확인한 뒤 원예약 전체 기간의 후보를 검색합니다.
        검색 결과는 검토용이며 고객 제안 발송이나 예약을 실행하지 않습니다.
      </p>
      <div className="mt-7">
        <AlternativeSearchPanel key={`${caseId}/${bookingId}`} caseId={caseId} bookingId={bookingId} />
      </div>
    </main>
  )
}
