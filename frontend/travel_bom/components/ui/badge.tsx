import { cva, type VariantProps } from 'class-variance-authority'
import type { ComponentProps } from 'react'

import { cn } from '@/lib/utils'

const badgeVariants = cva(
  'inline-flex shrink-0 items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium whitespace-nowrap [&_svg]:size-3 [&_svg]:shrink-0',
  {
    variants: {
      tone: {
        neutral: 'bg-secondary text-secondary-foreground',
        outline: 'border bg-background text-muted-foreground',
        brand: 'bg-accent text-accent-foreground',
        // A confirmed problem, in the brand coral. Red is reserved for `urgent`.
        danger: 'bg-primary/12 text-accent-foreground',
        urgent: 'bg-destructive/10 text-destructive',
        warning: 'bg-warning/15 text-[oklch(0.42_0.1_70)]',
        success: 'bg-success/12 text-[oklch(0.4_0.1_158)]',
        info: 'bg-info/10 text-info',
      },
    },
    defaultVariants: { tone: 'neutral' },
  },
)

type BadgeTone = NonNullable<VariantProps<typeof badgeVariants>['tone']>

function Badge({ className, tone, ...props }: ComponentProps<'span'> & VariantProps<typeof badgeVariants>) {
  return <span data-slot="badge" className={cn(badgeVariants({ tone }), className)} {...props} />
}

export { Badge, type BadgeTone }
