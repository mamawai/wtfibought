import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "../../lib/utils"

const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap font-semibold transition-colors duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 cursor-pointer",
  {
    variants: {
      variant: {
        default: "bg-primary text-primary-foreground rounded-md hover:bg-primary/90 active:bg-primary/80",
        destructive: "bg-destructive text-white rounded-md hover:bg-destructive/90",
        outline: "border border-border bg-card text-foreground rounded-md hover:bg-surface-hover hover:border-foreground/20",
        secondary: "bg-secondary text-secondary-foreground rounded-md hover:bg-secondary/80",
        ghost: "rounded-md hover:bg-surface-hover",
        link: "text-primary underline-offset-4 hover:underline",
        success: "bg-success text-white rounded-md hover:bg-success/90",
      },
      size: {
        default: "h-10 px-5 text-sm",
        sm: "h-8 px-3.5 text-xs",
        lg: "h-11 px-7 text-base",
        icon: "h-9 w-9 rounded-md",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

// React 19：ref 是普通 prop，随 ...props 透传
export interface ButtonProps
  extends React.ComponentProps<"button">,
    VariantProps<typeof buttonVariants> {}

function Button({ className, variant, size, ...props }: ButtonProps) {
  return (
    <button
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  )
}

export { Button }
