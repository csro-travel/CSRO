'use client'

import { useEffect, useRef, useState } from 'react'
import {
  alternativeStays,
  fetchBookingCase,
  formatWon,
  type AlternativeStay,
  type BookingCase,
} from '@/lib/alternative-stays'
import { CaseHeader } from './case-header'
import { SearchStatus } from './search-status'
import { ComparisonResults } from './comparison-results'
import { HandoffBar } from './handoff-bar'



type AlternativeSearchPanelProps = {
  caseId?: string
  bookingId?: string
}

// const stays = alternativeStays ?? []

const AI_SEARCH_API_URL =
  process.env.NEXT_PUBLIC_AI_SEARCH_API_URL ?? 'http://127.0.0.1:8000'


type SearchCandidateApi = {
  listing_id: string
  name: string
  listing_url: string | null
  distance_km: number
  total_price_krw: number
  checked_at: string
  unconfirmed_items: string[]
}

type SearchResponseApi = {
  policy: {
    check_in_date: string
    check_out_date: string
    nights: number
  }
  searched_radius_km: number
  top_recommendations: SearchCandidateApi[]
}

function toAlternativeStay(
  candidate: SearchCandidateApi,
  policy: SearchResponseApi['policy'],
): AlternativeStay {
  return {
    id: candidate.listing_id,
    name: candidate.name,
    image: '/placeholder.svg',
    stay: `${policy.check_in_date} ~ ${policy.check_out_date} · ${policy.nights}박`,
    distanceKm: candidate.distance_km,
    totalPrice: candidate.total_price_krw,
    availability:
      candidate.unconfirmed_items.length > 0
        ? '상담사 확인 필요'
        : '확인 완료',
    checkedAt: new Date(candidate.checked_at).toLocaleString('ko-KR'),
  }
}


function createInitialCase(caseId?: string, bookingId?: string): BookingCase {
  const resolvedBookingId = bookingId || 'YB-001'
  return {
    caseId: caseId || `BOOKING-${resolvedBookingId}`,
    reservationNo: resolvedBookingId,
    hotel: '숙소 정보 불러오는 중',
    period: '2026.11.10 ~ 2026.11.11',
    checkInDate: '2026-11-10',
    checkOutDate: '2026-11-11',
    nights: 1,
    guests: '성인 2명',
    adults: 2,
    nightlyPrice: 0,
    bookingStatus: 'LOADING',
  }
}

export function AlternativeSearchPanel({
  caseId,
  bookingId,
}: AlternativeSearchPanelProps) {
  const targetBookingId = bookingId || 'YB-001'

  const [stays, setStays] = useState<AlternativeStay[]>([])
  const [bookingCase, setBookingCase] = useState<BookingCase>(() =>
    createInitialCase(caseId, bookingId),
  )
  const [loadError, setLoadError] = useState<string | null>(null)
  const [searching, setSearching] = useState(false)
  const [expanded, setExpanded] = useState(true)
  const [selectedId, setSelectedId] = useState<string>(() => stays[0]?.id ?? '')
  const [sent, setSent] = useState(false)
  const [updatedAt, setUpdatedAt] = useState('2026.11.09 10:35')
  const [searchError, setSearchError] = useState<string | null>(null)
  const [searchResult, setSearchResult] = useState<unknown>(null)


  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    let active = true
    setBookingCase(createInitialCase(caseId, targetBookingId))
    setLoadError(null)

    fetchBookingCase(targetBookingId)
      .then((data) => {
        if (!active) return
        setBookingCase({
          ...data,
          caseId: caseId || data.caseId,
        })
      })
      .catch((err: unknown) => {
        if (!active) return
        setLoadError(
          err instanceof Error ? err.message : '예약 정보를 불러오지 못했습니다.',
        )
      })

    return () => {
      active = false
    }
  }, [caseId, targetBookingId])

  const summary = [
    { label: '비교 대상', value: `${stays.length}개 숙소` },
    { label: '대체 일정', value: '11.10 ~ 11.11 · 1박' },
    { label: '검색 범위', value: '직선거리 3km' },
    {
      label: '총액 조건',
      value: `${formatWon(bookingCase.nightlyPrice * 2)} 이하`,
    },
  ]

  const runSearch = async (): Promise<void> => {
    setSearching(true)
    setSent(false)
    setSearchError(null)

    try {
      const payload = {
        caseId: bookingCase.caseId,
        bookingId: bookingCase.reservationNo,
        hotelName: bookingCase.hotel,
        nightlyPrice: bookingCase.nightlyPrice,
        nights: bookingCase.nights,
        adults: bookingCase.adults,
        regionHint: '제주',
        checkInDate: bookingCase.checkInDate,
        checkOutDate: bookingCase.checkOutDate,
      }

      const response = await fetch(
        `${AI_SEARCH_API_URL}/api/v1/alternative-search/auto-search`,
        {
          method: 'POST',
          headers: {
            Accept: 'application/json',
            'Content-Type': 'application/json',
          },
          body: JSON.stringify(payload),
        },
      )

      const result = (await response.json()) as SearchResponseApi

      if (!response.ok) {
          throw new Error(`대체 숙소 검색 실패 (HTTP ${response.status})`)
      }

      const mappedStays = result.top_recommendations.map((candidate) =>
          toAlternativeStay(candidate, result.policy),
      )

      setStays(mappedStays)
      setSelectedId(mappedStays[0]?.id ?? '')
      setExpanded(true)

      setSearchResult(result)

      const now = new Date()
      const pad = (n: number) => String(n).padStart(2, '0')
      setUpdatedAt(
        `${now.getFullYear()}.${pad(now.getMonth() + 1)}.${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`,
      )
      setExpanded(true)

    } catch (error) {
      setSearchError(
        error instanceof Error
          ? error.message
          : '대체 숙소 검색 중 오류가 발생했습니다.',
      )
    } finally {
      setSearching(false)
    }
  }


  const selectStay = (id: string) => {
    setSelectedId(id)
    setSent(false)
  }

  const activeId = stays.some((stay) => stay.id === selectedId)
    ? selectedId
    : (stays[0]?.id ?? '')
  const selected = stays.find((stay) => stay.id === activeId) ?? stays[0]

  return (
    <div className="flex flex-col gap-6">
      {loadError && (
        <p className="text-sm text-red-600" role="alert">
          {loadError}
        </p>
      )}
      <CaseHeader
        fetchBookingCase={bookingCase}
        searching={searching}
        onSearch={runSearch}
      />
      <SearchStatus
        searching={searching}
        candidateCount={stays.length}
        updatedAt={updatedAt}
        expanded={expanded}
        onToggle={() => setExpanded((value) => !value)}
      />
      {expanded && (
        <ComparisonResults
          stays={stays}
          summary={summary}
          selectedId={activeId}
          onSelect={selectStay}
          footer={
            selected ? (
              <HandoffBar
                selectedName={selected.name}
                sent={sent}
                onSend={() => setSent(true)}
              />
            ) : null
          }
        />
      )}
    </div>
  )
}