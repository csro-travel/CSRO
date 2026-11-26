"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { useParams, useRouter } from "next/navigation";
import { Send } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Textarea } from "@/components/ui/input";
import { BookingSummary } from "./booking-summary";

type IncidentBody = { issueType: "CHECKIN_FAILED"; atHotel: boolean; message: string };
type Intent = { version: 1; key: string; body: IncidentBody };
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);
const validMessage = (value: unknown): value is string =>
  typeof value === "string" && !value.includes("\0") && [...value.trim()].length >= 1 && [...value.trim()].length <= 1000;
const storageKey = (bookingId: string) => `csro.incident-intent.v1:${bookingId}`;
const unknownOutcome = "접수 결과를 확인하지 못했습니다. 내용을 바꾸지 말고 같은 요청으로 다시 확인해 주세요.";

function loadIntent(bookingId: string): Intent | undefined {
  const raw = sessionStorage.getItem(storageKey(bookingId));
  if (!raw) return;
  const value: unknown = JSON.parse(raw);
  if (!record(value) || value.version !== 1 || typeof value.key !== "string" || !/^[A-Za-z0-9._:-]{1,100}$/.test(value.key)
      || !record(value.body) || Object.keys(value.body).length !== 3 || value.body.issueType !== "CHECKIN_FAILED"
      || typeof value.body.atHotel !== "boolean" || !validMessage(value.body.message)) {
    throw Error("보관된 접수 요청을 확인할 수 없습니다. 새 신고를 보내기 전에 상담사에게 확인해 주세요.");
  }
  return value as Intent;
}

