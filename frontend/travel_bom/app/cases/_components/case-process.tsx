import { ArrowDown, Check } from "lucide-react";
import { Button } from "@/components/ui/button";
import { formatDateTime, taskLabels } from "@/lib/case-labels";
import type { CaseDetail } from "../_types/case";

type StepState = "done" | "current" | "action" | "todo" | "skip";

/** Each step is read from the stored case: night facts, open and completed tasks, automatic runs. */
export function caseProcess(detail: CaseDetail): { label: string; state: StepState }[] {
  const open = new Set(detail.tasks.map(task => task.type));
  const done = new Set((detail.completedTasks ?? []).map(task => task.type));
  const task = (type: string, absent: StepState): StepState => (done.has(type) ? "done" : open.has(type) ? "action" : absent);
  const nights = detail.nights;
  const verified = nights.length > 0 && nights.every(night => night.factStatus === "VERIFIED");
  const needsReplacement = nights.some(night => night.factStatus === "VERIFIED" && night.fulfillmentStatus === "UNAVAILABLE");
  const booked = open.has("CHECKIN_CONFIRM") || done.has("CHECKIN_CONFIRM");
  const searched = (detail.analysisRuns ?? []).some(run => run.kind === "QUOTE_RANKING");
  const absent: StepState = needsReplacement ? "todo" : "skip";

  const steps: { label: string; state: StepState }[] = [
    { label: "위험 예약 확인", state: "done" },
    { label: "호텔 측 회신 확인", state: verified ? "done" : open.has("VERIFY_HOTEL") || nights.some(night => night.factStatus === "CONFLICT") ? "action" : "todo" },
    { label: "고객 안내", state: task("CONTACT_CUSTOMER", "todo") },
    { label: "대체 숙소 탐색 및 제안", state: booked ? "done" : !needsReplacement ? "skip" : searched ? "current" : "todo" },
    { label: "고객 선택 및 예약 확인", state: task("CHECKIN_CONFIRM", absent) },
    { label: "후속 기록 작성", state: task("ORIGINAL_FOLLOWUP", absent) },
    { label: "상담 종료", state: detail.status === "CLOSED" ? "done" : "todo" },
  ];
  // The first step still waiting, with nothing ahead of it in progress, is where the counselor is now.
  const first = steps.findIndex(step => step.state !== "done" && step.state !== "skip");
  if (first >= 0 && steps[first].state === "todo") steps[first].state = "current";
  return steps;
}

/** Scrolls to the element that still needs work and flashes it, so a blocked close points at the cause. */
export function flash(selector: string) {
  const element = document.querySelector<HTMLElement>(selector);
  if (!element) return false;
  element.scrollIntoView({ behavior: "smooth", block: "center" });
  element.animate(
    [{ boxShadow: "0 0 0 3px var(--destructive)", backgroundColor: "color-mix(in oklch, var(--destructive) 12%, transparent)" }, { boxShadow: "0 0 0 0 transparent", backgroundColor: "transparent" }],
    { duration: 2600, easing: "ease-out" },
  );
  return true;
}

type Shown = "done" | "now" | "blocked" | "later" | "skip";
const shown: Record<Shown, { word: string; dot: string; text: string }> = {
  done: { word: "끝남", dot: "border-success bg-success text-white", text: "text-foreground" },
  now: { word: "지금", dot: "border-primary bg-primary text-primary-foreground", text: "font-bold text-primary" },
  // The current step has an open task: same place in the flow, shown in red.
  blocked: { word: "지금", dot: "border-destructive bg-destructive text-white", text: "font-bold text-destructive" },
  later: { word: "나중", dot: "border-input bg-background text-muted-foreground", text: "text-muted-foreground" },
  skip: { word: "해당 없음", dot: "border-dashed border-input bg-background text-muted-foreground", text: "text-muted-foreground opacity-50" },
};

/** Where the counselor records each open task. A hotel check is recorded as evidence, the rest as a task note. */
const taskTarget = (type: string) => (type === "VERIFY_HOTEL" ? '[aria-label="수동 확인 및 후속 조치 기록"]' : `[data-task-type="${type}"]`);

/** The stage as one sentence, the seven steps in three visible states, and one button to the next open task. */
export function CaseProcessBar({ detail }: { detail: CaseDetail }) {
  const steps = caseProcess(detail);
  const now = steps.findIndex(step => step.state !== "done" && step.state !== "skip");
  const blocked = now >= 0 && steps[now].state === "action";
  const next = detail.status === "CLOSED" ? undefined : detail.tasks.find(task => task.taskId === detail.nextAction?.taskId) ?? detail.tasks[0];
  return (
    <nav aria-label="상담 진행 단계" className="panel space-y-3 p-4">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <p className={`text-lg leading-tight font-bold ${blocked ? "text-destructive" : ""}`}>
          {now >= 0 ? <>지금: {steps[now].label} <span className="num text-sm font-medium text-muted-foreground">({now + 1}/{steps.length})</span></> : "모든 단계가 끝났습니다"}
        </p>
        {next && (
          <Button type="button" size="sm" className="ml-auto" onClick={() => flash(taskTarget(next.type))}>
            다음 할 일: {taskLabels.get(next.type) ?? "남은 업무 확인"}<ArrowDown aria-hidden="true" />
          </Button>
        )}
        {next?.dueAt && <time dateTime={next.dueAt} className="num text-sm text-muted-foreground">기한 {formatDateTime(next.dueAt)}</time>}
      </div>
      <ol className="flex min-w-0 gap-1">
        {steps.map((step, index) => {
          const state: Shown = index === now ? (blocked ? "blocked" : "now") : step.state === "done" ? "done" : step.state === "skip" ? "skip" : "later";
          const style = shown[state];
          return (
            <li key={step.label} data-process-step={index + 1} aria-current={index === now ? "step" : undefined}
              className={`flex min-w-0 flex-1 flex-col items-center gap-1 rounded-md px-1 py-1 text-center ${style.text}`}>
              <span className={`num flex size-6 shrink-0 items-center justify-center rounded-full border text-xs font-bold ${style.dot}`}>
                {state === "done" ? <Check className="size-3.5" aria-hidden="true" /> : index + 1}
              </span>
              <span className="text-[0.8rem] leading-tight wrap-anywhere">{step.label}</span>
              <span className="text-[0.8rem] leading-none font-normal">{style.word}</span>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
