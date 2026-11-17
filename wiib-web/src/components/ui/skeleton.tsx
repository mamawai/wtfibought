import { cn } from "../../lib/utils"

/** 骨架：和将来内容一样大的浅灰块，慢慢呼吸（样式见 index.css .sk） */
function Skeleton({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("sk", className)} {...props} />
}

export { Skeleton }
