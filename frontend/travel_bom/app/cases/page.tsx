"use client";

import { useEffect, useState } from "react";
import { getCaseDetail, getCases } from "./_api/cases";
import { CaseChatPanel } from "./_components/case-chat-panel";
import { CaseClosurePanel } from "./_components/case-closure-panel";
import { CaseDetailTabs } from "./_components/case-detail-tabs";
import { CaseDetails } from "./_components/case-details";
import { CaseListView } from "./_components/case-list";
import type { CaseDetail, CaseList, CaseStatus } from "./_types/case";

export default function Cases() {
  const [list, setList] = useState<CaseList>();
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<CaseStatus>();
  const [search, setSearch] = useState("");
  const [attempt, setAttempt] = useState(0);
  const [selected, select] = useState<string>();
  const [detail, show] = useState<CaseDetail>();
  const [error, fail] = useState("");
  const [chatRefresh, setChatRefresh] = useState(0);

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
          const data = await getCases(page, status, request.signal, search);
          if (!request.signal.aborted) setList(data);
        }
      } catch (cause) {
        if (!request.signal.aborted)
          fail(cause instanceof Error ? cause.message : "조회 실패");
      }
    };
    void load();
    return () => request.abort();
  }, [selected, page, status, search, attempt]);

  return (
    <main className={`mx-auto space-y-6 px-6 py-10 ${selected ? "max-w-5xl" : "max-w-6xl"}`}>
      <header className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h1 className="text-3xl font-bold">상담 대응 목록</h1>
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
          search={search}
          onSearch={(value) => { navigate(0); setSearch(value.trim()); }}
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
          ← 상담 대응 목록
        </button>
      )}
      {selected && !detail && !error && (
        <p role="status">대응 건 조회 중…</p>
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
        >
          <CaseDetails detail={detail} />
          <CaseClosurePanel detail={detail} onChanged={() => { setAttempt(value => value + 1); setChatRefresh(value => value + 1); }} />
        </CaseDetailTabs>
      )}
    </main>
  );
}
