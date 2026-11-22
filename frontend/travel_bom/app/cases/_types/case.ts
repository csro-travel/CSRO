type CaseTask = {
  taskId: string;
  type: string;
  status: string;
  dueAt: string | null;
};

type CaseListItem = {
  caseId: string;
  bookingId: string;
  hotelName: string;
  priority: string;
  summary: string;
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

export type CaseDetail = {
  caseId: string;
  status: string;
  booking: {
    bookingId: string;
    hotel: { name: string };
    supplierStatus: string;
  };
  nights: { stayDate: string; fulfillmentStatus: string; factStatus: string }[];
  evidence: {
    evidenceId: string;
    sourceType: string;
    sourceAuthenticated: boolean;
    originalContent: string;
    assertions: {
      stayDate: string;
      reportedProvisionStatus: string;
      reviewStatus: string;
    }[];
  }[];
  nextAction: CaseTask | null;
  customerReports: {
    reportId: string;
    issueType: string;
    atHotel: boolean;
    receivedAt: string;
  }[];
};
