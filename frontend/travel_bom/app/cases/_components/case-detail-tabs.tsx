"use client";

import { Tabs } from "@base-ui/react/tabs";
import { BedDouble, FileText } from "lucide-react";
import type { ReactNode } from "react";
import { CaseQuotesPanel } from "./case-quotes-panel";

const tabClassName =
  "inline-flex min-h-10 shrink-0 items-center justify-center gap-2 whitespace-nowrap rounded-lg px-3 text-sm font-medium text-muted-foreground hover:bg-muted focus-visible:outline-2 focus-visible:outline-ring data-[active]:bg-accent data-[active]:text-accent-foreground sm:px-4";

export function CaseDetailTabs({
  title,
  caseId,
  closed,
  bookingId,
  children,
  chat,
}: {
  title: string;
  caseId: string;
  closed: boolean;
  bookingId: string;
  children: ReactNode;
  chat: ReactNode;
}) {
  return (
    <Tabs.Root defaultValue="case" className="min-w-0 space-y-4">
      <header className="flex flex-col items-start gap-3 lg:flex-row lg:items-center lg:gap-5">
        <h2 className="min-w-0 text-2xl font-bold wrap-anywhere">{title}</h2>
        <Tabs.List aria-label="사건 상세" className="inline-flex max-w-full shrink-0 flex-wrap gap-1 rounded-xl border bg-card p-1">
          <Tabs.Tab value="case" className={tabClassName}>
            <FileText aria-hidden="true" className="size-4 shrink-0" />
            사건
          </Tabs.Tab>
          <Tabs.Tab value="alternatives" className={tabClassName}>
            <BedDouble aria-hidden="true" className="size-4 shrink-0" />
            대체 숙소
          </Tabs.Tab>
        </Tabs.List>
      </header>

      <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <div className="min-w-0">
          <Tabs.Panel value="case" keepMounted>
            {children}
          </Tabs.Panel>
          <Tabs.Panel value="alternatives">
            <p className="mb-3 text-sm text-muted-foreground">예약번호 <span className="font-medium text-foreground">{bookingId}</span></p>
            <CaseQuotesPanel key={caseId} caseId={caseId} closed={closed} />
          </Tabs.Panel>
        </div>
        {chat}
      </div>
    </Tabs.Root>
  );
}
