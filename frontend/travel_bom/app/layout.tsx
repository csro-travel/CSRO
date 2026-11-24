import { Analytics } from '@vercel/analytics/next'
import type { Metadata, Viewport } from 'next'
import { Noto_Sans_KR } from 'next/font/google'
import { ConsoleHeader, Sidebar } from '@/components/console/sidebar'
import './globals.css'

const notoSansKr = Noto_Sans_KR({
  subsets: ['latin'],
  weight: ['400', '500', '700'],
  variable: '--font-noto-sans-kr',
})

export const metadata: Metadata = {
  title: '여행봄 | 상담 지원',
  description: '예약 문제의 확인 근거와 상담 업무, 대체 숙소 후보를 검토합니다.',
  generator: 'v0.app',
  icons: {
    icon: [
      { url: '/icon-light-32x32.png', media: '(prefers-color-scheme: light)' },
      { url: '/icon-dark-32x32.png', media: '(prefers-color-scheme: dark)' },
      { url: '/icon.svg', type: 'image/svg+xml' },
    ],
    apple: '/apple-icon.png',
  },
}

export const viewport: Viewport = {
  colorScheme: 'light',
  themeColor: '#ffffff',
}

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode
}>) {
  return (
    <html lang="ko" className={`light ${notoSansKr.variable}`}>
      <body className="font-sans antialiased">
        <div className="flex min-h-dvh bg-background">
          <Sidebar />
          <div className="min-w-0 flex-1 bg-muted/40">
            <ConsoleHeader />
            {children}
          </div>
        </div>
        {process.env.NODE_ENV === 'production' && <Analytics />}
      </body>
    </html>
  )
}
