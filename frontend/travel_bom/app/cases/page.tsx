"use client";

import { useEffect, useState } from "react";
import { getCaseDetail, getCases } from "./_api/cases";
import { CaseChatPanel } from "./_components/case-chat-panel";
import { CaseListView } from "./_components/case-list";
import type { CaseDetail, CaseList, CaseStatus } from "./_types/case";

export default function Cases() {
  const [list, setList] = useState<CaseList>();
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<CaseStatus>();
  const [attempt, setAttempt] = useState(0);
  const [selected, select] = useState<string>();
  const [detail, show] = useState<CaseDetail>();
  const [error, fail] = useState("");

  function navigate(target: number) {
    setList(undefined);
    fail("");
    setPage(target);
  }

  function filterByStatus(nextStatus: CaseStatus | undefined) {
    if (status === nextStatus) return;
    navigate(0);
    setStatus(nextStatus);
  }

  function openCase(caseId: string) {
    fail("");
    show(undefined);
    select(caseId);
  }

  useEffect(() => {
    const request = new AbortController();
    const load = async () => {
      try {
        if (selected) {
          const data = await getCaseDetail(selected, request.signal);
          if (!request.signal.aborted) show(data);
        } else {
          const data = await getCases(page, status, request.signal);
          if (!request.signal.aborted) setList(data);
        }
      } catch (cause) {
        if (!request.signal.aborted)
          fail(cause instanceof Error ? cause.message : "조회 실패");
      }
    };
    void load();
    return () => request.abort();
  }, [selected, page, status, attempt]);

  return (
    <main className={`mx-auto space-y-6 px-6 py-10 ${selected ? "max-w-5xl" : "max-w-6xl"}`}>
      <header className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h1 className="text-3xl font-bold">상담 사건</h1>
        {!selected && !error && list && (
          <span className="text-2xl font-medium text-muted-foreground tabular-nums">
            {list.pagination.totalElements.toLocaleString("ko-KR")}건
          </span>
        )}
      </header>
      {!selected && (
        <CaseListView
          list={list}
          status={status}
          error={error}
          onStatusChange={filterByStatus}
          onPageChange={navigate}
          onOpen={openCase}
          onRetry={() => {
            fail("");
            setAttempt((value) => value + 1);
          }}
        />
      )}
      {selected && error && <p role="alert">{error}</p>}
      {selected && (
        <button
          onClick={() => {
            select(undefined);
            setList(undefined);
            show(undefined);
            fail("");
          }}
        >
          ← 사건 목록
        </button>
      )}
      {selected && !detail && !error && (
        <p role="status">사건 조회 중…</p>
      )}
      {selected && detail && !error && (
        <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
          <article className="min-w-0 space-y-5 rounded-xl border p-6">
            <h2 className="text-2xl font-bold">
              {detail.caseId} · {detail.booking.hotel.name}
            </h2>
            <p>
              {detail.booking.bookingId} · 사건 {detail.status} · 공급사 계약{" "}
              {detail.booking.supplierStatus}
            </p>
            <h3 className="font-bold">숙박일별 제공 여부와 사실 상태</h3>
            {detail.nights.map((n) => (
              <p key={n.stayDate}>
                {n.stayDate} · {n.fulfillmentStatus} / {n.factStatus}
              </p>
            ))}
            <h3 className="font-bold">다음 업무</h3>
            <p>
              {detail.nextAction
                ? [
                    detail.nextAction.taskId,
                    detail.nextAction.type,
                    detail.nextAction.status,
                    detail.nextAction.dueAt ?? "기한 미정",
                  ].join(" · ")
                : "미완료 업무 없음"}
            </p>
            <h3 className="font-bold">검증 근거</h3>
            {detail.evidence.map((e) => (
              <section key={e.evidenceId} className="rounded-lg bg-muted p-4">
                <strong>
                  {e.evidenceId} · {e.sourceType}
                </strong>
                <p>{e.sourceAuthenticated ? "출처 인증됨" : "출처 미인증"}</p>
                {e.assertions.map((a) => (
                  <p key={a.stayDate}>
                    {a.stayDate} · {a.reportedProvisionStatus} / {a.reviewStatus}
                  </p>
                ))}
                <details>
                  <summary>응답 원문</summary>
                  <pre className="whitespace-pre-wrap break-all">
                    {e.originalContent}
                  </pre>
                </details>
              </section>
            ))}
            <h3 className="font-bold">고객 신고</h3>
            {detail.customerReports.length === 0 && <p>접수된 신고 없음</p>}
            {detail.customerReports.map((r) => (
              <p key={r.reportId}>
                {r.reportId} · {r.issueType} · 현장 도착 자기 신고{" "}
                {r.atHotel ? "예" : "아니요"} · {r.receivedAt}
              </p>
            ))}
          </article>
          <CaseChatPanel key={detail.caseId} caseId={detail.caseId} closed={detail.status === "CLOSED"} />
        </div>
      )}
    </main>
  );
}
