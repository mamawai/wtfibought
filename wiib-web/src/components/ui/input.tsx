import * as React from "react"
import { cn } from "../../lib/utils"

// React 19：ref 是普通 prop，随 ...props 透传到 input 上
export type InputProps = React.ComponentProps<"input">

/** 输入框：外观是 index.css 的 .inp，className 加在外框上（传 sm 是小号） */
function Input({ className, ...props }: InputProps) {
  return (
    <label className={cn("inp has-[:disabled]:opacity-50", className)}>
      <input {...props} />
    </label>
  )
}

export { Input }
