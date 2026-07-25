// 에픽 자식 이슈 목록 섹션 — 연결/해제/조회 CRUD 패널 (FR-EP-01 D6)
import type { JSX, ChangeEvent } from 'react'
import { useState } from 'react'
import { toast } from 'sonner'
import { useQueryClient } from '@tanstack/react-query'
import {
  useEpicChildren,
  useConnectEpicChild,
  useDisconnectEpicChild,
  EPIC_ERROR_CODES,
  extractEpicErrorCode,
} from '@/api/epic-children'
import type { EpicChildSummary } from '@/api/epic-children'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { epicChildrenStrings } from '@/i18n/ko'
import { EpicProgressBar, epicProgressKey } from './EpicProgressBar'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 → 인라인 메시지 매핑 (error-key drift 방지 — PR #106 교훈)
// ─────────────────────────────────────────────────────────────────────────────

/** 에픽 자식 연결 에러코드 → 인라인 한국어 메시지 */
const EPIC_CHILD_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  [EPIC_ERROR_CODES.ISSUE_EPIC_CHILD_ALREADY_LINKED]: epicChildrenStrings.errorAlreadyLinked,
  [EPIC_ERROR_CODES.ISSUE_EPIC_CHILD_INVALID_TYPE]: epicChildrenStrings.errorInvalidType,
  [EPIC_ERROR_CODES.ISSUE_EPIC_CHILD_CROSS_PROJECT]: epicChildrenStrings.errorCrossProject,
  [EPIC_ERROR_CODES.ISSUE_EPIC_CHILD_SELF_REFERENCE]: epicChildrenStrings.errorSelfReference,
  [EPIC_ERROR_CODES.ISSUE_EPIC_OR_CHILD_NOT_FOUND]: epicChildrenStrings.errorNotFound,
  [EPIC_ERROR_CODES.ISSUE_EPIC_VALIDATION_FAILED]: epicChildrenStrings.errorValidation,
} as const

/**
 * 에러 코드를 인라인 메시지로 변환한다.
 * 알 수 없는 코드이거나 null이면 fallback 메시지를 반환한다.
 *
 * @param errorCode 에러 코드 문자열 (null이면 fallback)
 */
