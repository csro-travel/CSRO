'use client'

import { useEffect, useRef, useState } from 'react'
import { fetchBookingCase, formatWon, type AlternativeStay, type BookingCase } from '@/lib/alternative-stays'
import { CaseHeader } from './case-header'
import { SearchStatus } from './search-status'
import { ComparisonResults } from './comparison-results'
import { HandoffBar } from './handoff-bar'

const AI_SEARCH_API_URL = process.env.NEXT_PUBLIC_AI_SEARCH_API_URL ?? 'http://127.0.0.1:8000'

type SearchCandidateApi = {
  listing_id: string
  name: string
  distance_km: number
  total_price_krw: number | null
  checked_at: string
  unconfirmed_items: string[]
}

type SearchResponseApi = {
  case_id: string
  booking_id: string
  policy: {
    check_in_date: string
    check_out_date: string
    nights: number
    preferred_max_amount: number
  }
  searched_radius_km: number
  top_recommendations: SearchCandidateApi[]
}

function validResponse(value: unknown, booking: BookingCase): value is SearchResponseApi {
  if (!value || typeof value !== 'object') return false
  const data = value as Partial<SearchResponseApi>
  return data.case_id === booking.caseId && data.booking_id === booking.reservationNo &&
    data.policy?.check_in_date === booking.checkInDate &&
    data.policy?.check_out_date === booking.checkOutDate && data.policy?.nights === booking.nights &&
    Number.isSafeInteger(data.policy?.preferred_max_amount) && data.policy.preferred_max_amount >= 0 &&
    typeof data.searched_radius_km === 'number' && Number.isFinite(data.searched_radius_km) && data.searched_radius_km > 0 &&
    Array.isArray(data.top_recommendations) && data.top_recommendations.every(candidate =>
      candidate && typeof candidate.listing_id === 'string' && candidate.listing_id.length > 0 &&
      typeof candidate.name === 'string' && typeof candidate.distance_km === 'number' &&
      Number.isFinite(candidate.distance_km) && candidate.distance_km >= 0 &&
      (candidate.total_price_krw === null || (Number.isSafeInteger(candidate.total_price_krw) && candidate.total_price_krw >= 0)) &&
      typeof candidate.checked_at === 'string' && Number.isFinite(Date.parse(candidate.checked_at)) &&
      Array.isArray(candidate.unconfirmed_items) && candidate.unconfirmed_items.every((item: unknown) => typeof item === 'string'))
}

function toStay(candidate: SearchCandidateApi, policy: SearchResponseApi['policy']): AlternativeStay {
  return {
    id: candidate.listing_id, name: candidate.name, image: '/placeholder.svg',
    stay: `${policy.check_in_date} ~ ${policy.check_out_date} · ${policy.nights}박`,
    distanceKm: candidate.distance_km, totalPrice: candidate.total_price_krw,
    availability: candidate.unconfirmed_items.length || candidate.total_price_krw === null
      ? '상담사 확인 필요' : '검색 조건 확인',
    checkedAt: new Date(candidate.checked_at).toLocaleString('ko-KR'),
  }
}

