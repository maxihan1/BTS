// 이슈 링크 패널 컴포넌트 — blocks/relates/duplicates/clones + 부모-자식 링크 + 소속 에픽 (FR-LK-01 D6 / FR-EP-01 D6)
import type { JSX, ChangeEvent } from 'react'
import { useState } from 'react'
import { toast } from 'sonner'
import { useIssueLinks, useCreateLink, useDeleteLink, useSetParent } from '@/api/issue-links'
import { ISSUE_LINK_ERROR_CODES, extractLinkErrorCode } from '@/api/issue-links'
import type { IssueLinkResponse } from '@/api/issue-links'
import {
  useSetIssueEpic,
  useClearIssueEpic,
  EPIC_ERROR_CODES,
  extractEpicErrorCode,
} from '@/api/epic-children'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { issueLinkStrings, epicChildrenStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 지원 링크 유형 목록 — 소문자 코드 (백엔드 계약) */
const LINK_TYPES = [
  { value: 'blocks', label: issueLinkStrings.linkTypeBlocks },
  { value: 'relates', label: issueLinkStrings.linkTypeRelates },
  { value: 'duplicates', label: issueLinkStrings.linkTypeDuplicates },
  { value: 'clones', label: issueLinkStrings.linkTypeClones },
] as const

/** linkType 코드 유니온 타입 */
type LinkTypeCode = (typeof LINK_TYPES)[number]['value']

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 → 사용자 메시지 매핑 (인라인 에러 표시 전용, 토스트 아님)
// ─────────────────────────────────────────────────────────────────────────────

/** 링크 에러코드 → 인라인 한국어 메시지 (error-key drift 방지 — PR #106 교훈) */
const LINK_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  [ISSUE_LINK_ERROR_CODES.LINK_SELF_REFERENCE]: issueLinkStrings.errorLinkSelfReference,
  [ISSUE_LINK_ERROR_CODES.DUPLICATE_LINK]: issueLinkStrings.errorDuplicateLink,
  [ISSUE_LINK_ERROR_CODES.ISSUE_NOT_FOUND]: issueLinkStrings.errorIssueNotFound,
  [ISSUE_LINK_ERROR_CODES.LINK_CYCLE]: issueLinkStrings.errorLinkCycle,
  [ISSUE_LINK_ERROR_CODES.LINK_NOT_FOUND]: issueLinkStrings.errorLinkNotFound,
} as const

/** 부모 에러코드 → 인라인 한국어 메시지 */
const PARENT_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  [ISSUE_LINK_ERROR_CODES.PARENT_SELF_REFERENCE]: issueLinkStrings.errorParentSelfReference,
  [ISSUE_LINK_ERROR_CODES.PARENT_CYCLE]: issueLinkStrings.errorParentCycle,
  [ISSUE_LINK_ERROR_CODES.ISSUE_NOT_FOUND]: issueLinkStrings.errorIssueNotFound,
} as const

/**
 * 에픽 연결/해제 에러코드 → 인라인 한국어 메시지.
 * EpicChildrenSection의 EPIC_CHILD_ERROR_MESSAGES와 동일 구성 — 공유 util로 추출 시
 * EpicChildrenSection.tsx(T4 산출물) 수정이 금지되므로 동형 복제로 drift를 막는다.
 * 두 맵은 같은 EPIC_ERROR_CODES 상수와 epicChildrenStrings를 참조하므로 drift가 없다.
 * (error-key drift 방지 — PR #106 교훈)
 */
const EPIC_CHILD_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  [EPIC_ERROR_CODES.ISSUE_EPIC_CHILD_ALREADY_LINKED]: epicChildrenStrings.errorAlreadyLinked,
  [EPIC_ERROR_CODES.ISSUE_EPIC_CHILD_INVALID_TYPE]: epicChildrenStrings.errorInvalidType,
  [EPIC_ERROR_CODES.ISSUE_EPIC_CHILD_CROSS_PROJECT]: epicChildrenStrings.errorCrossProject,
  [EPIC_ERROR_CODES.ISSUE_EPIC_CHILD_SELF_REFERENCE]: epicChildrenStrings.errorSelfReference,
  [EPIC_ERROR_CODES.ISSUE_EPIC_OR_CHILD_NOT_FOUND]: epicChildrenStrings.errorNotFound,
  [EPIC_ERROR_CODES.ISSUE_EPIC_VALIDATION_FAILED]: epicChildrenStrings.errorValidation,
} as const

/**
 * 에러 코드를 링크 인라인 메시지로 변환한다.
 * 알 수 없는 코드는 fallback 메시지를 반환한다.
 *
 * @param errorCode 에러 코드 문자열 (null이면 fallback)
 * @param errorMessages 에러코드→메시지 맵
 */
