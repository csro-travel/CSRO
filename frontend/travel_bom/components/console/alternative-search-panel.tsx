'use client'

import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { fetchBookingCase, formatWon, type AlternativeStay, type BookingCase } from '@/lib/alternative-stays'
import { CaseHeader } from './case-header'
import { SearchStatus } from './search-status'
import { ComparisonResults } from './comparison-results'

const AI_SEARCH_API_URL = process.env.NEXT_PUBLIC_AI_SEARCH_API_URL ?? 'http://127.0.0.1:8000'
const COUNSELOR_DEMO_ORIGIN = 'http://127.0.0.1:3100'

type SearchCandidateApi = {
  listing_id: string
  name: string
  distance_km: number
  total_price_krw: number | null
  checked_at: string
  unconfirmed_items: string[]
  listing_url?: string | null
  total_score?: number
  counselor_checks?: string[]
  checks?: { full_stay_available?: 'confirmed' | 'unconfirmed' | 'failed' }
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
  search_notes?: string[]
}

function isStringList(value: unknown): value is string[] {
  return Array.isArray(value) && value.every(item => typeof item === 'string')
}

function validResponse(value: unknown, booking: BookingCase): value is SearchResponseApi {
  if (!value || typeof value !== 'object') return false
  const data = value as Partial<SearchResponseApi>
  return data.case_id === booking.caseId && data.booking_id === booking.reservationNo &&
    data.policy?.check_in_date === booking.checkInDate &&
    data.policy?.check_out_date === booking.checkOutDate && data.policy?.nights === booking.nights &&
    Number.isSafeInteger(data.policy?.preferred_max_amount) && data.policy.preferred_max_amount >= 0 &&
    typeof data.searched_radius_km === 'number' && Number.isFinite(data.searched_radius_km) && data.searched_radius_km > 0 &&
    (data.search_notes === undefined || isStringList(data.search_notes)) &&
    Array.isArray(data.top_recommendations) && data.top_recommendations.every(candidate =>
      candidate && typeof candidate.listing_id === 'string' && candidate.listing_id.length > 0 &&
      typeof candidate.name === 'string' && typeof candidate.distance_km === 'number' &&
      Number.isFinite(candidate.distance_km) && candidate.distance_km >= 0 &&
      (candidate.total_price_krw === null || (Number.isSafeInteger(candidate.total_price_krw) && candidate.total_price_krw >= 0)) &&
      typeof candidate.checked_at === 'string' && Number.isFinite(Date.parse(candidate.checked_at)) &&
      isStringList(candidate.unconfirmed_items) &&
      (candidate.counselor_checks === undefined || isStringList(candidate.counselor_checks)))
}

function safeListingUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && !url.username && !url.password ? url.href : null
  } catch {
    return null
  }
}

function toStay(candidate: SearchCandidateApi, policy: SearchResponseApi['policy'], searchId: string | null): AlternativeStay {
  return {
    id: candidate.listing_id, name: candidate.name,
    stay: `${policy.check_in_date} ~ ${policy.check_out_date} · ${policy.nights}박`,
    distanceKm: candidate.distance_km, totalPrice: candidate.total_price_krw,
    listingUrl: safeListingUrl(candidate.listing_url),
    searchId,
    score: typeof candidate.total_score === 'number' && Number.isFinite(candidate.total_score)
      ? candidate.total_score : null,
    availability: candidate.checks?.full_stay_available === 'confirmed' ? '확인'
      : candidate.checks?.full_stay_available === 'failed' ? '불충족' : '미확인',
    counselorChecks: [...new Set(candidate.counselor_checks?.length
      ? candidate.counselor_checks
      : candidate.unconfirmed_items.map(item => `${item} 확인`))],
    checkedAt: new Date(candidate.checked_at).toLocaleString('ko-KR'),
  }
}

function sameSelection(left: string[], right: string[]) {
  return left.length === right.length && left.every(id => right.includes(id))
}

