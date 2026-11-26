"use client";

import { Tabs } from "@base-ui/react/tabs";
import { ArrowLeft, BedDouble, FileText } from "lucide-react";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { CaseQuotesPanel } from "./case-quotes-panel";

const tabClassName =
  "inline-flex h-8 shrink-0 items-center justify-center gap-1.5 whitespace-nowrap rounded-md px-3 text-sm font-medium text-muted-foreground outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/30 data-[active]:bg-accent data-[active]:text-accent-foreground";

/**
 * One screen, no page scroll on desktop: case information scrolls on the left,
 * the conversation keeps its own scroll and composer on the right.
 */
export function CaseDetailTabs({ title, badges, caseId, closed, children, chat, onBack, onOfferSent }: {
  title: string;
  badges?: ReactNode;
  caseId: string;
  closed: boolean;
  children: ReactNode;
  chat: ReactNode;
  onBack: () => void;
  onOfferSent?: () => void;
}) {
  return (
    <Tabs.Root defaultValue="case" className="flex min-w-0 flex-col gap-3 lg:h-full lg:min-h-0">
      <header className="flex shrink-0 flex-wrap items-center gap-x-3 gap-y-2">
        <Button type="button" variant="ghost" size="icon" onClick={onBack} aria-label="상담 대응 목록으로 돌아가기" title="목록으로">
          <ArrowLeft aria-hidden="true" />
        </Button>
        <h1 className="min-w-0 text-xl font-bold wrap-anywhere">{title}</h1>
        {badges}
        <Tabs.List aria-label="대응 건 상세" className="ml-auto inline-flex shrink-0 gap-1 rounded-lg border bg-card p-1">
          <Tabs.Tab value="case" className={tabClassName}>
            <FileText aria-hidden="true" className="size-4 shrink-0" />
            대응 건
          </Tabs.Tab>
          <Tabs.Tab value="alternatives" className={tabClassName}>
            <BedDouble aria-hidden="true" className="size-4 shrink-0" />
            대체 숙소
          </Tabs.Tab>
        </Tabs.List>
      </header>

      <div className="grid min-w-0 gap-4 lg:min-h-0 lg:flex-1 lg:grid-cols-[minmax(0,1.15fr)_minmax(24rem,0.85fr)]">
        <div className="min-w-0 lg:min-h-0 lg:overflow-y-auto lg:overscroll-contain lg:pr-1 [scrollbar-width:thin]">
          <Tabs.Panel value="case" keepMounted className="space-y-3 pb-1 outline-none">
            {children}
          </Tabs.Panel>
          <Tabs.Panel value="alternatives" className="pb-1 outline-none">
            <CaseQuotesPanel key={caseId} caseId={caseId} closed={closed} onOfferSent={onOfferSent} />
          </Tabs.Panel>
        </div>
        <div className="h-[34rem] min-w-0 lg:h-auto lg:min-h-0">{chat}</div>
      </div>
    </Tabs.Root>
  );
}
