// 자동화 룰 실행 이력 trace 행 — 요약 배지 + 펼침 상세(액션 outcome·triggerEvent JSON) + 인라인 재실행 확인 (FR-AT-05 D6/D7)
import { useState } from 'react'
import type { JSX } from 'react'
import { Button } from '@/components/ui/button'
import { useRuleExecutionDetail, useReplayRuleExecution } from '@/api/useAutomationExecutions'
import type {
  RuleExecutionSummary,
  RuleExecutionDetail,
  RuleExecutionStatus,
  ActionOutcome,
} from '@/api/automation-executions.types'

// ─────────────────────────────────────────────────────────────────────────────
// 라벨/상수 — BC 내 고정 한국어 (AutomationRuleList.tsx 관례, 별도 i18n 파일 미도입)
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  noIssue: '이슈 없음',
  replayedBadge: '재실행됨',
  detailLoading: '실행 상세 로딩 중',
  detailError: '실행 이력을 찾을 수 없습니다',
  outcomesHeading: '액션 결과',
  triggerEventHeading: '트리거 이벤트',
  replayButton: '재실행',
  replayConfirmMessage: '실제 이슈 변경이 발생합니다',
  replayConfirmButton: '확정',
  replayCancelButton: '취소',
  actionSuccess: '성공',
  actionFailure: '실패',
} as const

/**
 * 실행 이력 상태 → 배지 색/한국어 라벨.
 * 색상을 상태 구분의 유일한 단서로 쓰지 않도록(NFR1) 텍스트 라벨을 항상 함께 렌더한다.
 */
const STATUS_BADGE: Record<RuleExecutionStatus, { readonly label: string; readonly className: string }> = {
  SUCCESS: { label: '성공', className: 'bg-green-100 text-green-800' },
  PARTIAL: { label: '부분 성공', className: 'bg-amber-100 text-amber-800' },
  FAILED: { label: '실패', className: 'bg-red-100 text-red-800' },
  SKIPPED: { label: '조건 불충족', className: 'bg-muted text-muted-foreground' },
}

/**
 * 트리거 타입 → 한국어 라벨.
 *
 * `AutomationRuleList.tsx`에 동형 맵(`triggerTypeLabels`)이 있으나 export되지 않고, 이 Task의
 * 허용 파일 목록에 그 파일이 없어 export를 추가할 수도 없다. 게다가 키 타입도 다르다 — 그쪽은
 * 닫힌 enum `TriggerType` 기준이고, 여기 `RuleExecutionSummary.triggerType`은 backend가
 * `TriggerType.name`을 느슨한 `z.string()`으로 내려주는 값이라(automation-executions.types.ts
 * 주석 참고) 향후 추가된 트리거 타입도 허용해야 한다. 그래서 라벨 값 자체는 그쪽과 맞추되, 이
 * 컴포넌트에 별도로 정의하고 미지 값은 원문을 그대로 fallback한다.
 */
const TRIGGER_TYPE_LABELS: Record<string, string> = {
  ISSUE_CREATED: '생성',
  ISSUE_UPDATED: '수정',
  ISSUE_COMMENTED: '댓글',
  SCHEDULED: '스케줄',
  WEBHOOK: '웹훅',
  PR_MERGED: 'PR 병합',
}

/** 트리거 타입 라벨 조회 — 미지 값은 원문 그대로 fallback */
function triggerTypeLabel(triggerType: string): string {
  return TRIGGER_TYPE_LABELS[triggerType] ?? triggerType
}

/** 배지 공통 클래스 (AutomationRuleList.tsx RuleBadge 동형) */
const BADGE_BASE_CLASS = 'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium'

// ─────────────────────────────────────────────────────────────────────────────
// RuleExecutionSummaryButton — 요약 행(항상 표시) + 펼침 토글
// ─────────────────────────────────────────────────────────────────────────────

interface RuleExecutionSummaryButtonProps {
  readonly execution: RuleExecutionSummary
  readonly expanded: boolean
  readonly onToggle: () => void
}

/**
 * 요약 행 — status 배지(색+텍스트)·트리거 라벨·issueKey·성공/전체 카운트·재실행됨 표식.
 * 행 전체가 펼침 토글 버튼(aria-expanded, button semantics)이다.
 */
