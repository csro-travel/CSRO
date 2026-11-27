import { Check } from "lucide-react";
import type { CaseDetail } from "../_types/case";

type StepState = "done" | "current" | "action" | "todo" | "skip";

const stateLabels: Record<StepState, string> = { done: "완료", current: "진행 중", action: "처리 필요", todo: "미완료", skip: "해당 없음" };
const stateStyles: Record<StepState, { dot: string; text: string }> = {
  done: { dot: "border-success bg-success text-white", text: "text-foreground" },
  current: { dot: "border-primary bg-primary text-primary-foreground", text: "font-bold text-primary" },
  action: { dot: "border-destructive bg-destructive/10 text-destructive", text: "font-bold text-destructive" },
  todo: { dot: "border-input bg-background text-muted-foreground", text: "text-muted-foreground" },
  skip: { dot: "border-dashed border-input bg-background text-muted-foreground", text: "text-muted-foreground" },
};

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

export function CaseProcessBar({ detail }: { detail: CaseDetail }) {
  const steps = caseProcess(detail);
  return (
    <nav aria-label="상담 진행 단계" className="panel px-3 py-2.5">
      <ol className="flex min-w-0 gap-1">
        {steps.map((step, index) => {
          const style = stateStyles[step.state];
          return (
            <li key={step.label} data-process-step={index + 1} aria-current={step.state === "current" ? "step" : undefined}
              className="flex min-w-0 flex-1 flex-col items-center gap-1 rounded-md px-1 py-1 text-center">
              <span className={`num flex size-6 shrink-0 items-center justify-center rounded-full border text-xs font-bold ${style.dot}`}>
                {step.state === "done" ? <Check className="size-3.5" aria-hidden="true" /> : index + 1}
              </span>
              <span className={`text-xs leading-tight wrap-anywhere ${style.text}`}>{step.label}</span>
              <span className={`text-[0.7rem] leading-none ${step.state === "action" ? "font-semibold text-destructive" : "text-muted-foreground"}`}>{stateLabels[step.state]}</span>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
