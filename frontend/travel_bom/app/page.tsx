import { Brand, Sidebar } from '@/components/console/sidebar'
import { AlternativeSearchPanel } from '@/components/console/alternative-search-panel'

export default function Page() {
  return (
    <div className="flex min-h-dvh bg-background">
      <Sidebar />

      <div className="min-w-0 flex-1 bg-muted/40">
        <header className="border-b bg-background px-5 py-4 lg:hidden">
          <Brand />
        </header>

        <main className="mx-auto max-w-6xl px-5 py-8 md:px-9 md:py-10">
          <h1 className="text-2xl font-bold tracking-tight md:text-3xl">대체 숙소 자동 탐색</h1>
          <p className="mt-2 text-sm text-muted-foreground">
            체실 제공이 불가하거나 확인된 첫날에 해당 공급사 API로 대체 숙소 후보를 자동
            탐색합니다.
          </p>

          <div className="mt-7">
            <AlternativeSearchPanel />
          </div>
        </main>
      </div>
    </div>
  )
}
