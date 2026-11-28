"use client";

import { useEffect, useRef, useState } from "react";
import { CornerDownRight, Sparkles } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { formatDay } from "@/lib/case-labels";
import { requestAssistDraft, type AssistDraft } from "../_api/assist";
import type { CaseDetail } from "../_types/case";

/** Fixed rules over the stored facts. Shown when no model draft was requested or the model is unavailable. */
export function ruleGuidance(detail: CaseDetail) {
  const days = (filter: (night: CaseDetail["nights"][number]) => boolean) => detail.nights.filter(filter).map(night => formatDay(night.stayDate));
  const unavailable = days(night => night.factStatus === "VERIFIED" && night.fulfillmentStatus === "UNAVAILABLE");
  const unknown = days(night => night.factStatus === "UNCONFIRMED");
  const open = new Set(detail.tasks.map(task => task.type));
  const replacementBooked = open.has("CHECKIN_CONFIRM") || (detail.completedTasks ?? []).some(task => task.type === "CHECKIN_CONFIRM");
  const hotel = detail.booking.hotel.name;
  let draft: string;
  if (replacementBooked && open.has("CHECKIN_CONFIRM")) {
    draft = "안녕하세요, 여행봄입니다. 대체 숙소 예약이 확정되었습니다. 숙소에 도착해 입실하신 뒤 상담 화면의 '입실 완료 확인'을 눌러 주시면 남은 절차를 마무리하겠습니다.";
  } else if (unavailable.length) {
    draft = `안녕하세요, 여행봄입니다. 예약하신 ${hotel}에 확인한 결과 ${unavailable.join(", ")} 객실 제공이 어렵다는 회신을 받았습니다.`
      + (unknown.length ? ` ${unknown.join(", ")}은 아직 호텔에 확인 중이며 결과가 나오는 대로 알려 드리겠습니다.` : "")
      + " 같은 기간에 이용하실 수 있는 대체 숙소를 찾아 곧 안내드리겠습니다.";
  } else if (detail.customerReports.length) {
    draft = `안녕하세요, 여행봄입니다. 신고해 주신 내용을 접수했습니다. 지금 ${hotel}과 공급사에 예약 상태를 확인하고 있으며, 아직 확인된 사실은 없습니다. 결과가 나오는 대로 바로 안내드리겠습니다.`;
  } else {
    draft = `안녕하세요, 여행봄입니다. 예약하신 ${hotel}의 객실 제공 여부를 확인하고 있습니다. 확인되는 대로 안내드리겠습니다.`;
  }
  return { draft };
}

/**
 * The customer message draft, shown right above the chat composer. It is never sent as it is:
 * the counselor moves it into the composer, edits it there and sends. Nothing here changes case state.
 */
export function CaseDraft({ detail, onUseDraft }: { detail: CaseDetail; onUseDraft: (text: string) => void }) {
  const rules = ruleGuidance(detail);
  const [assist, setAssist] = useState<AssistDraft>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const request = useRef<AbortController | null>(null);
  // A draft describes the case as it was; drop it when the stored facts change.
  const factsKey = `${detail.caseId}:${detail.updatedAt}:${detail.tasks.length}:${detail.evidence.length}`;
  useEffect(() => { setAssist(undefined); setError(""); return () => request.current?.abort(); }, [factsKey]);

  async function ask() {
    if (loading) return;
    request.current?.abort();
    const controller = new AbortController(); request.current = controller;
    setLoading(true); setError("");
    try {
      const draft = await requestAssistDraft(detail.caseId, "GUIDANCE", controller.signal);
      if (!controller.signal.aborted) setAssist(draft);
    } catch (cause) {
      if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : "초안을 받지 못했습니다.");
    } finally { if (!controller.signal.aborted) setLoading(false); }
  }

  if (detail.status === "CLOSED") return null;
  const draft = assist?.customerMessageDraft ?? rules.draft;

  return (
    <div aria-label="고객 안내 초안" className="space-y-1.5 rounded-md border border-dashed border-primary/40 bg-accent/40 p-2.5">
      <div className="flex flex-wrap items-center gap-1.5">
        <p className="text-sm font-semibold">고객 안내 초안</p>
        <Badge tone="outline">미발송</Badge>
        <Badge tone={assist?.source === "LLM" ? "info" : "outline"}>{assist?.source === "LLM" ? `AI 초안 · ${assist.model ?? "모델"}` : "규칙 기반"}</Badge>
        <div className="ml-auto flex flex-wrap gap-1.5">
          <Button type="button" size="sm" variant="ghost" disabled={loading} onClick={() => void ask()}>
            <Sparkles aria-hidden="true" />{loading ? "AI 초안 만드는 중…" : assist ? "AI 초안 다시 받기" : "AI 초안 받기"}
          </Button>
          <Button type="button" size="sm" onClick={() => onUseDraft(draft)}>
            <CornerDownRight aria-hidden="true" />입력창에 넣어 수정
          </Button>
        </div>
      </div>
      <p className="scroll-area max-h-20 text-sm leading-relaxed whitespace-pre-wrap">{draft}</p>
      {assist?.warnings.map(warning => <p key={warning} className="text-xs text-muted-foreground">{warning}</p>)}
      {error && <p role="alert" className="text-xs text-accent-foreground">{error} 규칙 기반 초안을 그대로 쓸 수 있습니다.</p>}
    </div>
  );
}
