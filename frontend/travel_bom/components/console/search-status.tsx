import { Check, ChevronDown, Loader2 } from 'lucide-react'
import { cn } from '@/lib/utils'

type SearchStatusProps = {
  searching: boolean
  candidateCount: number
  updatedAt: string
  expanded: boolean
  onToggle: () => void
}

export function SearchStatus({
  searching,
  candidateCount,
  updatedAt,
  expanded,
  onToggle,
}: SearchStatusProps) {
  return (
    <section
      aria-live="polite"
      className="flex flex-col gap-4 rounded-2xl border bg-card px-5 py-4 sm:flex-row sm:items-center sm:justify-between"
    >
      <div className="flex items-center gap-4">
        <span className="flex size-8 shrink-0 items-center justify-center rounded-full bg-success/10 text-success">
          {searching ? (
            <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          ) : (
            <Check className="size-4" aria-hidden="true" />
          )}
        </span>
        <div>
          <p className="font-bold">
            {searching ? '탐색 시연 중' : `시연용 숙소 후보 ${candidateCount}개 표시`}
          </p>
          <p className="mt-0.5 text-xs text-muted-foreground">
            {searching
              ? '고정 후보를 불러오는 동작을 시연합니다.'
              : `시연 화면 갱신 ${updatedAt} · 실제 공급사 조회 없음`}
          </p>
        </div>
      </div>

      <button
        type="button"
        onClick={onToggle}
        aria-expanded={expanded}
        aria-controls="comparison-results"
        className="inline-flex h-10 items-center justify-center gap-2 rounded-lg border bg-background px-4 text-sm font-medium transition-colors hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/40 focus-visible:outline-none"
      >
        <ChevronDown
          className={cn('size-4 transition-transform', expanded && 'rotate-180')}
          aria-hidden="true"
        />
        {expanded ? '결과 접기' : '시연용 후보 보기'}
      </button>
    </section>
  )
}
