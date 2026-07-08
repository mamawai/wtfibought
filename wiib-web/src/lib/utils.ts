import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function isTradingHours(): boolean {
  const now = new Date();
  const day = now.getDay();
  if (day === 0 || day === 6) return false; // 周末

  const hours = now.getHours();
  const minutes = now.getMinutes();
  const time = hours * 60 + minutes;

  const morningStart = 9 * 60 + 30;  // 09:30
  const morningEnd = 11 * 60 + 30;   // 11:30
  const afternoonStart = 13 * 60;    // 13:00
  const afternoonEnd = 15 * 60;      // 15:00

  return (time >= morningStart && time <= morningEnd) ||
         (time >= afternoonStart && time <= afternoonEnd);
}

