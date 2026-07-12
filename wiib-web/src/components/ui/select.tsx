import * as React from "react"
import { cn } from "../../lib/utils"

// React 19：ref 是普通 prop，随 ...props 透传
export type SelectProps = React.ComponentProps<"select">

function Select({ className, children, ...props }: SelectProps) {
  return (
    <select
      className={cn(
        "flex h-11 w-full rounded-xl bg-background px-4 py-2 text-sm font-medium neu-inset",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40 focus-visible:border-primary",
        "disabled:cursor-not-allowed disabled:opacity-50 transition-all duration-150",
        className
      )}
      {...props}
    >
      {children}
    </select>
  )
}

export { Select }
