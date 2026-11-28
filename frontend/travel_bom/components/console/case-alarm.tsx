"use client";

import { useEffect, useRef, useState } from "react";
import { Bell, BellRing, X } from "lucide-react";
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
 * The bell in the header keeps every urgent case that is still open, so a dismissed alarm can be
 * read again. The panel opens by itself on the first load of a browser session and whenever a new
 * urgent case arrives; closing it only hides it.
 */
export function CaseAlarm() {
  const [urgent, setUrgent] = useState<CaseListItem[]>([]);
  const [fresh, setFresh] = useState<ReadonlySet<string>>(new Set());
  const [open, setOpen] = useState(false);
  const seen = useRef<Set<string> | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    const check = async () => {
      if (document.visibilityState !== "visible") return;
      try {
        const { items } = await getCases(0, undefined, controller.signal);
        const waiting = items.filter(item => item.priority === "URGENT");
        setUrgent(waiting);
        seen.current ??= readSeen() ?? new Set();
        const arrivals = waiting.filter(item => !seen.current!.has(item.caseId));
        if (!arrivals.length) return;
        for (const item of waiting) seen.current.add(item.caseId);
        writeSeen(seen.current);
        setFresh(new Set(arrivals.map(item => item.caseId)));
        setOpen(true);
      } catch { /* The screens report load errors themselves; the alarm stays quiet and retries. */ }
    };
    void check();
    const timer = window.setInterval(() => void check(), REFRESH_MS);
    return () => { window.clearInterval(timer); controller.abort(); };
  }, []);

  const shown = urgent.slice(0, 5);
  return (
    <div className="relative">
      <Button type="button" variant="outline" size="sm" aria-expanded={open} aria-controls="case-alarm-panel"
        className={urgent.length ? "border-destructive/40 text-destructive hover:text-destructive" : ""} onClick={() => setOpen(value => !value)}>
        {urgent.length ? <BellRing aria-hidden="true" /> : <Bell aria-hidden="true" />}
        긴급 알림 <span className="num font-bold">{urgent.length}</span>
      </Button>
      {open && (
        <aside id="case-alarm-panel" role="alert" aria-label="긴급 상담 알림"
          className="absolute top-full right-0 z-50 mt-2 w-[min(24rem,calc(100vw-1.5rem))] overflow-hidden rounded-lg border border-destructive/40 bg-card shadow-lg">
          <div className="flex items-center gap-2 border-b bg-destructive/8 px-3.5 py-2.5">
            <BellRing className="size-4 text-destructive" aria-hidden="true" />
            <p className="min-w-0 flex-1 text-sm font-bold text-destructive">
              {urgent.length ? `긴급 상담 ${urgent.length}건이 대기 중입니다` : "대기 중인 긴급 상담이 없습니다"}
            </p>
            <Button type="button" variant="ghost" size="icon-sm" aria-label="알림 닫기" title="닫기" onClick={() => setOpen(false)}>
              <X aria-hidden="true" />
            </Button>
          </div>
          {shown.length > 0 && (
            <ul className="divide-y">
              {shown.map(item => (
                <li key={item.caseId} className="flex items-center gap-3 px-3.5 py-2.5">
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-semibold">
                      {item.hotelName}
                      {fresh.has(item.caseId) && <span className="ml-1.5 text-xs font-bold text-destructive">새 건</span>}
                    </p>
                    <p className="num text-xs text-muted-foreground">{item.caseId} · {item.bookingId}</p>
                  </div>
                  {/* A full navigation: the case screen reads the open case from the URL when it loads. */}
                  <a href={`/cases?case=${encodeURIComponent(item.caseId)}`} className={buttonVariants({ variant: "outline", size: "sm" })}>열기</a>
                </li>
              ))}
            </ul>
          )}
          {urgent.length > shown.length && (
            <a href="/cases" className="block border-t px-3.5 py-2 text-center text-sm font-medium hover:underline">
              나머지 {urgent.length - shown.length}건은 상담 대응에서 보기
            </a>
          )}
        </aside>
      )}
    </div>
  );
}
