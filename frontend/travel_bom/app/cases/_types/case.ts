export type CaseTask = {
  taskId: string;
  type: string;
  status: string;
  dueAt: string | null;
  /** Blocks closing while open. Absent on the list's `nextAction`. */
  required?: boolean;
};

export type CaseStatus = "OPEN" | "IN_PROGRESS" | "WAITING" | "CLOSED";

/** How the case came to exist. `UNKNOWN` when no creation record identifies it. */
export type CaseOrigin = { type: "RISK_RECHECK" | "CUSTOMER_REPORT" | "UNKNOWN" };

export type NightState = {
  stayDate: string;
  fulfillmentStatus: string;
  factStatus: string;
  evidenceId?: string | null;
};

export type CaseListItem = {
  caseId: string;
  bookingId: string;
  hotelName: string;
  priority: "URGENT" | "NORMAL";
  status: CaseStatus;
  summary: string;
  nextAction: CaseTask | null;
  checkInDate?: string;
  checkOutDate?: string;
  createdAt?: string;
  updatedAt?: string;
  unavailableStayDates?: string[];
  unknownStayDates?: string[];
  conflictStayDates?: string[];
  origin?: CaseOrigin;
};

export type CaseList = {
  items: CaseListItem[];
  pagination: {
    page: number;
    size: number;
    totalElements: number;
    hasNext: boolean;
  };
};

export type RiskSignal = {
  score: number;
  threshold: number;
  policy: string;
  modelVersion: string;
  featureSetVersion?: string;
  targetCheckInDate?: string;
  asOf?: string;
  assessedAt?: string;
  features?: Record<string, number | boolean>;
};

export type AnalysisRun = {
  kind: "RECHECK" | "HOTEL_REPLY_ANALYSIS" | "QUOTE_RANKING" | "COUNSELOR_ASSIST" | (string & {});
  status: string | null;
  at: string | null;
  version?: string | null;
  trigger?: string | null;
  failedAttempts?: number | null;
  errorCode?: string | null;
  manualRequired?: boolean | null;
  evidenceId?: string | null;
};

export type CompletedTask = {
  taskId: string;
  type: string;
  required?: boolean;
  completedAt: string;
  completionNote: string | null;
  completionEvidenceId: string | null;
};

export type EvidenceRecord = {
  evidenceId: string;
  sourceType: string;
  sourceAuthenticated: boolean;
  contentFormat?: "TEXT" | "STRUCTURED";
  originalContent: string | null;
  occurredAt?: string | null;
  receivedAt?: string;
  assertions: {
    stayDate: string;
    reportedProvisionStatus: string;
    reviewStatus: string;
  }[];
};

export type CaseDetail = {
  caseId: string;
  status: string;
  priority?: "URGENT" | "NORMAL";
  createdAt?: string;
  updatedAt?: string;
  booking: {
    bookingId: string;
    hotel: { name: string };
    supplierStatus: string;
    checkInDate?: string;
    checkOutDate?: string;
    adults?: number;
    rooms?: number;
    originalPrice?: { amount: number; currency: string };
  };
  nights: NightState[];
  evidence: EvidenceRecord[];
  nextAction: CaseTask | null;
  tasks: CaseTask[];
  customerReports: {
    reportId: string;
    issueType: string;
    atHotel: boolean;
    receivedAt: string;
  }[];
  origin?: CaseOrigin;
  risk?: RiskSignal | null;
  analysisRuns?: AnalysisRun[];
  closure?: { reason: string; closedAt: string } | null;
  completedTasks?: CompletedTask[];
};
