'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import {
  CalendarCheck,
  Flower2,
  Headset,
  House,
  ShieldAlert,
  UserRoundCheck,
  type LucideIcon,
} from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

type NavItem = {
  label: string
  icon: LucideIcon
  href?: string
  children?: { label: string; href: string; note?: string }[]
}

// Every entry opens a screen backed by stored data. Menus without a data source are not listed.
const navItems: NavItem[] = [
  { label: '홈', icon: House, href: '/' },
  { label: '상담 대응', icon: Headset, href: '/cases' },
  {
    // In the order the work happens: a risk signal picks bookings, then they are re-checked.
    label: '사전 점검',
    icon: ShieldAlert,
    children: [
      { label: '위험 평가', href: '/risk/assessments' },
      { label: '자동 확인', href: '/risk/rechecks' },
    ],
  },
  { label: '예약 관리', icon: CalendarCheck, href: '/bookings' },
]
const mobileLinks = [
  { label: '상담 대응', href: '/cases' },
  { label: '위험 평가', href: '/risk/assessments' },
  { label: '자동 확인', href: '/risk/rechecks' },
  { label: '예약', href: '/bookings' },
]

function Brand({ customer = false }: { customer?: boolean }) {
  return (
    <div>
      {/* Home is where a counselor starts. */}
      {customer ? (
        <p className="flex items-center gap-1.5 text-xl font-bold text-primary">
          <Flower2 className="size-5" aria-hidden="true" />
          여행봄
        </p>
      ) : (
        <Link href="/" aria-label="여행봄 홈으로" className="flex w-fit items-center gap-1.5 rounded-md text-xl font-bold text-primary outline-none focus-visible:ring-3 focus-visible:ring-ring/30">
          <Flower2 className="size-5" aria-hidden="true" />
          여행봄
        </Link>
      )}
      <p className="mt-0.5 text-xs text-muted-foreground">{customer ? '고객 전용 · 내 예약 상담' : '숙박 예약 상담 지원 · 상담사'}</p>
    </div>
  )
}

export function ConsoleHeader() {
  const pathname = usePathname()
  if (pathname.startsWith('/customer/')) return <header className="border-b bg-background px-5 py-3"><Brand customer /></header>

  return (
    <header className="border-b bg-background px-5 py-3 lg:py-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="lg:hidden"><Brand /></div>
        {/* States who this screen is for, so it is not read as an admin dashboard. */}
        <Badge tone="brand" className="text-sm lg:ml-auto">
          <UserRoundCheck aria-hidden="true" />
          담당 상담사 실무 화면 · 본인에게 배정된 건을 처리합니다
        </Badge>
      </div>
      <nav aria-label="주 메뉴" className="mt-2 flex flex-wrap gap-1 lg:hidden">
        {mobileLinks.map(link => (
          <Link
            key={link.href}
            href={link.href}
            aria-current={link.href === pathname ? 'page' : undefined}
            className="rounded-md px-2.5 py-1.5 text-sm hover:bg-muted aria-[current=page]:bg-accent aria-[current=page]:font-bold aria-[current=page]:text-accent-foreground"
          >
            {link.label}
          </Link>
        ))}
      </nav>
    </header>
  )
}

const linkClass = 'flex items-center gap-2.5 rounded-md px-3 py-2.5 text-base transition-colors outline-none focus-visible:ring-3 focus-visible:ring-ring/30'

export function Sidebar() {
  const pathname = usePathname()
  if (pathname.startsWith('/customer/')) return null

  return (
    <aside className="hidden w-56 shrink-0 border-r bg-background lg:flex lg:flex-col">
      <div className="px-5 pt-5 pb-4">
        <Brand />
      </div>
      <nav aria-label="주 메뉴" className="scroll-area flex-1 px-2.5 pb-4">
        <ul className="flex flex-col gap-0.5">
          {navItems.map(item => (
            <li key={item.label}>
              {item.href ? (
                <Link
                  href={item.href}
                  aria-current={item.href === pathname ? 'page' : undefined}
                  className={cn(linkClass, item.href === pathname ? 'bg-accent font-bold text-accent-foreground' : 'text-foreground hover:bg-muted')}
                >
                  <item.icon className="size-4" aria-hidden="true" />
                  {item.label}
                </Link>
              ) : (
                <p className={cn(linkClass, 'mt-2 font-medium text-foreground')}>
                  <item.icon className="size-4" aria-hidden="true" />
                  {item.label}
                </p>
              )}
              {item.children && (
                <ul className="mb-2 ml-[1.15rem] flex flex-col gap-0.5 border-l pl-2.5">
                  {item.children.map(child => (
                    <li key={child.href}>
                      <Link
                        href={child.href}
                        aria-current={child.href === pathname ? 'page' : undefined}
                        className={cn(
                          'block rounded-md px-2.5 py-2 text-[0.95rem] transition-colors outline-none focus-visible:ring-3 focus-visible:ring-ring/30',
                          child.href === pathname ? 'bg-accent font-bold text-accent-foreground' : 'text-muted-foreground hover:bg-muted hover:text-foreground',
                        )}
                      >
                        {child.label}
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </li>
          ))}
        </ul>
      </nav>
      <p className="border-t px-5 py-3 text-[0.7rem] leading-relaxed text-muted-foreground">
        시연용 가상 공급사·호텔과 합성 데이터입니다. 실제 예약·결제가 아닙니다.
      </p>
    </aside>
  )
}
