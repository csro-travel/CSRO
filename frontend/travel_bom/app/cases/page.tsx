"use client";

import { useEffect, useRef, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { caseStatusLabels, formatDateTime } from "@/lib/case-labels";
import { getOverview, type ConsoleOverview } from "@/lib/console-api";
import { getCaseDetail, getCases } from "./_api/cases";
import { CaseChatPanel } from "./_components/case-chat-panel";
import { CaseClosurePanel } from "./_components/case-closure-panel";
import { CaseDetailTabs } from "./_components/case-detail-tabs";
import { CaseDetails } from "./_components/case-details";
import { CaseEvidenceList } from "./_components/case-evidence-list";
import { CaseEvidencePanel } from "./_components/case-evidence-panel";
import { CaseDraft } from "./_components/case-guidance";
import { CaseRiskBasis } from "./_components/case-insight";
import { CaseListView } from "./_components/case-list";
import { CaseProcessBar } from "./_components/case-process";
import type { CaseDetail, CaseList, CaseStatus } from "./_types/case";

const LIST_REFRESH_MS = 10000;
const DETAIL_REFRESH_MS = 15000;
const caseIdPattern = /^C-[0-9]{3,}$/;

export default function Cases() {
  const [list, setList] = useState<CaseList>();
  const [overview, setOverview] = useState<ConsoleOverview>();
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<CaseStatus>();
  const [search, setSearch] = useState("");
  const [attempt, setAttempt] = useState(0);
  const [selected, select] = useState<string>();
  const [detail, show] = useState<CaseDetail>();
  const [error, fail] = useState("");
  const [chatRefresh, setChatRefresh] = useState(0);
  const [draft, setDraft] = useState({ text: "", nonce: 0 });
  const [newCaseIds, setNewCaseIds] = useState<ReadonlySet<string>>(new Set());
  const [listLoadedAt, setListLoadedAt] = useState<string>();
  const [ready, setReady] = useState(false);
  // Case ids already shown on the default queue; anything else on a later refresh is new.
  const known = useRef<Set<string> | null>(null);

  // The open case lives in the URL so a reload or a shared link returns to it.
  useEffect(() => {
    const read = () => {
      const value = new URLSearchParams(window.location.search).get("case");
      select(value && caseIdPattern.test(value) ? value : undefined);
    };
    read(); setReady(true);
    window.addEventListener("popstate", read);
    return () => window.removeEventListener("popstate", read);
  }, []);

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

  function openCase(caseId: string | undefined) {
    fail("");
    show(undefined);
    if (!caseId) setList(undefined);
    select(caseId);
    window.history.pushState(null, "", caseId ? `/cases?case=${encodeURIComponent(caseId)}` : "/cases");
    if (caseId) setNewCaseIds(current => { const next = new Set(current); next.delete(caseId); return next; });
  }

  useEffect(() => {
    if (!ready) return;
    const request = new AbortController();
    const load = async () => {
      try {
        if (selected) {
          const data = await getCaseDetail(selected, request.signal);
          if (!request.signal.aborted) { show(data); fail(""); }
        } else {
          const [data, summary] = await Promise.all([
            getCases(page, status, request.signal, search),
            getOverview(request.signal).catch(() => undefined),
          ]);
          if (request.signal.aborted) return;
          setList(data); setListLoadedAt(new Date().toISOString()); fail("");
          if (summary) setOverview(summary);
          if (!status && !search && page === 0) {
            const ids = data.items.map(item => item.caseId);
            if (known.current) {
              const arrivals = ids.filter(id => !known.current!.has(id));
              if (arrivals.length) setNewCaseIds(current => new Set([...current, ...arrivals]));
            }
            known.current = new Set([...(known.current ?? []), ...ids]);
          }
        }
      } catch (cause) {
        // A failed background refresh keeps what is already on screen.
        if (!request.signal.aborted && (selected ? !detail : !list)) fail(cause instanceof Error ? cause.message : "조회 실패");
      }
    };
    void load();
    return () => request.abort();
    // `detail` and `list` are read only to decide whether a failure should replace the screen.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, selected, page, status, search, attempt]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") setAttempt(value => value + 1);
    }, selected ? DETAIL_REFRESH_MS : LIST_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [selected]);

  // A new case is also announced in the tab title, for a counselor working in another tab.
  useEffect(() => {
    document.title = newCaseIds.size ? `(${newCaseIds.size}) 새 대응 건 · 여행봄` : "여행봄 | 상담 지원";
  }, [newCaseIds]);

  if (selected) {
    const state = detail ? caseStatusLabels[detail.status] : undefined;
    return (
      <main className="flex flex-col px-5 py-4 lg:h-full lg:min-h-0 lg:overflow-hidden">
        {error && !detail && <p role="alert" className="panel px-4 py-3 text-sm text-accent-foreground">{error}</p>}
        {!detail && !error && <p role="status" className="text-sm text-muted-foreground">상담 건을 불러오는 중…</p>}
        {error && !detail && <Button type="button" variant="outline" className="mt-3 self-start" onClick={() => openCase(undefined)}>상담 대응 목록으로</Button>}
        {detail && (
          <CaseDetailTabs
            key={detail.caseId}
            title={`${detail.caseId} · ${detail.booking.hotel.name}`}
            badges={<>
              {detail.priority === "URGENT" && detail.status !== "CLOSED" && <Badge tone="urgent">긴급</Badge>}
              {state && <Badge tone={state.tone}>{state.label}</Badge>}
            </>}
            top={<CaseProcessBar detail={detail} />}
            caseId={detail.caseId}
            closed={detail.status === "CLOSED"}
            onBack={() => openCase(undefined)}
            onOfferSent={() => setChatRefresh(value => value + 1)}
            chat={<CaseChatPanel caseId={detail.caseId} closed={detail.status === "CLOSED"} refreshVersion={chatRefresh} insertDraft={draft}
              composerTop={<CaseDraft detail={detail} onUseDraft={text => setDraft(current => ({ text, nonce: current.nonce + 1 }))} />} />}
          >
            <CaseDetails detail={detail} />
            {/* Side by side on wide screens: what the hotel actually said next to why the system flagged it. */}
            <div className="grid items-start gap-3 2xl:grid-cols-2">
              <CaseRiskBasis detail={detail} />
              <CaseEvidenceList detail={detail} />
            </div>
            <CaseEvidencePanel detail={detail} onChanged={() => setAttempt(value => value + 1)} />
            <CaseClosurePanel detail={detail} onChanged={() => { setAttempt(value => value + 1); setChatRefresh(value => value + 1); }} />
          </CaseDetailTabs>
        )}
      </main>
    );
  }

  return (
    <main className="space-y-3 px-5 py-4">
      <header className="flex flex-wrap items-end justify-between gap-x-4 gap-y-1">
        <div>
          <h1 className="text-xl font-bold">상담 대응</h1>
          <p className="mt-1 text-sm text-muted-foreground">문제가 확인됐거나 고객이 신고한 예약만 올라옵니다. 긴급한 건, 기한이 빠른 건 순서입니다.</p>
        </div>
        {listLoadedAt && <p className="num text-xs text-muted-foreground">{formatDateTime(listLoadedAt)} 기준 · 10초마다 갱신</p>}
      </header>
      <CaseListView
        list={list}
        overview={overview}
        status={status}
        error={error}
        newCaseIds={newCaseIds}
        onStatusChange={filterByStatus}
        search={search}
        onSearch={value => { navigate(0); setSearch(value.trim()); }}
        onPageChange={navigate}
        onOpen={openCase}
        onRetry={() => { fail(""); setAttempt(value => value + 1); }}
      />
    </main>
  );
}
