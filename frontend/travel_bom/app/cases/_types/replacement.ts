export type ReplacementPointer = { replacementBookingId: string; selectionId: string };
export type ReplacementStatus = "PROCESSING" | "RESULT_UNKNOWN" | "CONFIRMED" | "FAILED";
type Price = { totalAmount: number; currency: "KRW" };

export type ReplacementBooking = ReplacementPointer & {
  caseId: string;
  offerId: string;
  quoteId: string;
  status: ReplacementStatus;
  stay: { checkInDate: string; checkOutDate: string; adults: number; rooms: number };
  expectedPrice: Price;
  supplierBookingId: string | null;
  confirmedPrice: Price | null;
  confirmedAt: string | null;
  failureCode: string | null;
  currentPrice: Price | null;
  manualRequired: boolean;
  nextCheckAt: string | null;
  updatedAt: string;
  isSynthetic: true;
  effectiveAt: string;
  clockMode: "REAL_TIME" | "SYNTHETIC_DEMO";
};

export type ReplacementReceipt = ReplacementPointer & { caseId: string; acceptedAt: string };
export type ReplacementIntent = {
  version: 1;
  key: string;
  body: { selectionId: string };
  resolved?: ReplacementPointer;
};
