import type { BadgeTone } from "@/components/ui/badge";

/** One vocabulary for every counselor and customer screen. */
export const caseStatusLabels: Record<string, { label: string; tone: BadgeTone }> = {
  OPEN: { label: "접수", tone: "brand" },
  IN_PROGRESS: { label: "처리 중", tone: "info" },
  WAITING: { label: "확인 대기", tone: "warning" },
  CLOSED: { label: "종료", tone: "neutral" },
};

export const taskLabels = new Map([
  ["CONTACT_CUSTOMER", "고객 안내"],
  ["VERIFY_HOTEL", "호텔 직접 확인"],
  ["CHECKIN_CONFIRM", "대체 숙소 입실 확인"],
  ["ORIGINAL_FOLLOWUP", "원예약 후속 처리"],
]);

export const supplierStatusLabels = new Map([
  ["CONFIRMED", "예약 확정"], ["CANCELLED", "예약 취소"], ["CHANGED", "예약 변경"], ["UNKNOWN", "예약 상태 미확인"],
]);

export const provisionLabels = new Map([
  ["AVAILABLE", "제공 가능"], ["UNAVAILABLE", "제공 불가"], ["UNKNOWN", "미확인"],
]);

export const evidenceSourceLabels = new Map([
  ["HOTEL_API", "호텔 회신"], ["HOTEL_EVENT", "호텔 통보"], ["MANUAL_HOTEL", "상담사의 호텔 직접 확인"],
  ["SUPPLIER_API", "공급사 조회"], ["SUPPLIER_EVENT", "공급사 통보"],
]);

export const reviewLabels = new Map([
  ["ACCEPTED", "사실로 채택"], ["PENDING_REVIEW", "검토 대기"], ["REJECTED", "채택 안 함"],
]);

export const originLabels: Record<string, { label: string; detail: string; tone: BadgeTone }> = {
  RISK_RECHECK: { label: "사전 점검에서 발견", detail: "체크인 전 위험 점검이 고른 예약을 호텔에 재확인하다 문제가 확인됐습니다.", tone: "info" },
  CUSTOMER_REPORT: { label: "고객 현장 신고", detail: "고객이 직접 체크인 문제를 신고했습니다. 위험 점검을 기다리지 않고 접수됩니다.", tone: "danger" },
  UNKNOWN: { label: "발생 경로 기록 없음", detail: "이 대응 건이 만들어진 경로를 식별할 기록이 없습니다.", tone: "outline" },
};

export const jobStatusLabels: Record<string, { label: string; tone: BadgeTone }> = {
  PENDING: { label: "대기", tone: "neutral" },
  RUNNING: { label: "확인 중", tone: "info" },
  WAITING: { label: "재시도 대기", tone: "warning" },
  COMPLETED: { label: "확인 완료", tone: "success" },
  FAILED: { label: "자동 확인 실패", tone: "danger" },
};

export const triggerLabels = new Map([["SCHEDULED", "사전 위험 점검"], ["CUSTOMER_REPORT", "고객 신고"]]);

export const riskFeatureLabels: Record<string, string> = {
  pastFailureRate90d: "과거 90일 객실 이행 실패율",
  targetConfirmedBookingCount: "해당 날짜 확정 예약 수",
  bookingVolumeGrowth7d: "최근 7일 예약 증가율",
  reconfirmationOverdueRate7d: "최근 7일 재확인 기한 초과 비율",
  inventoryUnavailableRate7d: "최근 7일 재고 불가 관측 비율",
  isPeakDay: "성수기·혼잡일",
};

export const policyLabels = new Map([
  ["F1_BASELINE", "기본 정책"], ["RECALL_50_F1", "재현율 50% 정책"], ["MAX_OBSERVED_RECALL", "재현율 우선 정책"],
]);

const dayFormat = new Intl.DateTimeFormat("ko-KR", { month: "numeric", day: "numeric", weekday: "short", timeZone: "Asia/Seoul" });
const dateTimeFormat = new Intl.DateTimeFormat("ko-KR", {
  month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit", hourCycle: "h23", timeZone: "Asia/Seoul",
});

/** `2026-11-10` → `11. 10. (화)` */
export function formatDay(value: string) {
  const date = new Date(`${value}T00:00:00+09:00`);
  return Number.isNaN(date.getTime()) ? value : dayFormat.format(date);
}

export function formatDateTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : dateTimeFormat.format(date);
}

export function formatScore(value: number) {
  return value.toFixed(3);
}

export function formatFeature(name: string, value: number | boolean) {
  if (typeof value === "boolean") return value ? "예" : "아니오";
  if (name === "targetConfirmedBookingCount") return `${value}건`;
  if (name === "bookingVolumeGrowth7d") return `${value >= 0 ? "+" : ""}${Math.round(value * 100)}%`;
  return `${(value * 100).toFixed(1)}%`;
}

/** Elapsed time in the counselor's words: `방금`, `12분 전`, `3시간 전`, `2일 전`. */
export function formatElapsed(value: string, now = Date.now()) {
  const minutes = Math.floor((now - new Date(value).getTime()) / 60000);
  if (!Number.isFinite(minutes) || minutes < 1) return "방금";
  if (minutes < 60) return `${minutes}분 전`;
  if (minutes < 1440) return `${Math.floor(minutes / 60)}시간 전`;
  return `${Math.floor(minutes / 1440)}일 전`;
}

export type NightTone = "unavailable" | "available" | "conflict" | "unknown";

export function nightTone(night: { fulfillmentStatus: string; factStatus: string }): NightTone {
  if (night.factStatus === "CONFLICT") return "conflict";
  if (night.factStatus === "VERIFIED" && night.fulfillmentStatus === "UNAVAILABLE") return "unavailable";
  if (night.factStatus === "VERIFIED" && night.fulfillmentStatus === "AVAILABLE") return "available";
  return "unknown";
}

export const nightToneLabels: Record<NightTone, string> = {
  unavailable: "객실 제공 불가 (검증됨)",
  available: "객실 제공 가능 (검증됨)",
  conflict: "근거 상충 · 확인 필요",
  unknown: "아직 확인되지 않음",
};
