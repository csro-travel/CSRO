type CaseChatOfferItem = {
  quoteId: string;
  displayOrder: number;
  propertyName: string;
  distanceMeters: number;
  checkInDate: string;
  checkOutDate: string;
  adults: number;
  rooms: number;
  totalAmount: number;
  currency: "KRW";
  priceDifference: number | null;
  checkedAt: string;
  expiresAt: string;
};

export type CaseChatMessage = {
  messageId: string;
  senderType: "CUSTOMER" | "COUNSELOR" | "SYSTEM";
  messageType: "TEXT" | "OFFER" | "SYSTEM";
  body: string | null;
  sentAt: string;
  offer: {
    offerId: string;
    status: "ACTIVE" | "SUPERSEDED";
    items: CaseChatOfferItem[];
  } | null;
};

export type CaseMessageList = {
  caseId: string;
  items: CaseChatMessage[];
  pagination: {
    hasMoreBefore: boolean;
    nextBeforeMessageId: string | null;
  };
};
