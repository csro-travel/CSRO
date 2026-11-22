'use client'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { Brand } from '@/components/console/sidebar'

type Task = { taskId: string; type: string; status: string; dueAt: string | null }
type Item = { caseId: string; bookingId: string; hotelName: string; priority: string; summary: string }
type Detail = {
  caseId: string; status: string; booking: { bookingId: string; hotel: {name: string}; supplierStatus: string }
  nights: {stayDate: string; fulfillmentStatus: string; factStatus: string}[]
  evidence: {evidenceId: string; sourceType: string; sourceAuthenticated: boolean; originalContent: string;
    assertions: {stayDate: string; reportedProvisionStatus: string; reviewStatus: string}[]}[]
  nextAction: Task | null
  customerReports: {reportId: string; issueType: string; atHotel: boolean; receivedAt: string}[]
}

async function read<T>(path: string, signal: AbortSignal): Promise<T> {
  const response = await fetch(path, {headers: {'X-Demo-Read': '1'}, cache: 'no-store', signal})
  if (!response.ok) throw Error('조회 실패 (HTTP ' + response.status + ')')
  return response.json()
}

export default function Cases() {
  const [items, setItems] = useState<Item[]>()
  const [selected, select] = useState<string>()
  const [detail, show] = useState<Detail>()
  const [error, fail] = useState('')
  useEffect(() => {
    const request = new AbortController()
    const path = selected ? '/demo/cases/' + encodeURIComponent(selected) : '/demo/cases'
    const load = async () => {
      try {
        if (selected) {
          const data = await read<Detail>(path, request.signal)
          if (!request.signal.aborted) show(data)
        } else {
          const data = await read<{items: Item[]}>(path, request.signal)
          if (!request.signal.aborted) setItems(data.items)
        }
      } catch (cause) {
        if (!request.signal.aborted) fail(cause instanceof Error ? cause.message : '조회 실패')
      }
    }
    void load()
    return () => request.abort()
  }, [selected])

  return <main className="mx-auto max-w-5xl space-y-6 px-6 py-10">
    <Brand />
    <header className="flex items-center justify-between"><h1 className="text-3xl font-bold">상담 사건</h1><Link href="/">대체 숙소 탐색 시연</Link></header>
    {error && <p role="alert">{error}</p>}
    {!selected && <section className="space-y-3">{items?.map(item =>
      <button key={item.caseId} onClick={() => { fail(''); show(undefined); select(item.caseId) }}
        className="block w-full rounded-xl border p-4 text-left hover:bg-muted">
        <strong>{item.caseId} · {item.hotelName}</strong><p>{item.bookingId} · {item.priority} · {item.summary}</p>
      </button>)}{items?.length === 0 && <p>활성 상담 사건이 없습니다.</p>}</section>}
    {selected && <button onClick={() => { select(undefined); setItems(undefined); show(undefined); fail('') }}>← 사건 목록</button>}
    {!error && ((!selected && !items) || (selected && !detail)) && <p role="status">사건 조회 중…</p>}
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