function RuleExecutionSummaryButton({ execution, expanded, onToggle }: RuleExecutionSummaryButtonProps): JSX.Element {
  const statusBadge = STATUS_BADGE[execution.status]

  return (
    <button
      type="button"
      aria-expanded={expanded}
      onClick={onToggle}
      className="flex w-full flex-wrap items-center gap-2 px-4 py-3 text-left"
    >
      <span className={`${BADGE_BASE_CLASS} ${statusBadge.className}`}>{statusBadge.label}</span>
      <span className="text-xs text-muted-foreground">{triggerTypeLabel(execution.triggerType)}</span>
      <span className="text-sm">{execution.issueKey ?? labels.noIssue}</span>
      <span className="text-xs text-muted-foreground">
        {execution.successCount}/{execution.actionCount} 성공
      </span>
      {execution.replayedFrom !== null && (
        <span className="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">{labels.replayedBadge}</span>
      )}
    </button>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RuleExecutionOutcomeRow — 액션 1건 결과 행
// ─────────────────────────────────────────────────────────────────────────────

interface RuleExecutionOutcomeRowProps {
  readonly outcome: ActionOutcome
}

/** 액션 1건의 성공/실패 결과 — 아이콘(✓/✗)은 aria-hidden, 스크린리더용 텍스트를 별도로 둔다(NFR1) */
function RuleExecutionOutcomeRow({ outcome }: RuleExecutionOutcomeRowProps): JSX.Element {
  return (
    <li className="flex items-center gap-2 text-xs">
      <span className={outcome.success ? 'text-green-700' : 'text-red-700'} aria-hidden="true">
        {outcome.success ? '✓' : '✗'}
      </span>
      <span className="sr-only">{outcome.success ? labels.actionSuccess : labels.actionFailure}</span>
      <span className="font-medium">{outcome.actionType}</span>
      {!outcome.success && outcome.error !== null && <span className="text-destructive">{outcome.error}</span>}
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RuleExecutionTraceDetail — 펼침 상세(outcomes + triggerEvent JSON)
// ─────────────────────────────────────────────────────────────────────────────

interface RuleExecutionTraceDetailProps {
  readonly detail: RuleExecutionDetail
}

/** 액션별 결과(position 순) + triggerEvent 원문 JSON pretty-print */
function RuleExecutionTraceDetail({ detail }: RuleExecutionTraceDetailProps): JSX.Element {
  const orderedOutcomes = [...detail.outcomes].sort((a, b) => a.position - b.position)

  return (
    <div className="space-y-3">
      <div>
        <h4 className="text-xs font-semibold text-muted-foreground">{labels.outcomesHeading}</h4>
        <ul className="mt-1 space-y-1">
          {orderedOutcomes.map((outcome) => (
            <RuleExecutionOutcomeRow key={outcome.position} outcome={outcome} />
          ))}
        </ul>
      </div>
      <div>
        <h4 className="text-xs font-semibold text-muted-foreground">{labels.triggerEventHeading}</h4>
        <pre className="mt-1 max-h-48 overflow-auto rounded bg-muted p-2 text-xs">
          {JSON.stringify(detail.triggerEvent, null, 2)}
        </pre>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RuleExecutionReplaySection — 재실행 버튼 + 인라인 2단계 확인
// ─────────────────────────────────────────────────────────────────────────────

interface RuleExecutionReplaySectionProps {
  readonly confirming: boolean
  readonly isPending: boolean
  readonly onReplayClick: () => void
  readonly onConfirm: () => void
  readonly onCancel: () => void
}

/**
 * 재실행 버튼 + 인라인 2단계 확인(중첩 Dialog 아님, PatList.tsx 인라인 확인 관례 미러).
 * 확인 전에는 "재실행" 버튼만, 클릭하면 경고 문구 + 확정/취소 버튼으로 전환된다.
 */
function RuleExecutionReplaySection({
  confirming,
  isPending,
  onReplayClick,
  onConfirm,
  onCancel,
}: RuleExecutionReplaySectionProps): JSX.Element {
  if (!confirming) {
    return (
      <Button variant="outline" size="sm" onClick={onReplayClick}>
        {labels.replayButton}
      </Button>
    )
  }

  return (
    <div className="space-y-2 rounded-md border border-destructive/20 bg-destructive/5 p-3">
      <p className="text-sm">{labels.replayConfirmMessage}</p>
      <div className="flex gap-2">
        <Button variant="destructive" size="sm" disabled={isPending} onClick={onConfirm}>
          {labels.replayConfirmButton}
        </Button>
        <Button variant="outline" size="sm" disabled={isPending} onClick={onCancel}>
          {labels.replayCancelButton}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RuleExecutionTraceRow
// ─────────────────────────────────────────────────────────────────────────────

/** RuleExecutionTraceRow props */
export interface RuleExecutionTraceRowProps {
  /** 목록 요약 행 데이터 */
  readonly execution: RuleExecutionSummary
  /** 프로젝트 키 */
  readonly projectKey: string
  /** 자동화 룰 UUID */
  readonly ruleId: string
  /** 재실행 성공 시 콜백 — 부모(Dialog)가 토스트+자동펼침을 처리 */
  readonly onReplaySuccess?: (newExecution: RuleExecutionDetail) => void
  /** 재실행 실패 시 콜백 */
  readonly onReplayError?: (error: unknown) => void
  /**
   * 초기 펼침 여부 (기본 false). 부모(Dialog)가 재실행 직후 새로 prepend된 행에만
   * true를 전달해 자동 펼침을 구현한다 — 마운트 시 최초 1회만 반영되는 초기값이므로,
   * 이미 마운트된 행에는 이 prop이 바뀌어도 영향이 없다(호출부가 `key`로 재마운트해야 한다).
   */
  readonly defaultExpanded?: boolean
}

/**
 * 자동화 룰 실행 이력 1건의 trace 행.
 *
 * - 요약 행은 항상 표시된다(status 배지·트리거 라벨·issueKey·성공/전체 카운트·재실행됨 표식).
 *   행 전체가 펼침 토글 버튼(aria-expanded)이다.
 * - 펼치면 `useRuleExecutionDetail(execution.id, { enabled: expanded })`로 상세를 조회한다 —
 *   접힌 행은 조회하지 않는다. 로딩 중엔 인라인 로딩 표시, 실패(404 등)면 인라인 에러 문구를
 *   보여주되 행 자체는 유지한다.
 * - 재실행은 펼친 영역에서만 가능하다. 버튼 클릭 → 인라인 2단계 확인(중첩 Dialog 아님) →
 *   확정 시 `useReplayRuleExecution(projectKey, ruleId).mutate(execution.id)`. 진행 중엔
 *   확정/취소 버튼을 disabled 처리한다. 성공하면 `onReplaySuccess`, 실패하면 `onReplayError`를
 *   호출한다(캐시 갱신·토스트·자동펼침은 이 컴포넌트가 아니라 호출부(Dialog)의 책임).
 *
 * @param execution 목록 요약 행 데이터
 * @param projectKey 프로젝트 키
 * @param ruleId 자동화 룰 UUID
 * @param onReplaySuccess 재실행 성공 콜백
 * @param onReplayError 재실행 실패 콜백
 * @param defaultExpanded 초기 펼침 여부(기본 false) — 재실행 직후 자동펼침 통합용
 */
export function RuleExecutionTraceRow({
  execution,
  projectKey,
  ruleId,
  onReplaySuccess,
  onReplayError,
  defaultExpanded = false,
}: RuleExecutionTraceRowProps): JSX.Element {
  const [expanded, setExpanded] = useState(defaultExpanded)
  const [confirmingReplay, setConfirmingReplay] = useState(false)

  const detailQuery = useRuleExecutionDetail(execution.id, { enabled: expanded })
  const replayMutation = useReplayRuleExecution(projectKey, ruleId)

  function handleToggle(): void {
    setExpanded((prev) => !prev)
  }

  function handleReplayClick(): void {
    setConfirmingReplay(true)
  }

  function handleReplayCancel(): void {
    setConfirmingReplay(false)
  }

  function handleReplayConfirm(): void {
    replayMutation.mutate(execution.id, {
      onSuccess: (newDetail) => {
        setConfirmingReplay(false)
        onReplaySuccess?.(newDetail)
      },
      onError: (error) => {
        setConfirmingReplay(false)
        onReplayError?.(error)
      },
    })
  }

  return (
    <li className="rounded-md border">
      <RuleExecutionSummaryButton execution={execution} expanded={expanded} onToggle={handleToggle} />

      {expanded && (
        <div className="space-y-3 border-t px-4 py-3">
          {detailQuery.isLoading && (
            <p role="status" className="text-sm text-muted-foreground">
              {labels.detailLoading}
            </p>
          )}
          {detailQuery.isError && <p className="text-sm text-destructive">{labels.detailError}</p>}
          {detailQuery.data !== undefined && <RuleExecutionTraceDetail detail={detailQuery.data} />}

          <RuleExecutionReplaySection
            confirming={confirmingReplay}
            isPending={replayMutation.isPending}
            onReplayClick={handleReplayClick}
            onConfirm={handleReplayConfirm}
            onCancel={handleReplayCancel}
          />
        </div>
      )}
    </li>
  )
}
