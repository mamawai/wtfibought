import * as React from "react"
import { cn } from "../../lib/utils"

// React 19：ref 是普通 prop，随 ...props 透传
export type SelectProps = React.ComponentProps<"select">

function Select({ className, children, ...props }: SelectProps) {
  return (
    <select
      className={cn(
        "ui-input flex h-10 w-full rounded-md bg-input border px-3.5 py-2 text-sm font-medium cursor-pointer",
        "disabled:cursor-not-allowed disabled:opacity-50",
        className
      )}
      {...props}
    >
      {children}
    </select>
  )
}

export { Select }
