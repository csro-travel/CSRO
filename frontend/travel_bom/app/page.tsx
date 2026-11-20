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
          <h1 className="text-2xl font-bold tracking-tight md:text-3xl">대체 숙소 탐색 시연</h1>
          <p className="mt-2 text-sm text-muted-foreground">
            사건·예약 정보와 후보는 시연 데이터입니다. 공급사 조회와 메시지 전송은 실행되지 않습니다.
          </p>

          <div className="mt-7">
            <AlternativeSearchPanel />
          </div>
        </main>
      </div>
    </div>
  )
}
