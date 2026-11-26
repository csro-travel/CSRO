"use client";

import { useParams } from "next/navigation";
import { CaseChatPanel } from "@/app/cases/_components/case-chat-panel";

/** Customer uses API-011 and API-014 only, through the separate customer proxy. */
export default function CustomerCase() {
  const { caseId } = useParams<{ caseId: string }>();
  return (
    <main className="mx-auto flex h-full max-w-3xl flex-col gap-3 px-4 py-4">
      <header className="shrink-0 space-y-1">
        <h1 className="text-xl font-bold">내 예약 상담 · {caseId}</h1>
        <p className="text-sm text-muted-foreground">여행봄 고객 전용 시연 · 전달받은 대체 숙소 제안을 확인해 주세요.</p>
      </header>
      {/^C-[0-9]{3,38}$/.test(caseId)
        ? <div className="min-h-0 flex-1"><CaseChatPanel key={caseId} caseId={caseId} closed={false} customer /></div>
        : <p role="alert">상담 번호를 확인해 주세요.</p>}
    </main>
  );
}
