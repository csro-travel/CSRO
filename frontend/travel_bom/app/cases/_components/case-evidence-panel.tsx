"use client";

import { useRef, useState } from "react";
import { PhoneCall } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { formatDay } from "@/lib/case-labels";
import { ClosureError, recordHotelEvidence } from "../_api/closure";
import type { CaseDetail } from "../_types/case";

const choices = [
  { value: "", label: "기록 안 함" }, { value: "AVAILABLE", label: "제공 가능" },
  { value: "UNAVAILABLE", label: "제공 불가" }, { value: "UNKNOWN", label: "확인 안 됨" },
] as const;

/** Counselor-side API-006: record a direct hotel call as evidence for chosen original nights. */
export function CaseEvidencePanel({ detail, onChanged }: { detail: CaseDetail; onChanged: () => void }) {
  const [note, setNote] = useState("");
  const [statuses, setStatuses] = useState<Record<string, string>>({});
  const [sending, setSending] = useState(false);
  const [message, setMessage] = useState("");
  const key = useRef<string | null>(null);
  if (detail.status === "CLOSED") return null;
  const nights = Object.entries(statuses).filter(([, status]) => status).map(([stayDate, reportedProvisionStatus]) => ({ stayDate, reportedProvisionStatus }));

  async function submit() {
    if (sending || !note.trim() || !nights.length) return;
    key.current ??= crypto.randomUUID();
    setSending(true); setMessage("");
    try {
      const result = await recordHotelEvidence(detail.caseId, key.current, { callNote: note.trim(), nights });
      key.current = null; setNote(""); setStatuses({});
      setMessage(result.caseStatus === "WAITING" ? "기록했습니다. 확인되지 않은 숙박일이 남아 상담 상태가 확인 대기로 바뀌었습니다." : "기록했습니다.");
      onChanged();
    } catch (cause) {
      if (cause instanceof ClosureError && !cause.outcomeUnknown) key.current = null;
      setMessage(cause instanceof Error ? cause.message : "기록 실패");
    } finally { setSending(false); }
  }

  return (
    <section className="panel space-y-3 p-4" aria-label="수동 확인 및 후속 조치 기록">
      <div>
        <h3 className="flex items-center gap-1.5 text-base font-semibold"><PhoneCall className="size-4 text-muted-foreground" aria-hidden="true" />수동 확인 및 후속 조치 기록</h3>
        <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
          호텔과 통화한 결과를 숙박일별로 남깁니다. 미확인 숙박일에 처음 확인된 결과만 사실로 반영되고, 이미 확인된 사실과 다르면 상충으로 표시됩니다.
        </p>
      </div>
      <ul className="grid gap-2 sm:grid-cols-3">
        {detail.nights.map(night => (
          <li key={night.stayDate} className="space-y-1">
            <label htmlFor={`evidence-${night.stayDate}`} className="num block text-xs font-medium">{formatDay(night.stayDate)}</label>
            <Select id={`evidence-${night.stayDate}`} value={statuses[night.stayDate] ?? ""} disabled={sending} options={choices}
              onValueChange={value => setStatuses(current => ({ ...current, [night.stayDate]: value }))} />
          </li>
        ))}
      </ul>
      <Textarea aria-label="호텔 통화 내용" value={note} maxLength={2000} rows={2} disabled={sending} onChange={event => setNote(event.target.value)}
        placeholder="통화 상대·시각·호텔이 말한 내용" />
      <div className="flex flex-wrap items-center gap-3">
        <Button type="button" variant="outline" disabled={sending || !note.trim() || !nights.length} onClick={() => void submit()}>
          {sending ? "기록 중…" : "확인 결과 기록"}
        </Button>
        {message && <p role="status" className="text-sm">{message}</p>}
      </div>
    </section>
  );
}
