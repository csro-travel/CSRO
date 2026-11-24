export type QuoteSearchStatus = "PENDING" | "RUNNING" | "WAITING" | "COMPLETED" | "FAILED";

export type CaseQuote = {
  quoteId: string;
  propertyName: string;
  roomName: string;
  distanceMeters: number;
  totalAmount: number;
  currency: "KRW";
  priceDifference: number | null;
  checkedAt: string;
  expiresAt: string;
  expired?: boolean;
  reason: string | null;
};

export type CaseQuoteGroup = {
  coverageMode: "FULL_STAY" | "VERIFIED_FAILURE_ONLY";
  stay: {
    checkInDate: string;
    checkOutDate: string;
    adults: number;
    rooms: number;
  };
  remainingUnconfirmedStayDates: string[];
  rankingAvailable: boolean;
  quotes: CaseQuote[];
  warnings: string[];
};

export type CaseQuoteList = {
  caseId: string;
  effectiveAt: string | null;
  isSynthetic: true;
  clockMode?: "REAL_TIME" | "SYNTHETIC_DEMO";
  searchJob: {
    searchJobId: string;
    status: QuoteSearchStatus;
    errorCode: string | null;
  } | null;
  groups: CaseQuoteGroup[];
};

export type QuoteSearchAcceptance = {
  caseId: string;
  searchJobId: string;
  status: "PENDING";
};
