"use client";

import { useParams } from "next/navigation";
import { CaseChatPanel } from "@/app/cases/_components/case-chat-panel";

/** Customer uses API-011 and API-014 only, through the separate customer proxy. */
export default function CustomerCase() {
  const { caseId } = useParams<{ caseId: string }>();
  return (
    <main className="mx-auto max-w-3xl space-y-5 px-5 py-8">
      <header className="space-y-2">
        <h1 className="text-2xl font-bold">내 예약 상담 · {caseId}</h1>
        <p className="text-sm text-muted-foreground">여행봄 고객 전용 시연 · 전달받은 대체 숙소 제안을 확인해 주세요.</p>
      </header>
      {/^C-[0-9]{3,38}$/.test(caseId)
        ? <CaseChatPanel key={caseId} caseId={caseId} closed={false} customer />
        : <p role="alert">상담 번호를 확인해 주세요.</p>}
    </main>
  );
}
