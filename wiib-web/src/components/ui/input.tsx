import * as React from "react"
import { cn } from "../../lib/utils"

// React 19：ref 是普通 prop，随 ...props 透传
export type InputProps = React.ComponentProps<"input">

function Input({ className, type, ...props }: InputProps) {
  return (
    <input
      type={type}
      className={cn(
        "flex h-10 w-full rounded-md bg-input border border-border px-3.5 py-2 text-sm font-medium",
        "placeholder:text-muted-foreground hover:border-foreground/20",
        "focus-visible:outline-none focus-visible:border-primary focus-visible:ring-2 focus-visible:ring-primary/30",
        "disabled:cursor-not-allowed disabled:opacity-50 transition-colors duration-150",
        className
      )}
      {...props}
    />
  )
}

export { Input }
