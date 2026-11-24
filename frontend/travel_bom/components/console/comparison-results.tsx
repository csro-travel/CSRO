import Image from 'next/image'
import { cn } from '@/lib/utils'
import { formatWon, type AlternativeStay } from '@/lib/alternative-stays'

type Summary = { label: string; value: string }

type ComparisonResultsProps = {
  stays: AlternativeStay[]
  summary: Summary[]
  selectedId: string
  onSelect: (id: string) => void
  footer: React.ReactNode
}

export function ComparisonResults({
  stays,
  summary,
  selectedId,
  onSelect,
  footer,
}: ComparisonResultsProps) {
  return (
    <section
      id="comparison-results"
      aria-labelledby="comparison-title"
      className="rounded-2xl border bg-card p-6"
    >
      <h2 id="comparison-title" className="text-xl font-bold">
        대체 숙소 비교 결과
      </h2>
      <p className="mt-1.5 text-sm text-muted-foreground">
        자동 검색된 후보를 거리·가격·숙박 조건으로 비교합니다. 실제 예약은 진행되지 않았습니다.
      </p>

      <dl className="mt-5 grid grid-cols-2 gap-3 xl:grid-cols-4">
        {summary.map((item) => (
          <div key={item.label} className="rounded-xl border px-5 py-4">
            <dt className="text-xs text-muted-foreground">{item.label}</dt>
            <dd className="mt-1.5 text-lg font-bold md:text-xl">{item.value}</dd>
          </div>
        ))}
      </dl>

      <div className="mt-6 overflow-x-auto">
        <table className="w-full min-w-[820px] border-collapse text-left">
          <caption className="sr-only">대체 숙소 후보 비교표</caption>
          <thead>
            <tr className="bg-muted text-xs font-bold text-muted-foreground">
              <th scope="col" className="rounded-l-lg px-4 py-3.5 font-bold">
                호텔 / 숙박 조건
              </th>
              <th scope="col" className="px-4 py-3.5 font-bold">
                기준 호텔에서
              </th>
              <th scope="col" className="px-4 py-3.5 font-bold">
                총액
              </th>
              <th scope="col" className="px-4 py-3.5 font-bold">
                재고 확인 상태
              </th>
              <th scope="col" className="rounded-r-lg px-4 py-3.5 text-center font-bold">
                상세
              </th>
            </tr>
          </thead>
          <tbody>
            {stays.map((stay) => {
              const selected = stay.id === selectedId
              return (
                <tr
                  key={stay.id}
                  className={cn('border-b last:border-b-0', selected && 'bg-accent/50')}
                >
                  <th scope="row" className="px-4 py-4 font-normal">
                    <div className="flex items-center gap-5">
                      <Image
                        src={stay.image || '/placeholder.svg'}
                        alt={`${stay.name} 외관`}
                        width={104}
                        height={72}
                        className="h-[72px] w-[104px] shrink-0 rounded-md object-cover"
                      />
                      <div>
                        <p className="text-base font-bold">{stay.name}</p>
                        <p className="mt-1.5 text-xs text-muted-foreground">{stay.stay}</p>
                      </div>
                    </div>
                  </th>
                  <td className="px-4 py-4">
                    <p className="text-sm">{stay.distanceKm}km</p>
                    <p className="mt-1.5 text-xs text-muted-foreground">직선거리</p>
                  </td>
                  <td className="px-4 py-4">
                    <p className="text-xl font-bold text-primary">{formatWon(stay.totalPrice)}</p>
                    <p className="mt-1 text-xs text-muted-foreground">검색 기간 전체 총액</p>
                  </td>
                  <td className="px-4 py-4">
                    <span className="inline-flex rounded-md bg-info/10 px-3 py-1 text-xs font-medium text-info">
                      {stay.availability}
                    </span>
                    <p className="mt-2 text-xs text-muted-foreground">{stay.checkedAt}</p>
                  </td>
                  <td className="px-4 py-4 text-center">
                    <button
                      type="button"
                      onClick={() => onSelect(stay.id)}
                      aria-pressed={selected}
                      aria-label={`${stay.name} 조건 확인`}
                      className={cn(
                        'inline-flex h-9 items-center rounded-lg border px-4 text-xs font-medium transition-colors focus-visible:ring-3 focus-visible:ring-ring/40 focus-visible:outline-none',
                        selected
                          ? 'border-primary bg-background text-accent-foreground'
                          : 'bg-background hover:bg-muted',
                      )}
                    >
                      조건 확인
                    </button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      <div className="mt-8">{footer}</div>
    </section>
  )
}
