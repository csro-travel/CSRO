/** Read-only counselor console queries (`GET /api/v1/console/*`), reached through the isolated demo proxy. */

export type NightView = { stayDate: string; fulfillmentStatus: string; factStatus: string };

export type ConsoleOverview = {
  cases: { open: number; inProgress: number; waiting: number; closed: number; urgentActive: number };
  autoChecks: {
    recheckJobs: number; completed: number; failed: number; pending: number;
    bookingsChecked: number; bookingsAllAvailable: number; bookingsWithUnavailable: number; casesOpenedByRecheck: number;
  };
  risk: { assessments: number; selectedHotelDates: number };
  analyses: { hotelReply: number; quoteRanking: number };
  bookings: { total: number };
};

export type RecheckJob = {
  jobCode: string; trigger: string; status: string; bookingId: string; hotelName: string;
  checkInDate: string; checkOutDate: string; caseId: string | null;
  riskScore: number | null; threshold: number | null; policy: string | null;
  failedAttempts: number; errorCode: string | null; manualRequired: boolean;
  createdAt: string; finishedAt: string | null; nights: NightView[];
};

export type RiskAssessment = {
  assessmentId: number; hotelId: string; hotelName: string; targetCheckInDate: string; asOf: string;
  score: number; modelVersion: string; featureSetVersion: string; features: Record<string, number | boolean>;
  assessedAt: string; selected: boolean; selections: { policy: string; threshold: number; rawScore: number }[];
  recheckJobs: number; completedRechecks: number; casesLinked: number;
};

export type ConsoleEvidence = {
  evidenceId: string; bookingId: string; hotelName: string; sourceType: string; sourceAuthenticated: boolean;
  contentFormat: "TEXT" | "STRUCTURED"; originalContent: string | null; occurredAt: string | null; receivedAt: string;
  jobCode: string | null; trigger: string | null;
  assertions: { stayDate: string; reportedProvisionStatus: string; reviewStatus: string }[];
};

export type HotelReplyAnalysis = {
  analysisId: number | string; evidenceId: string; bookingId: string; hotelName: string; status: string;
  modelVersion: string; analyzedAt: string; originalMessage: string | null;
  claims: Record<string, unknown>[]; unresolvedStayDates: string[]; warnings: string[];
};

export type ConsoleBooking = {
  bookingId: string; hotelName: string; customerName: string; checkInDate: string; checkOutDate: string;
  adults: number; rooms: number; supplierStatus: string; originalPrice: { amount: number; currency: string };
  nights: NightView[]; activeCaseId: string | null; latestCaseId: string | null; recheckJobs: number;
};

async function read<T>(name: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`/demo/console/${name}`, { headers: { "X-Demo-Read": "1" }, cache: "no-store", signal });
  if (!response.ok) throw Error(`조회하지 못했습니다 (HTTP ${response.status})`);
  return response.json();
}

type Items<T> = { items: T[] };

export const getOverview = (signal?: AbortSignal) => read<ConsoleOverview>("overview", signal);
export const getRecheckJobs = (signal?: AbortSignal) => read<Items<RecheckJob>>("recheck-jobs", signal).then(body => body.items);
export const getRiskAssessments = (signal?: AbortSignal) => read<Items<RiskAssessment>>("risk-assessments", signal).then(body => body.items);
export const getEvidence = (signal?: AbortSignal) => read<Items<ConsoleEvidence>>("evidence", signal).then(body => body.items);
export const getHotelReplyAnalyses = (signal?: AbortSignal) => read<Items<HotelReplyAnalysis>>("hotel-reply-analyses", signal).then(body => body.items);
export const getBookings = (signal?: AbortSignal) => read<Items<ConsoleBooking>>("bookings", signal).then(body => body.items);
