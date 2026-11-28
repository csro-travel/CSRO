'use client'

import { useEffect, useId, useRef, useState } from 'react'
import { cn } from '@/lib/utils'
import { formatWon, type AlternativeStay } from '@/lib/alternative-stays'

type Summary = { label: string; value: string }

type ComparisonResultsProps = {
  stays: AlternativeStay[]
  summary: Summary[]
  selectedIds: string[]
  selectionDisabled: boolean
  onToggleSelection: (id: string) => void
}

export function ComparisonResults({
  stays,
  summary,
  selectedIds,
  selectionDisabled,
  onToggleSelection,
}: ComparisonResultsProps) {
  const [detailId, setDetailId] = useState<string | null>(null)
  const dialog = useRef<HTMLDialogElement>(null)
  const dialogTitle = useId()
  const detail = stays.find((stay) => stay.id === detailId)

  useEffect(() => {
    const modal = dialog.current
    if (detail && modal && !modal.open) modal.showModal()
    if (!detail && modal?.open) modal.close()
  }, [detail])
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
        <table className="w-full min-w-[640px] border-collapse text-left">
          <caption className="sr-only">대체 숙소 후보 비교표</caption>
          <thead>
            <tr className="bg-muted text-xs font-bold text-muted-foreground">
              <th scope="col" className="rounded-l-lg px-4 py-3.5 font-bold">
                선택 / 호텔 및 숙박 조건
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
              const selected = selectedIds.includes(stay.id)
              return (
                <tr
                  key={stay.id}
                  className={cn('border-b last:border-b-0', selected && 'bg-accent/50')}
                >
                  <th scope="row" className="px-4 py-4 font-normal">
                    <div className="flex items-center gap-5">
                      <input
                        type="checkbox"
                        checked={selected}
                        disabled={selectionDisabled || (!selected && selectedIds.length >= 3)}
                        onChange={() => onToggleSelection(stay.id)}
                        aria-label={`${stay.name} 상담사 검토 후보 선택`}
                        className="size-5 shrink-0 cursor-pointer accent-primary focus-visible:ring-3 focus-visible:ring-ring/40 disabled:cursor-not-allowed"
                      />
                      <div>
                        <p className="text-base font-bold">{stay.name}</p>
                        <p className="mt-1.5 text-xs text-muted-foreground">{stay.stay}{stay.score !== null && ` · ${stay.score}점`}</p>
                      </div>
                    </div>
                  </th>
                  <td className="px-4 py-4">
                    <p className="text-sm">{stay.distanceKm.toFixed(2)}km</p>
                    <p className="mt-1.5 text-xs text-muted-foreground">직선거리</p>
                  </td>
                  <td className="px-4 py-4">
                    <p className="text-xl font-bold text-primary">{stay.totalPrice === null ? '미확인' : formatWon(stay.totalPrice)}</p>
                    <p className="mt-1 text-xs text-muted-foreground">조회 일정 총액</p>
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
                      onClick={() => setDetailId(stay.id)}
                      aria-haspopup="dialog"
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

      <dialog
        ref={dialog}
        aria-labelledby={dialogTitle}
        onClose={() => setDetailId(null)}
        className="fixed inset-0 m-auto max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] max-w-lg overflow-auto rounded-2xl border bg-card p-6 text-card-foreground shadow-xl backdrop:bg-black/50"
      >
        <div className="mb-4 flex items-center justify-between gap-4">
          <h2 id={dialogTitle} className="text-lg font-bold">숙소 조건 확인</h2>
          <button
            type="button"
            onClick={() => dialog.current?.close()}
            className="rounded-lg border px-3 py-1.5 text-sm font-medium focus-visible:ring-3 focus-visible:ring-ring/40"
          >
            닫기
          </button>
        </div>
        {detail && <StayDetails stay={detail} />}
      </dialog>
    </section>
  )
}

function StayDetails({ stay }: { stay: AlternativeStay }) {
  return (
    <div className="space-y-2 text-sm">
      <h3 className="font-semibold">{stay.name}</h3>
      <p>{stay.stay}</p>
      <p>직선거리 {stay.distanceKm.toFixed(2)}km · 조회 일정 총액 {stay.totalPrice === null ? '미확인' : formatWon(stay.totalPrice)}</p>
      <h4 className="font-semibold">상담사 확인 사항</h4>
      <ul className="list-disc space-y-1 pl-5">
        {stay.counselorChecks.map((item) => <li key={item}>{item}</li>)}
        <li>고객에게 안내하기 전에 실제 예약 가능 여부와 최종 요금을 다시 확인해 주세요.</li>
      </ul>
      {stay.listingUrl ? (
        <p className="break-all border-t pt-3">
          <a href={stay.listingUrl} target="_blank" rel="noopener noreferrer" className="text-primary underline">
            {stay.listingUrl}
          </a>
        </p>
      ) : <p className="border-t pt-3 text-muted-foreground">숙소 링크 미확인</p>}
    </div>
  )
}
