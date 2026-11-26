import { Input as InputPrimitive } from '@base-ui/react/input'
import type { ComponentProps } from 'react'

import { cn } from '@/lib/utils'

const fieldClass =
  'w-full min-w-0 rounded-lg border border-input bg-background px-3 text-sm transition-colors outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/30 disabled:cursor-not-allowed disabled:bg-muted disabled:opacity-70'

function Input({ className, ...props }: ComponentProps<typeof InputPrimitive>) {
  return <InputPrimitive data-slot="input" className={cn(fieldClass, 'h-9', className)} {...props} />
}

function Textarea({ className, ...props }: ComponentProps<'textarea'>) {
  return <textarea data-slot="textarea" className={cn(fieldClass, 'block resize-none py-2 leading-relaxed', className)} {...props} />
}

export { Input, Textarea }
