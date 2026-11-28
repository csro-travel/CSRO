export type BookingCase = {
  caseId: string
  reservationNo: string
  hotel: string
  period: string
  checkInDate: string
  checkOutDate: string
  nights: number
  guests: string
  adults: number
  nightlyPrice: number
  bookingStatus: string
}


// API-001 (GET /api/v1/bookings/{bookingId}) 응답 타입
type BookingDetailApiResponse = {
  bookingId: string
  hotel: {
    hotelId: string
    name: string
  }
  stay: {
    checkInDate: string // 예: "2026-11-10"
    checkOutDate: string // 예: "2026-11-13"
    nights: number
    adults: number
  }
  bookingStatus: string
  originalPrice: {
    amount: number
    currency: string
  }
  support: {
    activeCaseId: string | null
    needsAttention: boolean
  }
}

const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080'

function formatPeriod(checkIn: string, checkOut: string): string {
  return `${checkIn.replaceAll('-', '.')} ~ ${checkOut.replaceAll('-', '.')}`
}

export async function fetchBookingCase(
  bookingId: string,
  signal?: AbortSignal,
): Promise<BookingCase> {
  const headers: HeadersInit = {
    Accept: 'application/json',
  }

  const response = await fetch(
    `${API_BASE_URL}/api/v1/bookings/${encodeURIComponent(bookingId)}`,
    {
      method: 'GET',
      headers,
      cache: 'no-store',
      signal,
    },
  )

  if (!response.ok) {
    throw new Error(`예약 정보를 불러오지 못했습니다. (상태 코드: ${response.status})`)
  }

  const data: BookingDetailApiResponse = await response.json()
  if (data.bookingId !== bookingId || !data.support.activeCaseId) {
    throw new Error('이 예약에 연결된 상담 사건을 확인할 수 없습니다.')
  }
  const nights = data.stay.nights
  if (!Number.isInteger(nights) || nights < 1 || data.originalPrice.currency !== 'KRW' ||
      !Number.isSafeInteger(data.originalPrice.amount) || data.originalPrice.amount < 0) {
    throw new Error('예약의 숙박 기간과 원화 금액을 확인할 수 없습니다.')
  }

  return {
    caseId: data.support.activeCaseId,
    reservationNo: data.bookingId,
    hotel: data.hotel.name,
    period: formatPeriod(data.stay.checkInDate, data.stay.checkOutDate),
    checkInDate: data.stay.checkInDate,
    checkOutDate: data.stay.checkOutDate,
    nights: data.stay.nights,
    guests: `성인 ${data.stay.adults}명`,
    adults: data.stay.adults,
    nightlyPrice: Math.round(data.originalPrice.amount / nights),
    bookingStatus: data.bookingStatus,
  }
}


export const formatWon = (value?: number | null) => {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    return '금액 미확인'
  }
  return `${value.toLocaleString('ko-KR')}원`
}

export type AlternativeStay = {
  id: string
  name: string
  stay: string
  distanceKm: number
  totalPrice: number | null
  availability: string
  checkedAt: string
  counselorChecks: string[]
  listingUrl: string | null
  score: number | null
  searchId: string | null
}

export const alternativeStays: AlternativeStay[] = []

