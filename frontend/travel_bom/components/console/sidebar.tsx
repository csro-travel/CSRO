import Link from 'next/link'
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
  active?: boolean
  children?: { label: string; href: string; active?: boolean }[]
}

const navItems: NavItem[] = [
  { label: '홈', icon: House, href: '#' },
  { label: '예약 관리', icon: CalendarCheck, href: '#' },
  { label: 'CS 상담 관리', icon: Headset, href: '/cases' },
  {
    label: '사전 위험 관리',
    icon: ShieldAlert,
    href: '#',
    active: true,
    children: [
      { label: '자동 확인 작업', href: '#' },
      { label: '재배인 결과', href: '#' },
      { label: '호출 회신 분석', href: '#' },
      { label: '대체 숙소 탐색', href: '#', active: true },
    ],
  },
  { label: '공급사 커뮤니케이션', icon: MessagesSquare, href: '#' },
  { label: '운영 리포트', icon: BarChart3, href: '#' },
  { label: '기준정보 관리', icon: Settings, href: '#' },
]

export function Brand() {
  return (
    <div>
      <p className="flex items-center gap-1.5 text-2xl font-bold text-primary">
        <Flower2 className="size-6" aria-hidden="true" />
        여행봄
      </p>
      <p className="mt-1 text-xs text-muted-foreground">여행 예약 운영 · 상담사</p>
    </div>
  )
}

export function Sidebar() {
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
                  aria-current={item.active && !item.children ? 'page' : undefined}
                  className={cn(
                    'flex items-center gap-3 rounded-xl px-4 py-3 text-sm transition-colors',
                    item.active
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
                          aria-current={child.active ? 'page' : undefined}
                          className={cn(
                            'block rounded-lg px-4 py-2 text-sm transition-colors',
                            child.active
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
