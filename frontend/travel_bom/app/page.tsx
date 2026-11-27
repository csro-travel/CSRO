"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { ConsolePage, useConsoleData } from "@/components/console/use-console-data";
import { getOverview } from "@/lib/console-api";
import { getCases } from "./cases/_api/cases";
import { CaseTable, OverviewBar } from "./cases/_components/case-list";

const load = (signal: AbortSignal) =>
  Promise.all([getCases(0, undefined, signal), getOverview(signal)]).then(([list, overview]) => ({ list, overview }));

/** Where a counselor starts: the open cases, urgent first, under one line on what was settled without a person. */
export default function Home() {
  const router = useRouter();
  const state = useConsoleData(load);
  const data = state.data;
  const more = data ? data.list.pagination.totalElements - data.list.items.length : 0;

  return (
    <ConsolePage title="홈" state={state}>
      {data && <>
        <OverviewBar overview={data.overview} />
        <CaseTable items={data.list.items} onOpen={caseId => router.push(`/cases?case=${encodeURIComponent(caseId)}`)}
          empty={<p role="status" className="text-muted-foreground">지금 처리할 대응 건이 없습니다. 새 건이 접수되면 이 화면에 바로 나타납니다.</p>} />
        {more > 0 && (
          <p className="text-right text-sm">
            <Link href="/cases" className="text-primary underline-offset-4 hover:underline">나머지 {more}건은 상담 대응에서 보기</Link>
          </p>
        )}
      </>}
    </ConsolePage>
  );
}
