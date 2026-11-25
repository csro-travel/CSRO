'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import {
  BarChart3,
  CalendarCheck,
  Flower2,
  Headset,
  House,
  MessagesSquare,
  Settings,
  ShieldAlert,
  type LucideIcon,
} from 'lucide-react'
import { cn } from '@/lib/utils'

type NavItem = {
  label: string
  icon: LucideIcon
  href: string
  children?: { label: string; href: string }[]
}

const casesLink = { label: 'CS 상담 관리', href: '/cases' }
const alternativeSearchLink = { label: '대체 숙소 탐색', href: '/' }
const mobileLinks = [casesLink, alternativeSearchLink]

const navItems: NavItem[] = [
  { label: '홈', icon: House, href: '#' },
  { label: '예약 관리', icon: CalendarCheck, href: '#' },
  { ...casesLink, icon: Headset },
  {
    label: '사전 위험 관리',
    icon: ShieldAlert,
    href: '#',
    children: [
      { label: '자동 확인 작업', href: '#' },
      { label: '재배인 결과', href: '#' },
      { label: '호출 회신 분석', href: '#' },
      alternativeSearchLink,
    ],
  },
  { label: '공급사 커뮤니케이션', icon: MessagesSquare, href: '#' },
  { label: '운영 리포트', icon: BarChart3, href: '#' },
  { label: '기준정보 관리', icon: Settings, href: '#' },
]

function Brand({ customer = false }: { customer?: boolean }) {
  return (
    <div>
      <p className="flex items-center gap-1.5 text-2xl font-bold text-primary">
        <Flower2 className="size-6" aria-hidden="true" />
        여행봄
      </p>
      <p className="mt-1 text-xs text-muted-foreground">{customer ? '고객 전용 · 내 예약 상담' : '여행 예약 운영 · 상담사'}</p>
    </div>
  )
}

export function ConsoleHeader() {
  const pathname = usePathname()
  if (pathname.startsWith('/customer/')) return <header className="border-b bg-background px-5 py-4"><Brand customer /></header>

  return (
    <header className="border-b bg-background px-5 py-4 lg:hidden">
      <Brand />
      <nav aria-label="주 메뉴" className="mt-3 flex flex-wrap gap-1.5">
        {mobileLinks.map(link => (
          <Link
            key={link.href}
            href={link.href}
            aria-current={link.href === pathname ? 'page' : undefined}
            className="rounded-lg px-3 py-2 text-sm hover:bg-muted aria-[current=page]:bg-accent aria-[current=page]:font-bold aria-[current=page]:text-accent-foreground"
          >
            {link.label}
          </Link>
        ))}
      </nav>
    </header>
  )
}

export function Sidebar() {
  const pathname = usePathname()
  if (pathname.startsWith('/customer/')) return null

  return (
    <aside className="hidden w-60 shrink-0 border-r bg-background lg:block">
      <div className="sticky top-0 flex h-dvh flex-col px-4 py-6">
        <div className="px-3">
          <Brand />
        </div>

        <nav aria-label="주 메뉴" className="mt-10">
          <ul className="flex flex-col gap-1.5">
            {navItems.map((item) => (
              <li key={item.label}>
                <Link
                  href={item.href}
                  aria-current={item.href === pathname ? 'page' : undefined}
                  className={cn(
                    'flex items-center gap-3 rounded-xl px-4 py-3 text-sm transition-colors',
                    item.href === pathname || item.children?.some(child => child.href === pathname)
                      ? 'bg-accent font-bold text-accent-foreground'
                      : 'text-foreground hover:bg-muted',
                  )}
                >
                  <item.icon className="size-4" aria-hidden="true" />
                  {item.label}
                </Link>

                {item.children && (
                  <ul className="mt-1.5 mb-2 flex flex-col gap-1 pl-7">
                    {item.children.map((child) => (
                      <li key={child.label}>
                        <Link
                          href={child.href}
                          aria-current={child.href === pathname ? 'page' : undefined}
                          className={cn(
                            'block rounded-lg px-4 py-2 text-sm transition-colors',
                            child.href === pathname
                              ? 'bg-accent font-bold text-accent-foreground'
                              : 'text-muted-foreground hover:bg-muted hover:text-foreground',
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
      </div>
    </aside>
  )
}
