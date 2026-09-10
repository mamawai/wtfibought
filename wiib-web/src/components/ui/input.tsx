import * as React from "react"
import { cn } from "../../lib/utils"

// React 19：ref 是普通 prop，随 ...props 透传
export type InputProps = React.ComponentProps<"input">

function Input({ className, type, ...props }: InputProps) {
  return (
    <input
      type={type}
      className={cn(
        "ui-input flex h-10 w-full rounded-md bg-input border px-3.5 py-2 text-sm font-medium",
        "placeholder:text-muted-foreground disabled:cursor-not-allowed disabled:opacity-50",
        className
      )}
      {...props}
    />
  )
}

export { Input }
