// 클래스 이름 병합 헬퍼 — clsx + tailwind-merge 조합
import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}
