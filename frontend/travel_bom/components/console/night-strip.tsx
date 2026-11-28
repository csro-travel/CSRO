import { formatDay, nightTone, nightToneLabels, type NightTone } from "@/lib/case-labels";
import { cn } from "@/lib/utils";

const toneStyles: Record<NightTone, string> = {
  unavailable: "border-destructive/40 bg-destructive/12 text-destructive",
  available: "border-success/40 bg-success/12 text-[oklch(0.38_0.1_158)]",
  conflict: "border-warning/50 bg-warning/15 text-[oklch(0.42_0.1_70)]",
  // Unconfirmed nights are hatched: not yet a fact, and never to be read as "fine".
  unknown: "border-dashed border-input bg-[repeating-linear-gradient(135deg,transparent_0_5px,var(--muted)_5px_10px)] text-muted-foreground",
};
const shortLabels: Record<NightTone, string> = { unavailable: "제공 불가", available: "제공 가능", conflict: "근거 상충", unknown: "미확인" };

type Night = { stayDate: string; fulfillmentStatus: string; factStatus: string };

/**
 * The stay drawn night by night. A booking being "confirmed" says nothing about each night,
 * so every screen that mentions a booking shows this strip instead of a single status.
 */
export function NightStrip({ nights, size = "md", className }: { nights: Night[]; size?: "sm" | "md"; className?: string }) {
  if (nights.length === 0) return <span className="text-xs text-muted-foreground">숙박일 정보 없음</span>;
  return (
    <ol aria-label="숙박일별 객실 제공 여부" className={cn("flex min-w-0 gap-1", className)}>
      {nights.map(night => {
        const tone = nightTone(night);
        return (
          <li
            key={night.stayDate}
            title={`${formatDay(night.stayDate)} · ${nightToneLabels[tone]}`}
            className={cn("min-w-0 flex-1 rounded-md border", toneStyles[tone], size === "sm" ? "px-1.5 py-1" : "px-2.5 py-2")}
          >
            <time dateTime={night.stayDate} className={cn("num block truncate", size === "sm" ? "text-[0.7rem]" : "text-xs")}>
              {formatDay(night.stayDate)}
            </time>
            <span className={cn("block truncate font-semibold", size === "sm" ? "text-[0.72rem]" : "text-sm")}>{shortLabels[tone]}</span>
            <span className="sr-only">{nightToneLabels[tone]}</span>
          </li>
        );
      })}
    </ol>
  );
}

/** Builds strip input from the list API, which reports nights as three date arrays. */
export function nightsFromDateLists(checkIn: string | undefined, checkOut: string | undefined,
  lists: { unavailable?: string[]; unknown?: string[]; conflict?: string[] }): Night[] {
  if (!checkIn || !checkOut) return [];
  const nights: Night[] = [];
  const end = new Date(`${checkOut}T00:00:00Z`).getTime();
  for (let time = new Date(`${checkIn}T00:00:00Z`).getTime(); time < end && nights.length < 31; time += 86400000) {
    const stayDate = new Date(time).toISOString().slice(0, 10);
    nights.push(lists.conflict?.includes(stayDate) ? { stayDate, fulfillmentStatus: "UNKNOWN", factStatus: "CONFLICT" }
      : lists.unavailable?.includes(stayDate) ? { stayDate, fulfillmentStatus: "UNAVAILABLE", factStatus: "VERIFIED" }
      : lists.unknown?.includes(stayDate) ? { stayDate, fulfillmentStatus: "UNKNOWN", factStatus: "UNCONFIRMED" }
      : { stayDate, fulfillmentStatus: "AVAILABLE", factStatus: "VERIFIED" });
  }
  return nights;
}
