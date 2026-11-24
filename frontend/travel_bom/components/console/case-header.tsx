import { Loader2, Play } from 'lucide-react'
import { formatWon, type fetchBookingCase } from '@/lib/alternative-stays'

type CaseHeaderProps = {
  fetchBookingCase: Awaited<ReturnType<typeof fetchBookingCase>>
  searching: boolean
  onSearch: () => void
}

export function CaseHeader({ fetchBookingCase, searching, onSearch }: CaseHeaderProps) {
  return (
    <section
      aria-label="예약 케이스 정보"
      className="flex flex-col gap-5 rounded-2xl border bg-card p-6 md:flex-row md:items-center md:justify-between"
    >
      <div className="min-w-0">
        <h2 className="flex flex-wrap items-center gap-x-3 gap-y-1 text-lg font-bold">
          <span>{fetchBookingCase.caseId}</span>
          <span aria-hidden="true" className="h-4 w-0.5 bg-foreground" />
          <span>예약번호 {fetchBookingCase.reservationNo}</span>
        </h2>
        <dl className="mt-3 flex flex-wrap gap-x-7 gap-y-1 text-sm text-muted-foreground">
          <div>
            <dt className="sr-only">숙소</dt>
            <dd>{fetchBookingCase.hotel}</dd>
          </div>
          <div>
            <dt className="sr-only">일정</dt>
            <dd>
              {fetchBookingCase.period} · {fetchBookingCase.nights}박
            </dd>
          </div>
          <div>
            <dt className="sr-only">인원</dt>
            <dd>{fetchBookingCase.guests}</dd>
          </div>
          <div>
            <dt className="sr-only">요금</dt>
            <dd>1박 {formatWon(fetchBookingCase.nightlyPrice)}</dd>
          </div>
        </dl>
      </div>

      <button
        type="button"
        onClick={onSearch}
        disabled={searching}
        className="inline-flex h-14 shrink-0 items-center justify-center gap-3 rounded-xl bg-primary px-12 text-base font-bold text-primary-foreground shadow-sm transition-opacity hover:opacity-90 focus-visible:ring-3 focus-visible:ring-ring/40 focus-visible:outline-none disabled:opacity-70"
      >
        {searching ? (
          <Loader2 className="size-5 animate-spin" aria-hidden="true" />
        ) : (
          <Play className="size-5 fill-current" aria-hidden="true" />
        )}
        {searching ? '탐색 중...' : '대체 숙소 자동 탐색'}
      </button>
    </section>
  )
}
