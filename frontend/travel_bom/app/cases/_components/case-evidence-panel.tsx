"use client";

import { useRef, useState } from "react";
import { PhoneCall } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { formatDay } from "@/lib/case-labels";
import { ClosureError, recordHotelEvidence } from "../_api/closure";
import type { CaseDetail } from "../_types/case";

const choices = [
  { value: "AVAILABLE", label: "가능" }, { value: "UNAVAILABLE", label: "불가" }, { value: "UNKNOWN", label: "확인 안 됨" },
] as const;

/**
 * Counselor-side API-006, shown inside the hotel replies. When the automatic re-check could not
 * settle a night, the counselor calls the hotel and enters what it said for the nights still open.
 * One button until it is needed; nothing to fill in once every night is a verified fact.
 */
export function HotelCallEntry({ detail, onChanged }: { detail: CaseDetail; onChanged: () => void }) {
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState("");
  const [statuses, setStatuses] = useState<Record<string, string>>({});
  const [sending, setSending] = useState(false);
  const [message, setMessage] = useState("");
  const key = useRef<string | null>(null);
  const pending = detail.nights.filter(night => night.factStatus !== "VERIFIED");
  if (detail.status === "CLOSED" || pending.length === 0) return message ? <p role="status" className="mt-2 text-sm">{message}</p> : null;
  const nights = pending.filter(night => statuses[night.stayDate]).map(night => ({ stayDate: night.stayDate, reportedProvisionStatus: statuses[night.stayDate] }));

  async function submit() {
    if (sending || !note.trim() || !nights.length) return;
    key.current ??= crypto.randomUUID();
    setSending(true); setMessage("");
    try {
      const result = await recordHotelEvidence(detail.caseId, key.current, { callNote: note.trim(), nights });
      key.current = null; setNote(""); setStatuses({}); setOpen(false);
      setMessage(result.caseStatus === "WAITING" ? "기록했습니다. 확인되지 않은 숙박일이 남아 상담 상태가 확인 대기로 바뀌었습니다." : "기록했습니다.");
      onChanged();
    } catch (cause) {
      if (cause instanceof ClosureError && !cause.outcomeUnknown) key.current = null;
      setMessage(cause instanceof Error ? cause.message : "기록 실패");
    } finally { setSending(false); }
  }

  return (
    <div aria-label="호텔 통화 결과" className="mt-3 rounded-md border border-dashed p-3">
      <div className="flex flex-wrap items-center gap-2">
        <p className="flex min-w-0 flex-1 items-center gap-1.5 text-sm font-semibold">
          <PhoneCall className="size-4 text-muted-foreground" aria-hidden="true" />
          호텔에 전화해 확인했나요?
          <span className="font-normal text-muted-foreground">미확인 {pending.length}박</span>
        </p>
        <Button type="button" size="sm" variant={open ? "outline" : "default"} aria-expanded={open} onClick={() => setOpen(value => !value)}>
          {open ? "닫기" : "호텔 통화 결과 입력"}
        </Button>
      </div>
      {open && (
        <div className="mt-3 space-y-2.5">
          <ul className="space-y-1.5">
            {pending.map(night => (
              <li key={night.stayDate} className="flex flex-wrap items-center gap-2">
                <span className="num w-24 text-sm font-medium">{formatDay(night.stayDate)}</span>
                <div role="group" aria-label={`${formatDay(night.stayDate)} 호텔 답변`} className="flex gap-1">
                  {choices.map(choice => {
                    const picked = statuses[night.stayDate] === choice.value;
                    return (
                      <Button key={choice.value} type="button" size="sm" variant={picked ? "default" : "outline"} aria-pressed={picked} disabled={sending}
                        onClick={() => setStatuses(current => ({ ...current, [night.stayDate]: picked ? "" : choice.value }))}>
                        {choice.label}
                      </Button>
                    );
                  })}
                </div>
              </li>
            ))}
          </ul>
          <div className="flex flex-wrap gap-2">
            <Input aria-label="호텔 통화 내용" value={note} maxLength={2000} disabled={sending} onChange={event => setNote(event.target.value)}
              placeholder="통화 상대·시각·호텔이 말한 내용" className="min-w-48 flex-1" />
            <Button type="button" disabled={sending || !note.trim() || !nights.length} onClick={() => void submit()}>
              {sending ? "기록 중…" : "저장"}
            </Button>
          </div>
          <p className="text-xs text-muted-foreground">가능·불가로 답한 날짜는 사실로 반영되고, 이미 확인된 내용과 다르면 상충으로 표시됩니다.</p>
        </div>
      )}
      {message && <p role="status" className="mt-2 text-sm">{message}</p>}
    </div>
  );
}
