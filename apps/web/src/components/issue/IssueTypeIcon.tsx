// 이슈 타입 iconName 식별자를 lucide-react 아이콘으로 렌더하는 컴포넌트
import type { JSX } from 'react'
import { Crown, BookOpen, CheckSquare, GitBranch, Bug, Circle, type LucideIcon } from 'lucide-react'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 표준 이슈 타입 iconName → lucide-react 아이콘 컴포넌트 매핑 */
const TYPE_ICON_BY_NAME: Readonly<Record<string, LucideIcon>> = {
  epic: Crown,
  story: BookOpen,
  task: CheckSquare,
  subtask: GitBranch,
  bug: Bug,
}

/** 매핑에 없는 iconName 또는 null일 때 사용하는 기본 아이콘 */
const FALLBACK_ICON: LucideIcon = Circle

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssueTypeIcon props */
export interface IssueTypeIconProps {
  /** 백엔드에서 내려오는 이슈 타입 아이콘 식별자 (URL이 아님). null이면 fallback 렌더. */
  iconName: string | null
  /** 접근성 레이블 — 이슈 타입 표시 이름 (예: "에픽", "버그") */
  typeName: string
}

// ─────────────────────────────────────────────────────────────────────────────
// Component
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 타입을 아이콘으로 시각화하는 컴포넌트.
 *
 * - iconName 식별자(epic/story/task/subtask/bug)를 lucide-react 아이콘으로 매핑
 * - 매핑에 없거나 null이면 Circle 아이콘으로 fallback
 * - `<img>` 사용 금지 — iconName은 URL이 아니라 식별자 문자열
 * - aria-label={typeName}으로 스크린리더 접근성 보장
 */
export function IssueTypeIcon({ iconName, typeName }: IssueTypeIconProps): JSX.Element {
  const resolvedName = iconName ?? ''
  const Icon: LucideIcon = TYPE_ICON_BY_NAME[resolvedName] ?? FALLBACK_ICON

  return (
    <Icon
      role="img"
      aria-label={typeName}
      className="size-4 shrink-0"
    />
  )
}
