import { Check, ChevronDown, Clock3, CircleAlert, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

type SearchStatusProps = {
  state: 'idle' | 'loading' | 'success' | 'error'
  candidateCount: number
  updatedAt: string
  expanded: boolean
  onToggle: () => void
}

export function SearchStatus({
  state,
  candidateCount,
  updatedAt,
  expanded,
  onToggle,
}: SearchStatusProps) {
  const Icon = state === 'loading' ? Loader2 : state === 'success' ? Check
    : state === 'error' ? CircleAlert : Clock3
  const title = state === 'loading' ? '후보 검색 중' : state === 'success'
    ? `탐색 완료 · 후보 ${candidateCount}곳` : state === 'error' ? '검색 실패' : '검색 대기'
  const description = state === 'loading' ? '외부 검색 응답을 기다리고 있습니다.'
    : state === 'success' ? `조회 결과 · 마지막 갱신 ${updatedAt}`
      : state === 'error' ? '오류 내용을 확인한 뒤 다시 시도해 주세요.'
        : '예약 정보를 확인한 후 검색을 시작하세요.'
  return (
    <section
      aria-label="검색 상태"
      aria-live="polite"
      className="flex flex-col gap-4 rounded-2xl border bg-card px-5 py-4 sm:flex-row sm:items-center sm:justify-between"
    >
      <div className="flex items-center gap-4">
        <span className={cn('flex size-8 shrink-0 items-center justify-center rounded-full',
          state === 'success' ? 'bg-success/10 text-success' : state === 'error'
            ? 'bg-destructive/10 text-destructive' : 'bg-muted text-muted-foreground')}>
          <Icon className={cn('size-4', state === 'loading' && 'animate-spin')} aria-hidden="true" />
        </span>
        <div>
          <p className="font-bold">
            {title}
          </p>
          <p className="mt-0.5 text-xs text-muted-foreground">
            {description}
          </p>
        </div>
      </div>

      {state === 'success' && <Button
        type="button"
        variant="outline"
        size="lg"
        onClick={onToggle}
        aria-expanded={expanded}
        aria-controls="comparison-results"
      >
        <ChevronDown
          className={cn('size-4 transition-transform', expanded && 'rotate-180')}
          aria-hidden="true"
        />
        {expanded ? '결과 접기' : '탐색된 숙소 보기'}
      </Button>}
    </section>
  )
}
