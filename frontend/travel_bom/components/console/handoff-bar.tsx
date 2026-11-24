export function HandoffBar({ selectedName }: { selectedName: string }) {
  return (
    <div className="space-y-2 rounded-xl bg-muted px-5 py-5">
      <p className="font-bold">검토 중인 후보: {selectedName}</p>
      <p className="text-xs leading-relaxed text-muted-foreground">
        고객 제안 발송과 상담사 전달은 아직 연결되지 않았습니다.
        현재 선택은 이 화면에만 표시되며 서버에 저장되지 않습니다.
        예약·결제·보상 승인은 실행되지 않았습니다.
      </p>
    </div>
  )
}