/** API-002 receipt is unverified customer testimony; the server alone decides case and night states. */
export default function CustomerIncidentReport() {
  const { bookingId } = useParams<{ bookingId: string }>();
  const router = useRouter();
  const [message, setMessage] = useState("");
  const [atHotel, setAtHotel] = useState(true);
  const [pending, setPending] = useState<Intent>();
  const [ready, setReady] = useState(false);
  const [blocked, setBlocked] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [receivedCase, setReceivedCase] = useState("");
  const sending = useRef(false);
  const validBooking = /^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$/.test(bookingId);

  useEffect(() => {
    setReady(false);
    setPending(undefined);
    setBlocked(false);
    setError("");
    setReceivedCase("");
    setMessage("");
    setAtHotel(true);
    try {
      const saved = loadIntent(bookingId);
      if (saved) {
        setPending(saved);
        setMessage(saved.body.message);
        setAtHotel(saved.body.atHotel);
        setError(unknownOutcome);
      }
    } catch {
      setBlocked(true);
      setError("보관된 접수 요청을 안전하게 읽을 수 없습니다. 새 신고를 보내기 전에 상담사에게 확인해 주세요.");
    }
    setReady(true);
  }, [bookingId]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (sending.current || !ready || blocked || !validBooking || receivedCase || (!pending && !validMessage(message))) return;
    sending.current = true;
    setBusy(true);
    setError("");
    let intent = pending;
    let prepared = !!intent;
    try {
      if (!intent) {
        intent = { version: 1, key: `incident-${crypto.randomUUID()}`, body: { issueType: "CHECKIN_FAILED", atHotel, message: message.trim() } };
        // Save before the write: a network error or reload must not create a new report key.
        sessionStorage.setItem(storageKey(bookingId), JSON.stringify(intent));
        prepared = true;
        setPending(intent);
      }
      const response = await fetch(`/demo/bookings/${encodeURIComponent(bookingId)}/incident-reports`, {
        method: "POST", headers: { "Content-Type": "application/json", "Idempotency-Key": intent.key },
        body: JSON.stringify(intent.body), signal: AbortSignal.timeout(10000),
      });
      if (response.status >= 500) throw Error(unknownOutcome);
      const result: unknown = await response.json().catch(() => null);
      if (response.status !== 201) {
        // A documented rejection did not accept this report; permit a corrected new intent.
        if ([400, 401, 403, 404, 409, 415].includes(response.status)) {
          sessionStorage.removeItem(storageKey(bookingId));
          setPending(undefined);
          setError(record(result) && typeof result.detail === "string" ? result.detail : `접수가 거절되었습니다 (HTTP ${response.status}).`);
          return;
        }
        throw Error(unknownOutcome);
      }
      if (!record(result) || result.bookingId !== bookingId || typeof result.reportId !== "string" || !result.reportId
          || typeof result.chatMessageId !== "string" || !result.chatMessageId || result.verificationStatus !== "PENDING"
          || typeof result.receivedAt !== "string" || !/(Z|[+-]\d{2}:\d{2})$/.test(result.receivedAt) || !Number.isFinite(Date.parse(result.receivedAt))
          || !record(result.case) || typeof result.case.caseId !== "string" || !/^C-[0-9]{3,38}$/.test(result.case.caseId)
          || typeof result.case.created !== "boolean" || !["OPEN", "IN_PROGRESS", "WAITING"].includes(String(result.case.status))
          || result.case.priority !== "URGENT") throw Error(unknownOutcome);
      sessionStorage.removeItem(storageKey(bookingId));
      setPending(undefined);
      setReceivedCase(result.case.caseId);
      router.push(`/customer/cases/${encodeURIComponent(result.case.caseId)}`);
    } catch {
      setError(prepared ? unknownOutcome : "접수 요청을 안전하게 보관할 수 없어 전송하지 않았습니다. 브라우저 저장소 설정을 확인해 주세요.");
    } finally {
      sending.current = false;
      setBusy(false);
    }
  }

  return (
    <main className="mx-auto max-w-2xl space-y-5 px-5 py-6">
      <header className="space-y-1.5">
        <h1 className="text-xl font-bold">체크인 문제 신고</h1>
        <p className="text-sm text-muted-foreground">여행봄 고객 전용 시연 · 예약 {bookingId}</p>
        <p className="text-sm">접수 후 호텔에 예약을 재확인합니다. 고객 신고만으로 객실 제공 불가나 대체 예약이 확정되지는 않습니다.</p>
      </header>
      {validBooking && <BookingSummary bookingId={bookingId} />}
      {!validBooking ? <p role="alert">예약 번호를 확인해 주세요.</p> : (
        <form onSubmit={submit} className="panel space-y-4 p-4">
          <fieldset disabled={!ready || blocked || busy || !!pending || !!receivedCase} className="space-y-4 disabled:opacity-70">
            <label className="flex items-center gap-2 text-sm font-medium">
              <Checkbox checked={atHotel} onCheckedChange={checked => setAtHotel(checked === true)}
                disabled={!ready || blocked || busy || !!pending || !!receivedCase} />
              지금 숙소에 도착해 있습니다
            </label>
            <label className="block space-y-2">
              <span className="text-sm font-medium">어떤 체크인 문제가 있나요?</span>
              <Textarea value={message} onChange={event => setMessage(event.target.value)} required rows={6}
                placeholder="예: 호텔 프런트에서 예약을 찾을 수 없다고 합니다."
                aria-describedby="incident-message-help" />
            </label>
            <p id="incident-message-help" className="text-xs text-muted-foreground">1~1,000자 · 비밀번호나 결제 정보를 적지 마세요. 현재 {[...message.trim()].length.toLocaleString("ko-KR")}자</p>
          </fieldset>
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
          {receivedCase && <p role="status">신고가 접수되었습니다. <a href={`/customer/cases/${encodeURIComponent(receivedCase)}`} className="underline">상담 내용 확인</a></p>}
          <Button type="submit" size="lg" disabled={!ready || blocked || busy || !!receivedCase || (!pending && !validMessage(message))}>
            <Send aria-hidden="true" />{busy ? "접수 확인 중…" : pending ? "같은 요청으로 접수 결과 확인" : "체크인 문제 접수"}
          </Button>
        </form>
      )}
    </main>
  );
}
