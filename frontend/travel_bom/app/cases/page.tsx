'use client'

import { useEffect, useState } from 'react'
import { getCaseDetail, getCases } from './_api/cases'
import type { CaseDetail, CaseList } from './_types/case'

export default function Cases() {
  const [list, setList] = useState<CaseList>()
  const [page, setPage] = useState(0)
  const [attempt, setAttempt] = useState(0)
  const pagination = list?.pagination
  function navigate(target: number) {
    setList(undefined)
    fail('')
    setPage(target)
  }
  const [selected, select] = useState<string>()
  const [detail, show] = useState<CaseDetail>()
  const [error, fail] = useState('')
  useEffect(() => {
    const request = new AbortController()
    const load = async () => {
      try {
        if (selected) {
          const data = await getCaseDetail(selected, request.signal)
          if (!request.signal.aborted) show(data)
        } else {
          const data = await getCases(page, request.signal)
          if (!request.signal.aborted) setList(data)
        }
      } catch (cause) {
        if (!request.signal.aborted) fail(cause instanceof Error ? cause.message : '조회 실패')
      }
    }
    void load()
    return () => request.abort()
  }, [selected, page, attempt])

  return <main className="mx-auto max-w-5xl space-y-6 px-6 py-10">
    <h1 className="text-3xl font-bold">상담 사건</h1>
    {error && <p role="alert">{error}</p>}
    {!selected && error && <button onClick={() => { fail(''); setAttempt(value => value + 1) }}>다시 시도</button>}
    {!selected && !error && <section className="space-y-3">{list?.items.map(item =>
      <button key={item.caseId} onClick={() => { fail(''); show(undefined); select(item.caseId) }}
        className="block w-full rounded-xl border p-4 text-left hover:bg-muted">
        <strong>{item.caseId} · {item.hotelName}</strong><p>{item.bookingId} · {item.priority} · {item.summary}</p>
      </button>)}{list?.items.length === 0 && <p>이 페이지에 상담 사건이 없습니다.</p>}
      {pagination && <nav aria-label="사건 목록 페이지" className="flex items-center gap-4">
        <button disabled={pagination.page === 0} onClick={() => navigate(pagination.page - 1)}>이전</button>
        <span>{pagination.page + 1} / {Math.max(1, Math.ceil(pagination.totalElements / pagination.size))} 페이지 · 전체 {pagination.totalElements}건</span>
        <button disabled={!pagination.hasNext} onClick={() => navigate(pagination.page + 1)}>다음</button>
      </nav>}</section>}
    {selected && <button onClick={() => { select(undefined); setList(undefined); show(undefined); fail('') }}>← 사건 목록</button>}
    {!error && ((!selected && !list) || (selected && !detail)) && <p role="status">사건 조회 중…</p>}
    {selected && detail && !error && <article className="space-y-5 rounded-xl border p-6">
      <h2 className="text-2xl font-bold">{detail.caseId} · {detail.booking.hotel.name}</h2>
      <p>{detail.booking.bookingId} · 사건 {detail.status} · 공급사 계약 {detail.booking.supplierStatus}</p>
      <h3 className="font-bold">숙박일별 제공 여부와 사실 상태</h3>
      {detail.nights.map(n => <p key={n.stayDate}>{n.stayDate} · {n.fulfillmentStatus} / {n.factStatus}</p>)}
      <h3 className="font-bold">다음 업무</h3>
      <p>{detail.nextAction ? [detail.nextAction.taskId,detail.nextAction.type,detail.nextAction.status,detail.nextAction.dueAt ?? '기한 미정'].join(' · ') : '미완료 업무 없음'}</p>
      <h3 className="font-bold">검증 근거</h3>
      {detail.evidence.map(e => <section key={e.evidenceId} className="rounded-lg bg-muted p-4">
        <strong>{e.evidenceId} · {e.sourceType}</strong><p>{e.sourceAuthenticated ? '출처 인증됨' : '출처 미인증'}</p>
        {e.assertions.map(a => <p key={a.stayDate}>{a.stayDate} · {a.reportedProvisionStatus} / {a.reviewStatus}</p>)}
        <details><summary>응답 원문</summary><pre className="whitespace-pre-wrap break-all">{e.originalContent}</pre></details>
      </section>)}
      <h3 className="font-bold">고객 신고</h3>
      {detail.customerReports.length === 0 && <p>접수된 신고 없음</p>}
      {detail.customerReports.map(r => <p key={r.reportId}>{r.reportId} · {r.issueType} · 현장 도착 자기 신고 {r.atHotel ? '예' : '아니요'} · {r.receivedAt}</p>)}
    </article>}
  </main>
}
