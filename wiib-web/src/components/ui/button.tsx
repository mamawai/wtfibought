import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "../../lib/utils"

const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap font-bold transition-all duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50 cursor-pointer",
  {
    variants: {
      variant: {
        default: "bg-primary text-primary-foreground rounded-full neu-btn-sm",
        destructive: "bg-destructive text-white rounded-full neu-btn-sm",
        outline: "bg-card text-foreground rounded-full neu-btn-sm",
        secondary: "bg-secondary text-secondary-foreground rounded-full neu-flat hover:bg-secondary/80",
        ghost: "rounded-xl hover:bg-surface-hover",
        link: "text-primary underline-offset-4 hover:underline",
        success: "bg-success text-white rounded-full neu-btn-sm",
      },
      size: {
        default: "h-11 px-6 py-2 text-sm",
        sm: "h-9 px-4 text-xs",
        lg: "h-12 px-8 text-base",
        icon: "h-10 w-10 rounded-xl",
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
