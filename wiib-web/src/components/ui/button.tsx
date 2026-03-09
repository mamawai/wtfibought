import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "../../lib/utils"

const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap font-bold transition-all duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50 cursor-pointer",
  {
    variants: {
      variant: {
        default: "bg-primary text-primary-foreground border-[2.5px] border-edge rounded-full shadow-[3px_3px_0_0_var(--color-edge)] hover:shadow-[1px_1px_0_0_var(--color-edge)] hover:translate-x-[2px] hover:translate-y-[2px] active:shadow-none active:translate-x-[3px] active:translate-y-[3px]",
        destructive: "bg-destructive text-white border-[2.5px] border-edge rounded-full shadow-[3px_3px_0_0_var(--color-edge)] hover:shadow-[1px_1px_0_0_var(--color-edge)] hover:translate-x-[2px] hover:translate-y-[2px] active:shadow-none active:translate-x-[3px] active:translate-y-[3px]",
        outline: "bg-card border-[2.5px] border-edge rounded-full shadow-[3px_3px_0_0_var(--color-edge)] hover:shadow-[1px_1px_0_0_var(--color-edge)] hover:translate-x-[2px] hover:translate-y-[2px] active:shadow-none active:translate-x-[3px] active:translate-y-[3px]",
        secondary: "bg-secondary text-secondary-foreground border-[2px] border-edge/30 rounded-full hover:bg-secondary/80",
        ghost: "rounded-xl hover:bg-surface-hover",
        link: "text-primary underline-offset-4 hover:underline",
        success: "bg-success text-white border-[2.5px] border-edge rounded-full shadow-[3px_3px_0_0_var(--color-edge)] hover:shadow-[1px_1px_0_0_var(--color-edge)] hover:translate-x-[2px] hover:translate-y-[2px] active:shadow-none active:translate-x-[3px] active:translate-y-[3px]",
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

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, ...props }, ref) => {
    return (
      <button
        className={cn(buttonVariants({ variant, size, className }))}
        ref={ref}
        {...props}
      />
    )
  }
)
Button.displayName = "Button"

export { Button }
