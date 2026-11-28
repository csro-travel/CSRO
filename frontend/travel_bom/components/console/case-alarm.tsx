"use client";

import { useEffect, useRef, useState } from "react";
import { usePathname } from "next/navigation";
import { BellRing, X } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { getCases } from "@/app/cases/_api/cases";
import type { CaseListItem } from "@/app/cases/_types/case";

const REFRESH_MS = 10000;
const SEEN_KEY = "csro.alarm.seen-cases.v1";

function readSeen(): Set<string> | null {
  try {
    const value: unknown = JSON.parse(sessionStorage.getItem(SEEN_KEY) ?? "null");
    return Array.isArray(value) ? new Set(value.filter((id): id is string => typeof id === "string")) : null;
  } catch { return null; }
}

function writeSeen(ids: Set<string>) {
  try { sessionStorage.setItem(SEEN_KEY, JSON.stringify([...ids])); } catch { /* Without storage the alarm only repeats after a reload. */ }
}

/**
 * Urgent cases announced on every counselor screen, starting from home. The first load of a
 * browser session announces the urgent cases already waiting; after that only new arrivals.
 */
export function CaseAlarm() {
  const pathname = usePathname();
  const [alerts, setAlerts] = useState<CaseListItem[]>([]);
  const seen = useRef<Set<string> | null>(null);
  const counselor = !pathname.startsWith("/customer/");

  useEffect(() => {
    if (!counselor) return;
    const controller = new AbortController();
    const check = async () => {
      if (document.visibilityState !== "visible") return;
      try {
        const { items } = await getCases(0, undefined, controller.signal);
        seen.current ??= readSeen() ?? new Set();
        const arrivals = items.filter(item => item.priority === "URGENT" && !seen.current!.has(item.caseId));
        if (!arrivals.length) return;
        for (const item of items) seen.current.add(item.caseId);
        writeSeen(seen.current);
        setAlerts(current => [...arrivals, ...current.filter(old => !arrivals.some(item => item.caseId === old.caseId))]);
      } catch { /* The screens report load errors themselves; the alarm stays quiet and retries. */ }
    };
    void check();
    const timer = window.setInterval(() => void check(), REFRESH_MS);
    return () => { window.clearInterval(timer); controller.abort(); };
  }, [counselor]);

  if (!counselor || alerts.length === 0) return null;
  const shown = alerts.slice(0, 3);
  return (
    <aside role="alert" aria-label="긴급 상담 알림"
      className="fixed top-3 right-3 z-50 w-[min(24rem,calc(100vw-1.5rem))] overflow-hidden rounded-lg border border-destructive/40 bg-card shadow-lg">
      <div className="flex items-center gap-2 border-b bg-destructive/8 px-3.5 py-2.5">
        <BellRing className="size-4 text-destructive" aria-hidden="true" />
        <p className="min-w-0 flex-1 text-sm font-bold text-destructive">긴급 상담 {alerts.length}건이 접수됐습니다</p>
        <Button type="button" variant="ghost" size="icon-sm" aria-label="알림 닫기" title="닫기" onClick={() => setAlerts([])}>
          <X aria-hidden="true" />
        </Button>
      </div>
      <ul className="divide-y">
        {shown.map(item => (
          <li key={item.caseId} className="flex items-center gap-3 px-3.5 py-2.5">
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-semibold">{item.hotelName}</p>
              <p className="num text-xs text-muted-foreground">{item.caseId} · {item.bookingId}</p>
            </div>
            {/* A full navigation: the case screen reads the open case from the URL when it loads. */}
            <a href={`/cases?case=${encodeURIComponent(item.caseId)}`} className={buttonVariants({ size: "sm" })}>열기</a>
          </li>
        ))}
      </ul>
      {alerts.length > shown.length && (
        <a href="/cases" className="block border-t px-3.5 py-2 text-center text-sm font-medium text-primary hover:underline">
          나머지 {alerts.length - shown.length}건은 상담 대응에서 보기
        </a>
      )}
    </aside>
  );
}