export function AlternativeSearchPanel({ caseId, bookingId }: { caseId?: string; bookingId: string }) {
  const [bookingCase, setBookingCase] = useState<BookingCase | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [searching, setSearching] = useState(false)
  const [expanded, setExpanded] = useState(true)
  const [selectedId, setSelectedId] = useState('')
  const [searchError, setSearchError] = useState<string | null>(null)
  const [searchResult, setSearchResult] = useState<SearchResponseApi | null>(null)
  const [updatedAt, setUpdatedAt] = useState('조회 전')
  const searchController = useRef<AbortController | null>(null)
  const stays = searchResult?.top_recommendations.map(candidate => toStay(candidate, searchResult.policy)) ?? []

  useEffect(() => {
    const request = new AbortController()
    setBookingCase(null)
    setLoadError(null)
    setSearchResult(null)
    setSelectedId('')
    setSearchError(null)
    setSearching(false)
    setUpdatedAt('조회 전')
    void fetchBookingCase(bookingId, request.signal).then(data => {
      if (request.signal.aborted) return
      if (caseId && data.caseId !== caseId) throw new Error('사건과 예약의 연결이 일치하지 않습니다.')
      setBookingCase(data)
    }).catch((error: unknown) => {
      if (!request.signal.aborted) setLoadError(error instanceof Error ? error.message : '예약 정보를 불러오지 못했습니다.')
    })
    return () => {
      request.abort()
      searchController.current?.abort()
      searchController.current = null
    }
  }, [caseId, bookingId])

  async function runSearch() {
    if (!bookingCase || searching || searchController.current || bookingCase.reservationNo !== bookingId) return
    const request = new AbortController()
    searchController.current = request
    const timer = setTimeout(() => request.abort(), 60000)
    setSearching(true)
    setSearchError(null)
    setSearchResult(null)
    setSelectedId('')
    setUpdatedAt('조회 중')
    try {
      const response = await fetch(`${AI_SEARCH_API_URL}/api/v1/alternative-search/auto-search`, {
        method: 'POST', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
        signal: request.signal,
        body: JSON.stringify({
          caseId: bookingCase.caseId, bookingId: bookingCase.reservationNo,
          hotelName: bookingCase.hotel, nightlyPrice: bookingCase.nightlyPrice,
          nights: bookingCase.nights, adults: bookingCase.adults,
          checkInDate: bookingCase.checkInDate, checkOutDate: bookingCase.checkOutDate,
        }),
      })
      const result: unknown = await response.json().catch(() => null)
      if (!response.ok) {
        const detail = result && typeof result === 'object' && 'detail' in result ? result.detail : null
        throw new Error(typeof detail === 'string' ? detail : `대체 숙소 검색 실패 (HTTP ${response.status})`)
      }
      if (!validResponse(result, bookingCase)) throw new Error('검색 결과의 예약·기간·금액을 확인할 수 없습니다.')
      if (request.signal.aborted) return
      setSearchResult(result)
      setSelectedId(result.top_recommendations[0]?.listing_id ?? '')
      setExpanded(true)
      setUpdatedAt(new Date().toLocaleString('ko-KR'))
    } catch (error) {
      // A component navigation abort has no result to display on the old case.
      if (searchController.current !== request) return
      setSearchError(request.signal.aborted ? '검색 응답을 기다리는 시간이 초과되었습니다. 다시 시도해 주세요.'
        : error instanceof Error ? error.message : '대체 숙소 검색 중 오류가 발생했습니다.')
      setUpdatedAt('조회 실패')
    } finally {
      clearTimeout(timer)
      if (searchController.current === request) {
        searchController.current = null
        setSearching(false)
      }
    }
  }

  const summary = [
    { label: '비교 대상', value: `${stays.length}개 숙소` },
    { label: '대체 일정', value: searchResult
      ? `${searchResult.policy.check_in_date} ~ ${searchResult.policy.check_out_date} · ${searchResult.policy.nights}박`
      : bookingCase ? `${bookingCase.period} · ${bookingCase.nights}박` : '예약 조회 필요' },
    { label: '검색 범위', value: searchResult ? `직선거리 ${searchResult.searched_radius_km}km` : '검색 후 표시' },
    { label: '검토 기준 총액', value: searchResult ? `${formatWon(searchResult.policy.preferred_max_amount)} 이하` : '검색 후 표시' },
  ]
  const selected = stays.find(stay => stay.id === selectedId)

  return (
    <div className="flex flex-col gap-6">
      {loadError && <p className="text-sm text-red-600" role="alert">{loadError}</p>}
      {bookingCase ? <CaseHeader bookingCase={bookingCase} searching={searching} onSearch={runSearch} /> : (
        <section className="space-y-3 rounded-2xl border p-6">
          {!loadError && <p role="status">예약 정보를 불러오는 중…</p>}
          <button type="button" disabled className="rounded-lg bg-muted px-6 py-3 text-muted-foreground">대체 숙소 자동 탐색</button>
        </section>
      )}
      {searchError && <p className="text-sm text-red-600" role="alert">{searchError}</p>}
      <SearchStatus searching={searching} candidateCount={stays.length} updatedAt={updatedAt}
        expanded={expanded} onToggle={() => setExpanded(value => !value)} />
      {searchResult && stays.length === 0 && <p role="status">검색 조건에 맞는 후보가 없습니다.</p>}
      {expanded && <ComparisonResults stays={stays} summary={summary} selectedId={selectedId}
        onSelect={setSelectedId} footer={selected ? <HandoffBar selectedName={selected.name} /> : null} />}
    </div>
  )
}
