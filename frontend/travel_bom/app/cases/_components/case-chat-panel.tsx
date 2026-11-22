import { Button } from "@/components/ui/button";

export function CaseChatPanel() {
  return (
    <section
      aria-labelledby="case-chat-heading"
      className="flex min-h-[26rem] min-w-0 flex-col overflow-hidden rounded-xl border bg-card text-card-foreground"
    >
      <header className="border-b px-5 py-4">
        <h2 id="case-chat-heading" className="text-lg font-bold">
          고객과의 대화
        </h2>
      </header>

      <div
        role="region"
        aria-label="대화 내역"
        className="grid flex-1 place-items-center bg-muted/50 px-5 py-8 text-center"
      >
        <p className="text-sm text-muted-foreground">표시할 대화가 없습니다.</p>
      </div>

      <div className="space-y-3 border-t px-5 py-4">
        <label className="block space-y-2 text-sm font-medium">
          <span className="block">메시지</span>
          <textarea
            rows={3}
            placeholder="메시지를 입력하세요"
            className="block w-full resize-y rounded-lg border border-input bg-background px-3 py-2 text-sm font-normal placeholder:text-muted-foreground focus-visible:outline-2 focus-visible:outline-ring"
          />
        </label>
        <div className="flex justify-end">
          <Button type="button" size="lg" disabled>
            전송
          </Button>
        </div>
      </div>
    </section>
  );
}
