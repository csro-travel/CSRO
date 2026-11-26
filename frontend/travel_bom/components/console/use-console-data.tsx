"use client";

import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { formatDateTime } from "@/lib/case-labels";

/** Loads a console query, refreshes it on an interval while the tab is visible, and keeps the last good data on failure. */
export function useConsoleData<T>(load: (signal: AbortSignal) => Promise<T>, intervalMs = 15000) {
  const [data, setData] = useState<T>();
  const [error, setError] = useState("");
  const [loadedAt, setLoadedAt] = useState<string>();
  const [loading, setLoading] = useState(true);
  const request = useRef<AbortController | null>(null);
  const loader = useRef(load);
  loader.current = load;

  const refresh = useCallback(async () => {
    request.current?.abort();
    const controller = new AbortController(); request.current = controller;
    setLoading(true);
    try {
      const value = await loader.current(controller.signal);
      if (controller.signal.aborted) return;
      setData(value); setError(""); setLoadedAt(new Date().toISOString());
    } catch (cause) {
      if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : "조회하지 못했습니다");
    } finally { if (!controller.signal.aborted) setLoading(false); }
  }, []);

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => { if (document.visibilityState === "visible") void refresh(); }, intervalMs);
    return () => { window.clearInterval(timer); request.current?.abort(); };
  }, [refresh, intervalMs]);

  return { data, error, loadedAt, loading, refresh };
}

/** Shared frame for the console pages: title, what the page is for, refresh state, then content. */
export function ConsolePage({ title, description, state, children, wide = false }: {
  title: string;
  description: ReactNode;
  state: { error: string; loadedAt?: string; loading: boolean; refresh: () => void | Promise<void>; data?: unknown };
  children: ReactNode;
  wide?: boolean;
}) {
  return (
    <main className={`mx-auto space-y-4 px-5 py-5 ${wide ? "max-w-[1500px]" : "max-w-[1200px]"}`}>
      <header className="flex flex-wrap items-end justify-between gap-x-4 gap-y-2">
        <div className="min-w-0">
          <h1 className="text-xl font-bold">{title}</h1>
          <p className="mt-1 max-w-3xl text-sm leading-relaxed text-muted-foreground">{description}</p>
        </div>
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          {state.loadedAt && <span className="num">{formatDateTime(state.loadedAt)} 기준 · 15초마다 갱신</span>}
          <Button type="button" variant="outline" size="icon" disabled={state.loading} onClick={() => void state.refresh()} aria-label="새로고침" title="새로고침">
            <RefreshCw className={state.loading ? "animate-spin" : ""} aria-hidden="true" />
          </Button>
        </div>
      </header>
      {state.error && (
        <p role="alert" className="panel border-destructive/40 px-4 py-3 text-sm text-destructive">
          {state.error}. {state.data ? "마지막으로 받은 내용을 보여 주고 있습니다." : "잠시 뒤 다시 시도합니다."}
        </p>
      )}
      {!state.data && !state.error && <p role="status" className="text-sm text-muted-foreground">불러오는 중…</p>}
      {children}
    </main>
  );
}

export function EmptyState({ children }: { children: ReactNode }) {
  return <p className="panel px-4 py-10 text-center text-sm leading-relaxed text-muted-foreground">{children}</p>;
}
