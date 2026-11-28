"use client";

import { Tabs } from "@base-ui/react/tabs";
import { ArrowLeft, BedDouble, FileText } from "lucide-react";
import { useCallback, useState, type ReactNode } from "react";
import type { AlternativeStay } from "@/lib/alternative-stays";
import { Button } from "@/components/ui/button";
import { CaseQuotesPanel } from "./case-quotes-panel";
import { CaseAirbnbPanel } from "./case-airbnb-panel";
import type { CaseDetail } from "../_types/case";

// Browser-style tabs: the open tab is a white sheet joined to the content below, the other sits back in grey.
const tabClassName =
  "relative -mb-px inline-flex h-11 shrink-0 items-center justify-center gap-2 whitespace-nowrap rounded-t-xl border border-b-0 border-transparent bg-muted px-5 text-base font-semibold text-muted-foreground outline-none transition-colors hover:bg-secondary hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/30 data-[active]:border-border data-[active]:bg-card data-[active]:text-accent-foreground data-[active]:shadow-[inset_0_3px_0_var(--primary)]";

/**
 * One screen, no page scroll on desktop: case information scrolls on the left,
 * the conversation keeps its own scroll and composer on the right.
 */
export function CaseDetailTabs({ title, badges, top, caseId, closed, detail, children, chat, onBack, onOfferSent }: {
  title: string;
  badges?: ReactNode;
  /** Full-width strip between the title and the two columns, e.g. the process bar. */
  top?: ReactNode;
  caseId: string;
  closed: boolean;
  detail: CaseDetail;
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
      </header>

      {top && <div className="shrink-0">{top}</div>}

      <div className="grid min-w-0 gap-4 lg:min-h-0 lg:flex-1 lg:grid-cols-[minmax(0,1.15fr)_minmax(24rem,0.85fr)]">
        <div className="flex min-w-0 flex-col lg:min-h-0">
          <Tabs.List aria-label="상담 상세" className="flex shrink-0 justify-start gap-1 border-b border-border px-2">
            <Tabs.Tab value="case" className={tabClassName}>
              <FileText aria-hidden="true" className="size-[1.125rem] shrink-0" />
              상담 정보
            </Tabs.Tab>
            <Tabs.Tab value="alternatives" className={tabClassName}>
              <BedDouble aria-hidden="true" className="size-[1.125rem] shrink-0" />
              대체 숙소 추천
            </Tabs.Tab>
          </Tabs.List>
          {/* Positioned, so visually hidden labels inside (absolutely placed) scroll with this area instead of stretching the page. */}
          <div className="relative min-w-0 pt-3 lg:min-h-0 lg:flex-1 lg:overflow-y-auto lg:overscroll-contain lg:pr-1 [scrollbar-width:thin]">
            <Tabs.Panel value="case" keepMounted className="space-y-3 pb-1 outline-none">
              {children}
            </Tabs.Panel>
            <Tabs.Panel value="alternatives" className="space-y-4 pb-1 outline-none">
              <CaseAlternativePanels key={caseId} detail={detail} closed={closed} onOfferSent={onOfferSent} />
            </Tabs.Panel>
          </div>
        </div>
        <div className="h-[34rem] min-w-0 lg:h-auto lg:min-h-0">{chat}</div>
      </div>
    </Tabs.Root>
  );
}

function CaseAlternativePanels({ detail, closed, onOfferSent }: {
  detail: CaseDetail;
  closed: boolean;
  onOfferSent?: () => void;
}) {
  const [airbnbSelection, setAirbnbSelection] = useState<{ stays: AlternativeStay[]; sent: boolean }>({ stays: [], sent: false });
  const updateAirbnbSelection = useCallback((stays: AlternativeStay[], sent: boolean) => {
    setAirbnbSelection({ stays, sent });
  }, []);
  return (
    <>
      <CaseAirbnbPanel detail={detail} closed={closed} onSelectionChange={updateAirbnbSelection} />
      <CaseQuotesPanel caseId={detail.caseId} closed={closed} onOfferSent={onOfferSent}
        airbnbCandidates={airbnbSelection.stays} airbnbSelectionSent={airbnbSelection.sent} />
    </>
  );
}
