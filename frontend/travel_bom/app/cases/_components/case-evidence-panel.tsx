"use client";

import { useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { ClosureError, recordHotelEvidence } from "../_api/closure";
import type { CaseDetail } from "../_types/case";

const card = "min-w-0 rounded-xl border bg-card px-4 py-3.5";
const choices = [["", "기록 안 함"], ["AVAILABLE", "제공 가능"], ["UNAVAILABLE", "제공 불가"], ["UNKNOWN", "확인 안 됨"]] as const;

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
      setMessage(result.caseStatus === "WAITING" ? "기록했습니다. 확인되지 않은 숙박일이 남아 사건이 확인 대기로 바뀌었습니다." : "기록했습니다.");
      onChanged();
    } catch (cause) {
      if (cause instanceof ClosureError && !cause.outcomeUnknown) key.current = null;
      setMessage(cause instanceof Error ? cause.message : "기록 실패");
    } finally { setSending(false); }
  }

  return (
    <section className={`${card} mt-3 space-y-2`} aria-label="호텔 직접 확인 기록">
      <h3 className="text-sm font-semibold">호텔 직접 확인 기록</h3>
      <p className="text-xs text-muted-foreground">호텔과 통화한 결과를 숙박일별로 남깁니다. 미확인 숙박일에 처음 확인된 결과만 사실로 반영되고, 기존 사실과 다르면 상충으로 표시됩니다.</p>
      <ul className="grid gap-2 sm:grid-cols-3">
        {detail.nights.map(night => (
          <li key={night.stayDate} className="space-y-1 text-sm">
            <label htmlFor={`evidence-${night.stayDate}`} className="block text-xs font-medium">{night.stayDate}</label>
            <select id={`evidence-${night.stayDate}`} value={statuses[night.stayDate] ?? ""} disabled={sending}
              onChange={event => setStatuses(value => ({ ...value, [night.stayDate]: event.target.value }))}
              className="w-full rounded-lg border bg-background px-2 py-1.5">
              {choices.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
          </li>
        ))}
      </ul>
      <textarea aria-label="호텔 통화 내용" value={note} maxLength={2000} rows={2} disabled={sending} onChange={event => setNote(event.target.value)}
        placeholder="통화 상대·시각·호텔이 말한 내용" className="w-full rounded-lg border bg-background px-2.5 py-1.5 text-sm" />
      <Button type="button" size="sm" variant="outline" disabled={sending || !note.trim() || !nights.length} onClick={() => void submit()}>
        {sending ? "기록 중…" : "확인 결과 기록"}
      </Button>
      {message && <p role="status" className="text-sm">{message}</p>}
    </section>
  );
}
