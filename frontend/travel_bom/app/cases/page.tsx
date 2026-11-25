"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { getCaseDetail, getCases } from "./_api/cases";
import { CaseChatPanel } from "./_components/case-chat-panel";
import { CaseDetailTabs } from "./_components/case-detail-tabs";
import { CaseDetails } from "./_components/case-details";
import { CaseListView } from "./_components/case-list";
import { CaseResolutionPanel } from "./_components/case-resolution-panel";
import { recoverableResolutionCases } from "./_api/resolution";
import type { CaseDetail, CaseList, CaseStatus } from "./_types/case";

export default function Cases() {
  const [list, setList] = useState<CaseList>();
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<CaseStatus>();
  const [attempt, setAttempt] = useState(0);
  const [selected, select] = useState<string>();
  const [detail, show] = useState<CaseDetail>();
  const [error, fail] = useState("");
  const [chatRefresh, setChatRefresh] = useState(0);
  const [resolutionReadError, setResolutionReadError] = useState("");
  const [recoverableCases, setRecoverableCases] = useState<string[]>([]);
  const resolutionRead = useRef<AbortController | null>(null);
  const currentSelection = useRef(selected);
  currentSelection.current = selected;

  const refreshResolution = useCallback(async () => {
    if (!selected) return false;
    resolutionRead.current?.abort();
    const request = new AbortController(); resolutionRead.current = request;
    setResolutionReadError("");
    try {
      const result = await getCaseDetail(selected, request.signal);
      if (request.signal.aborted || currentSelection.current !== selected) return false;
      show(result); setChatRefresh(value => value + 1);
      return result.resolution !== undefined;
    } catch (cause) {
      if (!request.signal.aborted && currentSelection.current === selected) setResolutionReadError(cause instanceof Error ? cause.message : "종료 조건 조회 실패");
      return false;
    }
  }, [selected]);

  useEffect(() => () => resolutionRead.current?.abort(), [selected]);
  useEffect(() => {
    if (selected) return;
    try { setRecoverableCases(recoverableResolutionCases()); }
    catch { setRecoverableCases([]); }
  }, [selected]);

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
    setResolutionReadError("");
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
        <div className="space-y-2">{recoverableCases.map(caseId => <button key={caseId} className="block rounded-md border px-3 py-2 text-sm" onClick={() => openCase(caseId)}>처리 결과 확인이 필요한 {caseId} 열기</button>)}</div>
      )}
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
        <CaseDetailTabs
          key={detail.caseId}
          title={[detail.caseId, detail.booking.hotel.name].join(" · ")}
          caseId={detail.caseId}
          closed={detail.status === "CLOSED"}
          bookingId={detail.booking.bookingId}
          onOfferSent={() => setChatRefresh(value => value + 1)}
          chat={<CaseChatPanel caseId={detail.caseId} closed={detail.status === "CLOSED"} refreshVersion={chatRefresh} />}
          resolution={<>{resolutionReadError && <p role="alert" className="mt-4">{resolutionReadError} · 이전 상태를 성공으로 추정하지 않습니다.</p>}
            <CaseResolutionPanel caseId={detail.caseId} resolution={detail.resolution} onRefresh={refreshResolution} /></>}
        >
          <CaseDetails detail={detail} />
        </CaseDetailTabs>
      )}
    </main>
  );
}
