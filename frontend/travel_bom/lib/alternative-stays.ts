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
  process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8081'
const DEMO_SUBJECT =
  process.env.NEXT_PUBLIC_DEMO_SUBJECT ?? 'demo-customer-001'

function formatPeriod(checkIn: string, checkOut: string): string {
  return `${checkIn.replaceAll('-', '.')} ~ ${checkOut.replaceAll('-', '.')}`
}

export async function fetchBookingCase(
  bookingId = 'YB-001',
): Promise<BookingCase> {
  const headers: HeadersInit = {
    Accept: 'application/json',
  }

  // 로컬 테스트용 헤더 주입 (실제 인증 연결 시 제거)
  if (DEMO_SUBJECT) {
    headers['X-Demo-Subject'] = DEMO_SUBJECT
  }

  const response = await fetch(
    `${API_BASE_URL}/api/v1/bookings/${encodeURIComponent(bookingId)}`,
    {
      method: 'GET',
      headers,
      cache: 'no-store',
    },
  )

  if (!response.ok) {
    throw new Error(`예약 정보를 불러오지 못했습니다. (상태 코드: ${response.status})`)
  }

  const data: BookingDetailApiResponse = await response.json()
  const nights = Math.max(data.stay.nights || 1, 1)

  return {
    caseId: data.support.activeCaseId ?? `BOOKING-${data.bookingId}`,
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
  if (typeof value !== 'number' || Number.isNaN(value)) {
    return '0원'
  }
  return `${value.toLocaleString('ko-KR')}원`
}

export type AlternativeStay = {
  id: string
  name: string
  image: string
  stay: string
  distanceKm: number
  totalPrice: number
  availability: string
  checkedAt: string
}

export const alternativeStays: AlternativeStay[] = []