function resolveErrorMessage(
  errorCode: string | null,
  errorMessages: Readonly<Record<string, string>>,
): string {
  if (errorCode === null) return issueLinkStrings.errorDefault
  return errorMessages[errorCode] ?? issueLinkStrings.errorDefault
}

/**
 * 에픽 에러 코드를 인라인 메시지로 변환한다.
 * 알 수 없는 코드이거나 null이면 fallback 메시지를 반환한다.
 *
 * @param errorCode 에픽 에러 코드 문자열 (null이면 fallback)
 */
function resolveEpicErrorMessage(errorCode: string | null): string {
  if (errorCode === null) return epicChildrenStrings.errorDefault
  return EPIC_CHILD_ERROR_MESSAGES[errorCode] ?? epicChildrenStrings.errorDefault
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브컴포넌트 — LinkRow
// ─────────────────────────────────────────────────────────────────────────────

interface LinkRowProps {
  /** 링크 단건 응답 */
  link: IssueLinkResponse
  /** 제거 버튼 클릭 핸들러 */
  onRemove: (linkId: number) => void
  /** 제거 비활성화 여부 */
  disabled: boolean
}

/**
 * 링크 목록 개별 행 컴포넌트.
 * label + 상대 이슈 key/summary/statusKey 를 표시하고
 * 제거 버튼을 제공한다.
 */
function LinkRow({ link, onRemove, disabled }: LinkRowProps): JSX.Element {
  return (
    <li className="flex items-center justify-between gap-2 py-1.5 border-b border-border last:border-b-0">
      <div className="flex items-center gap-2 min-w-0">
        <span className="text-xs text-muted-foreground shrink-0">{link.label}</span>
        <a
          href={`#${link.otherIssue.key}`}
          className="text-sm font-medium text-primary hover:underline shrink-0"
          aria-label={link.otherIssue.key}
        >
          {link.otherIssue.key}
        </a>
        <span className="text-sm text-foreground truncate">{link.otherIssue.summary}</span>
        <span className="text-xs text-muted-foreground shrink-0">({link.otherIssue.statusKey})</span>
      </div>
      <Button
        type="button"
        variant="ghost"
        size="xs"
        onClick={() => onRemove(link.id)}
        disabled={disabled}
        aria-label={issueLinkStrings.removeLinkButton}
        className="min-h-[44px] shrink-0 text-muted-foreground hover:text-destructive disabled:opacity-40 disabled:cursor-not-allowed"
      >
        {issueLinkStrings.removeLinkButton}
      </Button>
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브컴포넌트 — AddLinkForm
// ─────────────────────────────────────────────────────────────────────────────

interface AddLinkFormProps {
  /** 이슈 키 */
  issueKey: string
  /** 비활성화 여부 */
  disabled: boolean
}

/**
 * 링크 추가 폼 서브컴포넌트.
 * 유형 select(소문자 value) + 대상 키 input + 추가 버튼.
 * 에러는 인라인 표시 (토스트 아님).
 */
function AddLinkForm({ issueKey, disabled }: AddLinkFormProps): JSX.Element {
  const [linkType, setLinkType] = useState<LinkTypeCode>('blocks')
  const [targetKey, setTargetKey] = useState('')
  const [inlineError, setInlineError] = useState<string | null>(null)

  const { mutate: createLink, isPending } = useCreateLink(issueKey)

  function handleSubmit(): void {
    setInlineError(null)
    createLink(
      { targetKey: targetKey.trim(), linkType },
      {
        onSuccess: () => {
          setTargetKey('')
          setInlineError(null)
          toast.success(issueLinkStrings.addLinkSuccess)
        },
        onError: (error: unknown) => {
          const code = extractLinkErrorCode(error)
          setInlineError(resolveErrorMessage(code, LINK_ERROR_MESSAGES))
        },
      },
    )
  }

  const isSubmitDisabled = disabled || isPending || targetKey.trim() === ''

  return (
    <div className="flex flex-col gap-2 pt-2">
      <div className="flex gap-2">
        {/* 링크 유형 선택 — native select (jsdom 호환, 테스트 단순화) */}
        <select
          aria-label={issueLinkStrings.linkTypeSelectLabel}
          value={linkType}
          onChange={(e: ChangeEvent<HTMLSelectElement>) => {
            setLinkType(e.target.value as LinkTypeCode)
          }}
          disabled={disabled}
          className="rounded-md border border-input bg-background px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-40 disabled:cursor-not-allowed shrink-0"
        >
          {LINK_TYPES.map((lt) => (
            <option key={lt.value} value={lt.value}>
              {lt.label}
            </option>
          ))}
        </select>

        {/* 대상 이슈 키 입력 */}
        <Input
          type="text"
          aria-label={issueLinkStrings.targetKeyLabel}
          placeholder={issueLinkStrings.targetKeyPlaceholder}
          value={targetKey}
          onChange={(e: ChangeEvent<HTMLInputElement>) => {
            setTargetKey(e.target.value)
            if (inlineError !== null) setInlineError(null)
          }}
          disabled={disabled}
          className="flex-1"
        />

        {/* 링크 추가 버튼 */}
        <Button
          type="button"
          size="sm"
          onClick={handleSubmit}
          disabled={isSubmitDisabled}
          aria-label={issueLinkStrings.addLinkButton}
        >
          {issueLinkStrings.addLinkButton}
        </Button>
      </div>

      {/* 인라인 에러 — 토스트 아님 */}
      {inlineError !== null && (
        <p role="alert" className="text-xs text-destructive">
          {inlineError}
        </p>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브컴포넌트 — ParentSection
// ─────────────────────────────────────────────────────────────────────────────

interface ParentSectionProps {
  /** 이슈 키 */
  issueKey: string
  /** 현재 부모 이슈 (null이면 미설정) */
  parent: { key: string; summary: string } | null | undefined
  /** 비활성화 여부 */
  disabled: boolean
}

/**
 * 부모 이슈 섹션 서브컴포넌트.
 * - parent 있음: "KEY (요약)" + 해제 버튼
 * - parent 없음: 키 input + 지정 버튼
 * 에러는 인라인 표시 (토스트 아님).
 */
function ParentSection({ issueKey, parent, disabled }: ParentSectionProps): JSX.Element {
  const [parentKey, setParentKey] = useState('')
  const [inlineError, setInlineError] = useState<string | null>(null)

  const { mutate: setParentMutation, isPending } = useSetParent(issueKey)

  function handleSetParent(): void {
    setInlineError(null)
    setParentMutation(
      { parentKey: parentKey.trim() },
      {
        onSuccess: () => {
          setParentKey('')
          setInlineError(null)
          toast.success(issueLinkStrings.setParentSuccess)
        },
        onError: (error: unknown) => {
          const code = extractLinkErrorCode(error)
          setInlineError(resolveErrorMessage(code, PARENT_ERROR_MESSAGES))
        },
      },
    )
  }

  function handleClearParent(): void {
    setInlineError(null)
    setParentMutation(
      { parentKey: null },
      {
        onSuccess: () => {
          setInlineError(null)
          toast.success(issueLinkStrings.clearParentSuccess)
        },
        onError: (error: unknown) => {
          const code = extractLinkErrorCode(error)
          setInlineError(resolveErrorMessage(code, PARENT_ERROR_MESSAGES))
        },
      },
    )
  }

  const hasParent = parent !== null && parent !== undefined

  return (
    <div className="flex flex-col gap-1.5">
      {hasParent ? (
        <div className="flex items-center gap-2">
          <a
            href={`#${parent.key}`}
            className="text-sm font-medium text-primary hover:underline"
            aria-label={parent.key}
          >
            {parent.key}
          </a>
          <span className="text-sm text-foreground truncate">{parent.summary}</span>
          <Button
            type="button"
            variant="ghost"
            size="xs"
            onClick={handleClearParent}
            disabled={disabled || isPending}
            aria-label={issueLinkStrings.clearParentButton}
            className="min-h-[44px] shrink-0 text-muted-foreground hover:text-destructive disabled:opacity-40 disabled:cursor-not-allowed"
          >
            {issueLinkStrings.clearParentButton}
          </Button>
        </div>
      ) : (
        <div className="flex gap-2">
          <Input
            type="text"
            aria-label={issueLinkStrings.parentKeyLabel}
            placeholder={issueLinkStrings.parentKeyPlaceholder}
            value={parentKey}
            onChange={(e: ChangeEvent<HTMLInputElement>) => {
              setParentKey(e.target.value)
              if (inlineError !== null) setInlineError(null)
            }}
            disabled={disabled}
            className="flex-1"
          />
          <Button
            type="button"
            size="sm"
            onClick={handleSetParent}
            disabled={disabled || isPending || parentKey.trim() === ''}
            aria-label={issueLinkStrings.setParentButton}
          >
            {issueLinkStrings.setParentButton}
          </Button>
        </div>
      )}

      {/* 부모 인라인 에러 */}
      {inlineError !== null && (
        <p role="alert" className="text-xs text-destructive">
          {inlineError}
        </p>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브컴포넌트 — EpicSection
// ─────────────────────────────────────────────────────────────────────────────

interface EpicSectionProps {
  /** 이슈 키 (자신의 키 — 자식 이슈 관점) */
  issueKey: string
  /** 현재 소속 에픽 (null이면 미지정) */
  epic: { key: string; summary: string } | null | undefined
  /** 비활성화 여부 */
  disabled: boolean
}

/**
 * 소속 에픽 섹션 서브컴포넌트 (ParentSection 동형 미러).
 * - epic 있음: "KEY (요약)" + 해제 버튼 (useClearIssueEpic)
 * - epic 없음: 에픽 키 input + 지정 버튼 (useSetIssueEpic)
 * 에러는 인라인 표시 (토스트 아님).
 *
 * @param issueKey 자신(자식 이슈)의 키
 * @param epic 현재 소속 에픽 정보
 * @param disabled 비활성화 여부
 */
function EpicSection({ issueKey, epic, disabled }: EpicSectionProps): JSX.Element {
  const [epicKey, setEpicKey] = useState('')
  const [inlineError, setInlineError] = useState<string | null>(null)

  const { mutate: setEpicMutation, isPending: isSetPending } = useSetIssueEpic(issueKey)
  const { mutate: clearEpicMutation, isPending: isClearPending } = useClearIssueEpic(issueKey)

  const isPending = isSetPending || isClearPending

  function handleSetEpic(): void {
    setInlineError(null)
    setEpicMutation(epicKey.trim(), {
      onSuccess: () => {
        setEpicKey('')
        setInlineError(null)
        toast.success(issueLinkStrings.setEpicSuccess)
      },
      onError: (error: unknown) => {
        const code = extractEpicErrorCode(error)
        setInlineError(resolveEpicErrorMessage(code))
      },
    })
  }

  function handleClearEpic(): void {
    if (epic === null || epic === undefined) return
    setInlineError(null)
    clearEpicMutation(epic.key, {
      onSuccess: () => {
        setInlineError(null)
        toast.success(issueLinkStrings.clearEpicSuccess)
      },
      onError: (error: unknown) => {
        const code = extractEpicErrorCode(error)
        setInlineError(resolveEpicErrorMessage(code))
      },
    })
  }

  const hasEpic = epic !== null && epic !== undefined

  return (
    <div className="flex flex-col gap-1.5">
      {hasEpic ? (
        <div className="flex items-center gap-2">
          <a
            href={`#${epic.key}`}
            className="text-sm font-medium text-primary hover:underline"
            aria-label={epic.key}
          >
            {epic.key}
          </a>
          <span className="text-sm text-foreground truncate">{epic.summary}</span>
          <Button
            type="button"
            variant="ghost"
            size="xs"
            onClick={handleClearEpic}
            disabled={disabled || isPending}
            aria-label={issueLinkStrings.clearEpicButton}
            className="min-h-[44px] shrink-0 text-muted-foreground hover:text-destructive disabled:opacity-40 disabled:cursor-not-allowed"
          >
            {issueLinkStrings.clearEpicButton}
          </Button>
        </div>
      ) : (
        <div className="flex gap-2">
          <Input
            type="text"
            aria-label={issueLinkStrings.epicKeyLabel}
            placeholder={issueLinkStrings.epicKeyPlaceholder}
            value={epicKey}
            onChange={(e: ChangeEvent<HTMLInputElement>) => {
              setEpicKey(e.target.value)
              if (inlineError !== null) setInlineError(null)
            }}
            disabled={disabled}
            className="flex-1"
          />
          <Button
            type="button"
            size="sm"
            onClick={handleSetEpic}
            disabled={disabled || isPending || epicKey.trim() === ''}
            aria-label={issueLinkStrings.setEpicButton}
          >
            {issueLinkStrings.setEpicButton}
          </Button>
        </div>
      )}

      {/* 소속 에픽 인라인 에러 */}
      {inlineError !== null && (
        <p role="alert" className="text-xs text-destructive">
          {inlineError}
        </p>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트 — IssueLinksPanel
// ─────────────────────────────────────────────────────────────────────────────

interface IssueLinksPanelProps {
  /** 이슈 키 (예: "ATLAS-1") */
  issueKey: string
  /**
   * 현재 부모 이슈 (이슈 상세 쿼리 유래).
   * null = 부모 없음, undefined = 이슈 데이터 로딩 전.
   */
  parent?: { key: string; summary: string } | null
  /**
   * 소속 에픽 정보 (이슈 상세 쿼리 유래 — issueResponseSchema.epic).
   * null = 에픽 미지정, undefined = 이슈 데이터 로딩 전.
   * showEpicSection=true일 때만 사용한다.
   */
  epic?: { key: string; summary: string } | null
  /**
   * 소속 에픽 섹션 노출 여부.
   * - true: level-0 일반 이슈(bug/story/task 등) — 에픽 지정/해제 가능
   * - false(기본): 에픽 타입(typeKey==='epic')과 서브태스크(typeKey==='subtask')는 비노출
   *   에픽은 EpicChildrenSection이 담당, 서브태스크는 직접 에픽 부여 금지 (Jira 모델)
   */
  showEpicSection?: boolean
  /** 비활성화 여부 (권한 없음 시 true) */
  disabled?: boolean
}

/**
 * 이슈 링크 패널 컨테이너 컴포넌트.
 *
 * 자체 `useIssueLinks` 쿼리 + mutation 훅 보유 — VersionMultiSelect 같은
 * 순수 presentational 아님. 링크는 자체 쿼리라 route 배선 최소화.
 *
 * - outward/inward 링크 목록 표시
 * - 링크 추가 폼 (유형 select + 대상 키 input + 추가 버튼)
 * - 링크 행 제거 버튼
 * - 부모 이슈 섹션 (설정/해제)
 * - 소속 에픽 섹션 (showEpicSection=true일 때만 렌더)
 * - 에러는 인라인 표시 (토스트 아님) — 성공은 토스트
 *
 * @param issueKey 이슈 키
 * @param parent 현재 부모 이슈 ({key, summary} | null | undefined)
 * @param epic 소속 에픽 정보 ({key, summary} | null | undefined)
 * @param showEpicSection 소속 에픽 섹션 노출 여부 (기본 false — epic/subtask 비노출)
 * @param disabled 비활성화 여부
 */
export function IssueLinksPanel({
  issueKey,
  parent,
  epic,
  showEpicSection = false,
  disabled = false,
}: IssueLinksPanelProps): JSX.Element {
  const { data: linkList, isLoading } = useIssueLinks(issueKey)
  const { mutate: deleteLink } = useDeleteLink(issueKey)

  const outward = linkList?.outward ?? []
  const inward = linkList?.inward ?? []
  const hasLinks = outward.length > 0 || inward.length > 0

  function handleRemove(linkId: number): void {
    deleteLink(linkId, {
      onSuccess: () => {
        toast.success(issueLinkStrings.removeLinkSuccess)
      },
      onError: (error: unknown) => {
        const code = extractLinkErrorCode(error)
        toast.error(resolveErrorMessage(code, LINK_ERROR_MESSAGES))
      },
    })
  }

  return (
    <div className="flex flex-col gap-0">
      {/* ── 링크 섹션 ─────────────────────────────────────────────────── */}
      <div className="px-3.5 py-3 border-b border-border" data-testid="links-section">
        <p className="text-xs text-muted-foreground mb-2">
          {issueLinkStrings.linksSectionTitle}
        </p>

        {/* 링크 목록 */}
        {isLoading ? (
          <p className="text-sm text-muted-foreground">{issueLinkStrings.loadingState}</p>
        ) : hasLinks ? (
          <ul className="flex flex-col divide-y divide-border">
            {outward.map((link) => (
              <LinkRow
                key={link.id}
                link={link}
                onRemove={handleRemove}
                disabled={disabled}
              />
            ))}
            {inward.map((link) => (
              <LinkRow
                key={link.id}
                link={link}
                onRemove={handleRemove}
                disabled={disabled}
              />
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted-foreground">{issueLinkStrings.emptyState}</p>
        )}

        {/* 링크 추가 폼 */}
        <AddLinkForm issueKey={issueKey} disabled={disabled} />
      </div>

      {/* ── 부모 이슈 섹션 ───────────────────────────────────────────── */}
      <div className="px-3.5 py-3 border-b border-border" data-testid="parent-section">
        <p className="text-xs text-muted-foreground mb-2">
          {issueLinkStrings.parentSectionTitle}
        </p>
        <ParentSection
          issueKey={issueKey}
          parent={parent ?? null}
          disabled={disabled}
        />
      </div>

      {/* ── 소속 에픽 섹션 — level-0 이슈(epic/subtask 제외)에만 노출 ── */}
      {showEpicSection && (
        <div className="px-3.5 py-3 border-b border-border" data-testid="epic-section">
          <p className="text-xs text-muted-foreground mb-2">
            {issueLinkStrings.epicSectionTitle}
          </p>
          <EpicSection
            issueKey={issueKey}
            epic={epic ?? null}
            disabled={disabled}
          />
        </div>
      )}
    </div>
  )
}
