export type BookingCase = {
  caseId: string
  reservationNo: string
  hotel: string
  period: string
  nights: number
  guests: string
  nightlyPrice: number
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

export const bookingCase: BookingCase = {
  caseId: 'CASE-001',
  reservationNo: 'YB-241110-8792',
  hotel: '제주 오션뷰 호텔',
  period: '2026.11.10 ~ 2026.11.13',
  nights: 3,
  guests: '성인 2명',
  nightlyPrice: 168000,
}

export const alternativeStays: AlternativeStay[] = [
  {
    id: 'jeju-city',
    name: '제주 시티 호텔',
    image: '/hotels/jeju-city.png',
    stay: '2026.11.10 ~ 11.11 · 성인 2명',
    distanceKm: 0.8,
    totalPrice: 172000,
    availability: '예약 가능 조회',
    checkedAt: '11.09 10:34 기준',
  },
  {
    id: 'jeju-hills',
    name: '제주 힐스 호텔',
    image: '/hotels/jeju-hills.png',
    stay: '2026.11.10 ~ 11.11 · 성인 2명',
    distanceKm: 1.4,
    totalPrice: 188000,
    availability: '예약 가능 조회',
    checkedAt: '11.09 10:35 기준',
  },
  {
    id: 'jeju-coast',
    name: '제주 코스트 호텔',
    image: '/hotels/jeju-coast.png',
    stay: '2026.11.10 ~ 11.11 · 성인 2명',
    distanceKm: 2.6,
    totalPrice: 220000,
    availability: '예약 가능 조회',
    checkedAt: '11.09 10:35 기준',
  },
]

export const formatWon = (value: number) => `${value.toLocaleString('ko-KR')}원`
