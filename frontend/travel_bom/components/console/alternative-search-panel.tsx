'use client'

import { useRef, useState } from 'react'
import { alternativeStays, bookingCase, formatWon } from '@/lib/alternative-stays'
import { CaseHeader } from './case-header'
import { SearchStatus } from './search-status'
import { ComparisonResults } from './comparison-results'
import { HandoffBar } from './handoff-bar'

const summary = [
  { label: '비교 대상', value: `${alternativeStays.length}개 숙소` },
  { label: '대체 일정', value: '11.10 ~ 11.11 · 1박' },
  { label: '검색 범위', value: '직선거리 3km' },
  { label: '총액 조건', value: `${formatWon(bookingCase.nightlyPrice * 2)} 이하` },
]

export function AlternativeSearchPanel() {
  const [searching, setSearching] = useState(false)
  const [expanded, setExpanded] = useState(true)
  const [selectedId, setSelectedId] = useState(alternativeStays[0].id)
  const [sent, setSent] = useState(false)
  const [updatedAt, setUpdatedAt] = useState('2026.11.09 10:35')
  const timer = useRef<ReturnType<typeof setTimeout>>(null)

  const runSearch = () => {
    setSearching(true)
    setSent(false)
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => {
      const now = new Date()
      const pad = (n: number) => String(n).padStart(2, '0')
      setUpdatedAt(
        `${now.getFullYear()}.${pad(now.getMonth() + 1)}.${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`,
      )
      setSearching(false)
      setExpanded(true)
    }, 1200)
  }

  const selectStay = (id: string) => {
    setSelectedId(id)
    setSent(false)
  }

  const selected = alternativeStays.find((stay) => stay.id === selectedId) ?? alternativeStays[0]

  return (
    <div className="flex flex-col gap-6">
      <CaseHeader bookingCase={bookingCase} searching={searching} onSearch={runSearch} />
      <SearchStatus
        searching={searching}
        candidateCount={alternativeStays.length}
        updatedAt={updatedAt}
        expanded={expanded}
        onToggle={() => setExpanded((value) => !value)}
      />
      {expanded && (
        <ComparisonResults
          stays={alternativeStays}
          summary={summary}
          selectedId={selectedId}
          onSelect={selectStay}
          footer={
            <HandoffBar selectedName={selected.name} sent={sent} onSend={() => setSent(true)} />
          }
        />
      )}
    </div>
  )
}
