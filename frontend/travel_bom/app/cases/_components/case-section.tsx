import type { ReactNode } from "react";
import { ChevronDown } from "lucide-react";

export function CaseSection({ title, className = "", children }: {
  title: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <details className={`panel group ${className}`}>
      <summary className="flex cursor-pointer list-none items-center justify-between gap-2 rounded-[inherit] p-4 text-sm font-semibold focus-visible:outline-2 focus-visible:outline-primary [&::-webkit-details-marker]:hidden">
        {title}
        <ChevronDown className="size-4 text-muted-foreground group-open:rotate-180" aria-hidden="true" />
      </summary>
      <div className="space-y-3 px-4 pb-4">{children}</div>
    </details>
  );
}