export function AlternativeSearchPanel({ caseId, bookingId, caseBooking, closed = false, onSelectionChange }: {
  caseId?: string
  bookingId: string
  caseBooking?: BookingCase
  closed?: boolean
  onSelectionChange?: (stays: AlternativeStay[], sent: boolean) => void
}) {
  const [bookingCase, setBookingCase] = useState<BookingCase | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [searching, setSearching] = useState(false)
  const [restoring, setRestoring] = useState(false)
  const [restoreError, setRestoreError] = useState<string | null>(null)
  const [searchId, setSearchId] = useState<string | null>(null)
  const [expanded, setExpanded] = useState(true)
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [sentSelections, setSentSelections] = useState<string[][]>([])
  const [selectionSaving, setSelectionSaving] = useState(false)
  const [selectionError, setSelectionError] = useState<string | null>(null)
  const selectionController = useRef<AbortController | null>(null)
  const [searchError, setSearchError] = useState<string | null>(null)
  const [saveStatus, setSaveStatus] = useState<'saving' | 'saved' | 'failed' | null>(null)
  const [searchResult, setSearchResult] = useState<SearchResponseApi | null>(null)
  const [updatedAt, setUpdatedAt] = useState('조회 전')
  const searchController = useRef<AbortController | null>(null)
  const stays = searchResult?.top_recommendations.map(candidate => toStay(candidate, searchResult.policy, searchId)) ?? []

  useEffect(() => {
    const request = new AbortController()
    setBookingCase(null)
    setLoadError(null)
    setSearchResult(null)
    setSelectedIds([])
    setSentSelections([])
    setSelectionSaving(false)
    setSelectionError(null)
    onSelectionChange?.([], false)
    setSearchError(null)
    setSearching(false)
    setUpdatedAt('조회 전')
    setSaveStatus(null)
    setRestoreError(null)
    setSearchId(null)
    const shouldRestore = !!caseBooking && window.location.origin === COUNSELOR_DEMO_ORIGIN
    setRestoring(shouldRestore)
    if (caseBooking) {
      setBookingCase(caseBooking)
      if (shouldRestore) {
        void fetch(`/demo/cases/${encodeURIComponent(caseBooking.caseId)}/airbnb-demo-searches/latest`, {
          headers: { 'X-Demo-Read': '1' }, cache: 'no-store',
          signal: AbortSignal.any([request.signal, AbortSignal.timeout(7000)]),
        }).then(async response => {
          if (response.status === 204 || request.signal.aborted) return
          if (!response.ok) throw new Error()
          const snapshot: { searchId?: unknown; finishedAt?: unknown; result?: unknown;
            selectedListingIds?: unknown; sentSelections?: unknown } | null = await response.json()
          if (request.signal.aborted) return
          if (typeof snapshot?.searchId !== 'string' || typeof snapshot.finishedAt !== 'string' ||
              !Number.isFinite(Date.parse(snapshot.finishedAt)) || !validResponse(snapshot.result, caseBooking)) {
            throw new Error()
          }
          const { result, searchId: restoredSearchId } = snapshot
          const restored = isStringList(snapshot.selectedListingIds) ? snapshot.selectedListingIds : []
          const sent = Array.isArray(snapshot.sentSelections) ? snapshot.sentSelections.filter(isStringList) : []
          const restoredStays = result.top_recommendations
            .filter(candidate => restored.includes(candidate.listing_id))
            .map(candidate => toStay(candidate, result.policy, restoredSearchId))
          const ids = restoredStays.map(stay => stay.id)
          setSelectedIds(ids)
          setSentSelections(sent)
          onSelectionChange?.(restoredStays, sent.some(selection => sameSelection(selection, ids)))
          setSearchResult(snapshot.result)
          setSearchId(snapshot.searchId)
          setUpdatedAt(new Date(snapshot.finishedAt).toLocaleString('ko-KR'))
          setExpanded(true)
          setSaveStatus('saved')
        }).catch(() => {
          if (!request.signal.aborted) setRestoreError('저장한 Airbnb 검색 결과를 불러오지 못했습니다.')
        }).finally(() => {
          if (!request.signal.aborted) setRestoring(false)
        })
      }
    } else void fetchBookingCase(bookingId, request.signal).then(data => {
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
      selectionController.current?.abort()
      selectionController.current = null
    }
  }, [caseId, bookingId, caseBooking, onSelectionChange])

  async function runSearch() {
    if (closed || !bookingCase || restoring || searching || searchController.current || selectionController.current || bookingCase.reservationNo !== bookingId) return
    const request = new AbortController()
    searchController.current = request
    const timer = setTimeout(() => request.abort(), 130_000)
    setSearching(true)
    setSearchError(null)
    setRestoreError(null)
    setSelectionError(null)
    setSaveStatus(null)
    const parameters = {
      caseId: bookingCase.caseId, bookingId: bookingCase.reservationNo,
      hotelName: bookingCase.hotel, nightlyPrice: bookingCase.nightlyPrice,
      nights: bookingCase.nights, adults: bookingCase.adults,
      checkInDate: bookingCase.checkInDate, checkOutDate: bookingCase.checkOutDate,
    }
    try {
      const response = await fetch(`${AI_SEARCH_API_URL}/api/v1/alternative-search/auto-search`, {
        method: 'POST', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
        signal: request.signal,
        body: JSON.stringify(parameters),
      })
      const result: unknown = await response.json().catch(() => null)
      if (!response.ok) {
        const detail = result && typeof result === 'object' && 'detail' in result ? result.detail : null
        throw new Error(typeof detail === 'string' ? detail : `대체 숙소 검색 실패 (HTTP ${response.status})`)
      }
      if (!validResponse(result, bookingCase)) throw new Error('검색 결과의 예약·기간·금액을 확인할 수 없습니다.')
      if (request.signal.aborted) return
      let savedSearchId: string | null = null
      if (caseBooking && window.location.origin === COUNSELOR_DEMO_ORIGIN) {
        clearTimeout(timer)
        setSearching(false)
        setSaveStatus('saving')
        try {
          const requestId = crypto.randomUUID()
          const saved = await fetch(
            `/demo/cases/${encodeURIComponent(bookingCase.caseId)}/airbnb-demo-searches/${requestId}`,
            {
              method: 'PUT', headers: { 'Content-Type': 'application/json' },
              signal: AbortSignal.any([request.signal, AbortSignal.timeout(10000)]),
              body: JSON.stringify({ parameters, result }),
            },
          )
          if (!request.signal.aborted) {
            setSaveStatus(saved.ok ? 'saved' : 'failed')
            if (saved.ok) savedSearchId = requestId
          }
        } catch {
          if (!request.signal.aborted) setSaveStatus('failed')
        }
        if (!savedSearchId) return
      }
      if (request.signal.aborted) return
      setSearchId(savedSearchId)
      setSearchResult(result)
      setSelectedIds([])
      setSentSelections([])
      onSelectionChange?.([], false)
      setExpanded(true)
      setUpdatedAt(new Date().toLocaleString('ko-KR'))
    } catch (error) {
      // A component navigation abort has no result to display on the old case.
      if (searchController.current !== request) return
      setSearchError(request.signal.aborted ? '검색 응답을 기다리는 시간이 초과되었습니다. 다시 시도해 주세요.'
        : error instanceof Error ? error.message : '대체 숙소 검색 중 오류가 발생했습니다.')
      if (!searchResult) setUpdatedAt('조회 실패')
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
  async function toggleSelection(id: string) {
    if (closed || searchController.current || selectionController.current ||
        (!selectedIds.includes(id) && selectedIds.length >= 3)) return
    const next = selectedIds.includes(id) ? selectedIds.filter(value => value !== id) : [...selectedIds, id]
    const applySelection = () => {
      setSelectedIds(next)
      onSelectionChange?.(stays.filter(stay => next.includes(stay.id)),
        sentSelections.some(selection => sameSelection(selection, next)))
    }
    if (!caseBooking || !searchId) {
      applySelection()
      return
    }
    const request = new AbortController()
    selectionController.current = request
    setSelectionSaving(true)
    setSelectionError(null)
    try {
      const response = await fetch(
        `/demo/cases/${encodeURIComponent(caseBooking.caseId)}/airbnb-demo-searches/${searchId}/selection`, {
          method: 'PUT', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ listingIds: next }),
          signal: AbortSignal.any([request.signal, AbortSignal.timeout(7000)]),
        },
      )
      if (response.status !== 204) throw new Error()
      if (!request.signal.aborted) applySelection()
    } catch {
      if (!request.signal.aborted) setSelectionError('숙소 선택 저장을 확인하지 못했습니다. 다시 선택하거나 화면을 새로고침해 주세요.')
    } finally {
      if (selectionController.current === request) {
        selectionController.current = null
        setSelectionSaving(false)
      }
    }
  }

  return (
    <div className="flex flex-col gap-6">
      {loadError && <p className="text-sm text-red-600" role="alert">{loadError}</p>}
      {bookingCase ? <CaseHeader bookingCase={bookingCase} searching={searching} disabled={closed || restoring || selectionSaving || saveStatus === 'saving'} onSearch={runSearch} /> : (
        <section className="space-y-3 rounded-2xl border p-6">
          {!loadError && <p role="status">예약 정보를 불러오는 중…</p>}
          <Button type="button" size="lg" disabled>대체 숙소 자동 탐색</Button>
        </section>
      )}
      {restoring && <p role="status">저장한 Airbnb 검색 결과를 불러오는 중입니다.</p>}
      {restoreError && <p className="text-sm text-destructive" role="alert">{restoreError}</p>}
      {selectionSaving && <p role="status">숙소 선택을 저장하는 중입니다.</p>}
      {selectionError && <p role="alert" className="text-destructive">{selectionError}</p>}
      {searchError && <p className="text-sm text-red-600" role="alert">{searchError}</p>}
      {saveStatus === 'saving' && <p role="status">Airbnb 검색 결과를 저장하는 중입니다.</p>}
      {saveStatus === 'saved' && <p role="status">Airbnb 검색 결과를 저장했습니다.</p>}
      {saveStatus === 'failed' && <p className="text-sm text-destructive" role="alert">Airbnb 검색 결과 저장에 실패했습니다.</p>}
      <SearchStatus state={searching ? 'loading' : searchError ? 'error' : searchResult ? 'success' : 'idle'}
        candidateCount={stays.length} updatedAt={updatedAt}
        expanded={expanded} onToggle={() => setExpanded(value => !value)} />
      {searchResult && stays.length > 0 && stays.length < 5 && (
        <p role="status" className="text-sm text-muted-foreground">
          이번 검색에서 추천할 수 있는 숙소는 {stays.length}곳입니다.
          {searchResult.search_notes?.length ? ` ${searchResult.search_notes.join(' ')}` : ''}
        </p>
      )}
      {searchResult && stays.length === 0 && <p role="status">검색 조건에 맞는 후보가 없습니다.</p>}
      {expanded && searchResult && <ComparisonResults stays={stays} summary={summary} selectedIds={selectedIds} selectionDisabled={closed || searching || selectionSaving || saveStatus === 'saving'}
        onToggleSelection={toggleSelection} />}
    </div>
  )
}
