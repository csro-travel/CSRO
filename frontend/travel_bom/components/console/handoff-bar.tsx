import { Check } from 'lucide-react'

type HandoffBarProps = {
  selectedName: string
  sent: boolean
  onSend: () => void
}

export function HandoffBar({ selectedName, sent, onSend }: HandoffBarProps) {
  return (
    <div className="flex flex-col gap-4 rounded-xl bg-muted px-5 py-5 md:flex-row md:items-center md:justify-between">
      <div>
        <p className="font-bold">상담사 전달 전 확인</p>
        <p className="mt-1.5 text-xs leading-relaxed text-muted-foreground">
          가격과 재고는 조회 시점에만 유효합니다. 후보 비교 결과는 예약·결제·보상 승인을 의미하지
          않습니다.
        </p>
        <p className="mt-1 text-xs text-foreground" aria-live="polite">
          {sent
            ? `${selectedName} 후보가 상담사에게 전달되었습니다.`
            : `선택된 후보: ${selectedName}`}
        </p>
      </div>
      <button
        type="button"
        onClick={onSend}
        disabled={sent}
        className="inline-flex h-11 shrink-0 items-center justify-center gap-2 rounded-lg bg-primary px-5 text-sm font-bold text-primary-foreground transition-opacity hover:opacity-90 focus-visible:ring-3 focus-visible:ring-ring/40 focus-visible:outline-none disabled:opacity-70"
      >
        {sent && <Check className="size-4" aria-hidden="true" />}
        {sent ? '전달 완료' : '상담사에게 전달'}
      </button>
    </div>
  )
}