function resolveEpicErrorMessage(errorCode: string | null): string {
  if (errorCode === null) return epicChildrenStrings.errorDefault
  return EPIC_CHILD_ERROR_MESSAGES[errorCode] ?? epicChildrenStrings.errorDefault
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브컴포넌트 — ChildRow
// ─────────────────────────────────────────────────────────────────────────────

interface ChildRowProps {
  /** 자식 이슈 단건 요약 */
  child: EpicChildSummary
  /** 해제 버튼 비활성화 여부 */
  disabled: boolean
  /** 해제 버튼 클릭 핸들러 */
  onDisconnect: (childKey: string) => void
}

/**
 * 자식 이슈 목록 개별 행.
 * 이슈 키(링크 스타일) + 요약 + 상태 칩 + (typeKey가 있을 때만) 타입 칩 + 해제 버튼.
 */
function ChildRow({ child, disabled, onDisconnect }: ChildRowProps): JSX.Element {
  return (
    <li
      className="flex items-center justify-between gap-2 py-1.5 border-b border-border last:border-b-0"
      data-testid={`epic-child-row-${child.key}`}
    >
      <div className="flex items-center gap-2 min-w-0">
        {/* 이슈 키 — 상세 페이지 링크 */}
        <a
          href={`/issues/${child.key}`}
          className="text-sm font-medium text-primary hover:underline shrink-0"
          aria-label={child.key}
        >
          {child.key}
        </a>

        {/* 이슈 요약 */}
        <span className="text-sm text-foreground truncate">{child.summary}</span>

        {/* 상태 칩 */}
        <span className="text-xs text-muted-foreground bg-muted px-1.5 py-0.5 rounded shrink-0">
          {child.currentStateKey}
        </span>

        {/* 타입 칩 — typeKey가 있을 때만 표시 */}
        {child.typeKey !== null && (
          <span
            className="text-xs text-muted-foreground border border-border px-1.5 py-0.5 rounded shrink-0"
            data-testid={`child-type-chip-${child.key}`}
          >
            {child.typeKey}
          </span>
        )}
      </div>

      {/* 해제 버튼 */}
      <Button
        type="button"
        variant="ghost"
        size="xs"
        onClick={() => onDisconnect(child.key)}
        disabled={disabled}
        aria-label={`${child.key} ${epicChildrenStrings.disconnectButton}`}
        className="min-h-[44px] shrink-0 text-muted-foreground hover:text-destructive disabled:opacity-40 disabled:cursor-not-allowed"
      >
        {epicChildrenStrings.disconnectButton}
      </Button>
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface EpicChildrenSectionProps {
  /** 에픽 이슈 키 (예: "ATLAS-EPIC") */
  epicKey: string
  /**
   * 비활성화 여부.
   * - true: 추가/해제 버튼 + 입력 필드 모두 disabled (권한 없음 UX 힌트)
   * - false: 모든 인터랙션 활성
   */
  disabled: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트 — EpicChildrenSection
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에픽 자식 이슈 목록 섹션 컴포넌트.
 *
 * - `useEpicChildren`으로 자식 이슈 목록 조회 (자체 쿼리 보유)
 * - 자식 추가: 이슈 키 입력 + 추가 버튼 → `useConnectEpicChild` POST
 * - 자식 해제: 각 행 해제 버튼 → `useDisconnectEpicChild` DELETE
 * - 에러는 인라인 표시 (role="alert"), 성공은 toast
 * - `disabled=true`이면 모든 편집 UI 비활성 (권한 게이팅 UX 힌트)
 *   백엔드 403이 최종 보안 경계이며, 이 컴포넌트는 UX 힌트만 담당한다.
 *
 * @param epicKey 에픽 이슈 키
 * @param disabled 비활성화 여부 (에픽 자체의 canEdit으로 게이팅)
 */
export const EpicChildrenSection = ({ epicKey, disabled }: EpicChildrenSectionProps): JSX.Element => {
  const [childKeyInput, setChildKeyInput] = useState('')
  const [inlineError, setInlineError] = useState<string | null>(null)

  const queryClient = useQueryClient()
  const { data, isLoading } = useEpicChildren(epicKey)
  const connectMutation = useConnectEpicChild(epicKey)
  const disconnectMutation = useDisconnectEpicChild(epicKey)

  const children = data?.children ?? []

  /** 추가 버튼 비활성 조건: disabled 또는 입력 비어 있음 또는 mutation in-flight */
  const isAddDisabled = disabled || connectMutation.isPending || childKeyInput.trim() === ''

  /**
   * 자식 이슈 추가 핸들러.
   * POST 성공 시 입력을 초기화하고 toast.success를 표시한다.
   * 실패 시 인라인 에러 메시지를 표시한다 (토스트 아님 — 선례 IssueLinksPanel).
   */
  function handleAdd(): void {
    setInlineError(null)
    connectMutation.mutate(childKeyInput.trim(), {
      onSuccess: () => {
        setChildKeyInput('')
        setInlineError(null)
        toast.success(epicChildrenStrings.addChildSuccess)
        // ★ cross-mutation invalidate: 자식 연결 후 progress 막대 갱신 (FR-EP-02)
        void queryClient.invalidateQueries({ queryKey: epicProgressKey(epicKey) })
      },
      onError: (error: unknown) => {
        const code = extractEpicErrorCode(error)
        setInlineError(resolveEpicErrorMessage(code))
      },
    })
  }

  /**
   * 자식 이슈 해제 핸들러.
   * DELETE 성공 시 toast.success를 표시한다.
   * 실패 시 toast.error를 표시한다 (행 단위 에러라 인라인 표시 불가).
   *
   * @param childKey 해제할 자식 이슈 키
   */
  function handleDisconnect(childKey: string): void {
    disconnectMutation.mutate(childKey, {
      onSuccess: () => {
        toast.success(epicChildrenStrings.disconnectSuccess)
        // ★ cross-mutation invalidate: 자식 해제 후 progress 막대 갱신 (FR-EP-02)
        void queryClient.invalidateQueries({ queryKey: epicProgressKey(epicKey) })
      },
      onError: (error: unknown) => {
        const code = extractEpicErrorCode(error)
        toast.error(resolveEpicErrorMessage(code))
      },
    })
  }

  return (
    <section
      data-testid="epic-children-section"
      aria-label={epicChildrenStrings.sectionTitle}
      className="px-3.5 py-3 border-b border-border"
    >
      {/* 섹션 제목 */}
      <p className="text-xs text-muted-foreground mb-2">
        {epicChildrenStrings.sectionTitle}
      </p>

      {/* 진행률 막대 — 자식 목록 상단 배치 (FR-EP-02 Task-5) */}
      <div className="mb-3">
        <EpicProgressBar epicKey={epicKey} />
      </div>

      {/* 목록 영역 */}
      {isLoading ? (
        <p className="text-sm text-muted-foreground">{epicChildrenStrings.loadingState}</p>
      ) : children.length === 0 ? (
        <p className="text-sm text-muted-foreground">{epicChildrenStrings.emptyState}</p>
      ) : (
        <ul className="flex flex-col mb-2">
          {children.map((child) => (
            <ChildRow
              key={child.key}
              child={child}
              disabled={disabled || disconnectMutation.isPending}
              onDisconnect={handleDisconnect}
            />
          ))}
        </ul>
      )}

      {/* 추가 폼 */}
      <div className="flex flex-col gap-2 pt-2">
        <div className="flex gap-2">
          <Input
            type="text"
            aria-label={epicChildrenStrings.childKeyLabel}
            placeholder={epicChildrenStrings.childKeyPlaceholder}
            value={childKeyInput}
            onChange={(e: ChangeEvent<HTMLInputElement>) => {
              setChildKeyInput(e.target.value)
              if (inlineError !== null) setInlineError(null)
            }}
            disabled={disabled}
            className="flex-1"
          />
          <Button
            type="button"
            size="sm"
            onClick={handleAdd}
            disabled={isAddDisabled}
            aria-label={epicChildrenStrings.addChildButton}
          >
            {epicChildrenStrings.addChildButton}
          </Button>
        </div>

        {/* 인라인 에러 — role="alert" (접근성 요구사항) */}
        {inlineError !== null && (
          <p role="alert" className="text-xs text-destructive">
            {inlineError}
          </p>
        )}
      </div>
    </section>
  )
}
