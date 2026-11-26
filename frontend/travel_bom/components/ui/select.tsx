'use client'

import { Select as SelectPrimitive } from '@base-ui/react/select'
import { Check, ChevronDown } from 'lucide-react'

import { cn } from '@/lib/utils'

type Option = { value: string; label: string }

/** Single-value dropdown. `options` carries the labels so the trigger can show the chosen one. */
function Select({ value, onValueChange, options, disabled, id, className, label }: {
  value: string
  onValueChange: (value: string) => void
  options: readonly Option[]
  disabled?: boolean
  id?: string
  className?: string
  label?: string
}) {
  return (
    <SelectPrimitive.Root value={value} onValueChange={next => onValueChange(String(next ?? ''))} disabled={disabled} items={[...options]}>
      <SelectPrimitive.Trigger
        id={id}
        aria-label={label}
        data-slot="select-trigger"
        className={cn(
          'flex h-9 w-full min-w-0 items-center justify-between gap-2 rounded-lg border border-input bg-background px-3 text-sm outline-none transition-colors focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/30 data-[disabled]:cursor-not-allowed data-[disabled]:bg-muted data-[disabled]:opacity-70 data-[popup-open]:border-ring',
          className,
        )}
      >
        <SelectPrimitive.Value className="truncate" />
        <SelectPrimitive.Icon className="text-muted-foreground">
          <ChevronDown className="size-4" aria-hidden="true" />
        </SelectPrimitive.Icon>
      </SelectPrimitive.Trigger>
      <SelectPrimitive.Portal>
        <SelectPrimitive.Positioner sideOffset={6} alignItemWithTrigger={false} className="z-50">
          <SelectPrimitive.Popup className="min-w-[var(--anchor-width)] rounded-lg border bg-popover p-1 text-sm text-popover-foreground shadow-lg outline-none">
            {options.map(option => (
              <SelectPrimitive.Item
                key={option.value}
                value={option.value}
                className="flex cursor-default items-center justify-between gap-3 rounded-md px-2.5 py-1.5 outline-none select-none data-[highlighted]:bg-accent data-[highlighted]:text-accent-foreground"
              >
                <SelectPrimitive.ItemText>{option.label}</SelectPrimitive.ItemText>
                <SelectPrimitive.ItemIndicator>
                  <Check className="size-3.5" aria-hidden="true" />
                </SelectPrimitive.ItemIndicator>
              </SelectPrimitive.Item>
            ))}
          </SelectPrimitive.Popup>
        </SelectPrimitive.Positioner>
      </SelectPrimitive.Portal>
    </SelectPrimitive.Root>
  )
}

export { Select }
