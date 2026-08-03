// 단축키 커스터마이즈 편집 폼 — 키 캡처 + 실시간 충돌 배지(백엔드 6종 규칙 로컬 복제) + 저장 (FR-PF-03 Task 9)
import type { JSX, KeyboardEvent } from 'react'
import { useEffect, useState } from 'react'
import {
  useKeymap,
  useUpdateKeymap,
  keymapConflictErrorSchema,
  KEYMAP_ACTIONS,
} from '@/api/keymap'
import type { KeymapActionId, KeymapBinding, KeymapBindingInput, KeymapConflictType } from '@/api/keymap'
import type { ApiError } from '@/api/client'
import { DEFAULT_KEYMAP, LEADER_KEY } from '@/components/keyboard-shortcuts/shortcuts'
import { CONTEXT_SHORTCUTS } from '@/components/keyboard-shortcuts/context-shortcuts'
import { keymapSettingsStrings } from '@/i18n/ko'
import { Label } from '@/components/ui/label'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 로컬 충돌 검증 — 백엔드 KeymapValidator 6종 규칙 중 5종을 순수 함수로 복제
// (화이트리스트 완비 검사는 이 폼이 항상 KEYMAP_ACTIONS 5종을 완비해 전송하므로
// 구조적으로 불가능한 위반이라 로컬에서는 생략 — 최종 방어선은 저장 시 서버 400/409)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 로컬 위반 종류.
 *
 * - `blank`/`format` — VALIDATION 계열(서버는 400)
 * - `reservedContext` — **프론트 전용**(FR-UX-10 F10). 서버 `KeymapValidator` 는 이 규칙을
 *   모른다. 컨텍스트 단축키가 영속 상태 없는 프론트 규약이라(ADR D-1) 백엔드에 알릴
 *   이유가 없고, 알리면 그 경계가 무너진다. 대신 이 폼이 유일한 게이트다 — API 직호출로는
 *   우회되지만 그 경우 컨텍스트 키가 죽을 뿐 데이터 손상은 없다(fail-safe).
 * - 나머지 — 서버 `KeymapConflictType` 과 동일
 */
type LocalViolationType = 'blank' | 'format' | 'reservedContext' | KeymapConflictType

/** 로컬(또는 서버 409) 위반 하나 — 관련 action id 목록 + (완전중복이면) 겹치는 key_combo */
interface LocalViolation {
  readonly type: LocalViolationType
  readonly actions: readonly string[]
  readonly keyCombo?: string
}

/** [keyCombo]를 leader 구분자(공백) 기준으로 나눈 토큰 — 백엔드 KeymapBinding.tokens 미러 */
function tokensOf(keyCombo: string): string[] {
  return keyCombo.split(' ')
}

/** single(1글자) 또는 "g <key>" leader(공백 1칸, 2토큰) 형식을 만족하는지 검사 */
function hasValidFormat(keyCombo: string): boolean {
  if (keyCombo.trim().length === 0) return false
  const tokens = tokensOf(keyCombo)
  if (tokens.length === 1) return keyCombo.length === 1
  if (tokens.length === 2) return tokens[0] === LEADER_KEY && tokens[1]?.length === 1
  return false
}

/** key_combo 형식에서 발화 방식을 파생한다 — 형식 유효성 자체는 검사하지 않는다 */
function triggerOf(keyCombo: string): 'single' | 'leader' {
  const tokens = tokensOf(keyCombo)
  return tokens.length === 2 && tokens[0] === LEADER_KEY ? 'leader' : 'single'
}

/** leader continuation 키(두 번째 토큰) — leader가 아니면 null */
function leaderContinuationKey(keyCombo: string): string | null {
  const tokens = tokensOf(keyCombo)
  return triggerOf(keyCombo) === 'leader' ? (tokens[1] ?? null) : null
}

/** 빈값(공백 포함) 위반 목록 */
function findBlankViolations(bindings: readonly KeymapBindingInput[]): LocalViolation[] {
  return bindings
    .filter((b) => b.keyCombo.trim().length === 0)
    .map((b) => ({ type: 'blank' as const, actions: [b.action] }))
}

/** key_combo 형식 위반 목록 — 빈 값은 findBlankViolations가 담당하므로 제외 */
function findFormatViolations(bindings: readonly KeymapBindingInput[]): LocalViolation[] {
  return bindings
    .filter((b) => b.keyCombo.trim().length > 0 && !hasValidFormat(b.keyCombo))
    .map((b) => ({ type: 'format' as const, actions: [b.action] }))
}

/** 완전 중복 위반 목록 — 같은 key_combo를 가진 action이 둘 이상 */
function findDuplicateViolations(structurallyValid: readonly KeymapBindingInput[]): LocalViolation[] {
  const byCombo = new Map<string, string[]>()
  for (const b of structurallyValid) {
    byCombo.set(b.keyCombo, [...(byCombo.get(b.keyCombo) ?? []), b.action])
  }
  return [...byCombo.entries()]
    .filter(([, actions]) => actions.length > 1)
    .map(([keyCombo, actions]) => ({ type: 'duplicate' as const, actions: [...actions].sort(), keyCombo }))
}

/** leader 접두 충돌 — single "g"와 leader("g X")가 공존하면 위반 하나 반환, 없으면 null */
function findLeaderPrefixViolation(structurallyValid: readonly KeymapBindingInput[]): LocalViolation | null {
  const singleLeaderKey = structurallyValid.find((b) => triggerOf(b.keyCombo) === 'single' && b.keyCombo === LEADER_KEY)
  const leaderBindings = structurallyValid.filter((b) => triggerOf(b.keyCombo) === 'leader')
  if (singleLeaderKey === undefined || leaderBindings.length === 0) return null
  return {
    type: 'leaderPrefix',
    actions: [singleLeaderKey.action, ...leaderBindings.map((b) => b.action)].sort(),
  }
}

/** dead leader combo("g g") 위반 목록 — continuation 키가 leader 키와 동일 */
function findDeadLeaderViolations(structurallyValid: readonly KeymapBindingInput[]): LocalViolation[] {
  return structurallyValid
    .filter((b) => triggerOf(b.keyCombo) === 'leader' && leaderContinuationKey(b.keyCombo) === LEADER_KEY)
    .map((b) => ({ type: 'deadLeader' as const, actions: [b.action] }))
}

/**
 * 컨텍스트 단축키(FR-UX-10 F10)가 선점한 키 → 그 용도 설명.
 *
 * `CONTEXT_SHORTCUTS` 에서 파생한다 — 하드코딩하면 컨텍스트 키가 늘 때 이 가드만
 * 뒤처져 조용히 뚫린다(`two-lists-never-check-each-other`).
 */
const RESERVED_CONTEXT_KEYS: ReadonlyMap<string, string> = new Map(
  CONTEXT_SHORTCUTS.map((shortcut) => [shortcut.key, shortcut.description]),
)

/**
 * 컨텍스트 단축키 예약 키 위반 목록 (FR-UX-10 F10).
 *
 * **single 트리거만 검사한다.** 컨텍스트 단축키는 전부 단일 키이고, 전역 판별이
 * 컨텍스트보다 먼저 돌기 때문에 같은 단일 키를 전역에 배정하면 목록 항법이 죽는다
 * (그런데 도움말 모달은 여전히 그 키를 광고한다). 반면 leader combo(`g j`)는 leader
 * 대기 중 컨텍스트로 넘어가지 않으므로(E6) 충돌하지 않는다 — 막을 이유가 없다.
 */
function findReservedContextViolations(
  structurallyValid: readonly KeymapBindingInput[],
): LocalViolation[] {
  return structurallyValid
    .filter((b) => triggerOf(b.keyCombo) === 'single' && RESERVED_CONTEXT_KEYS.has(b.keyCombo))
    .map((b) => ({ type: 'reservedContext' as const, actions: [b.action], keyCombo: b.keyCombo }))
}

/**
 * [bindings]를 백엔드 KeymapValidator 규칙(화이트리스트 제외 5종)
 * + 프론트 전용 예약 키 규칙 1종으로 검증한다.
 * 저장 시 최종 방어선은 서버 400/409 — 이 함수는 즉시 UI 피드백용.
 * (예약 키 규칙만은 서버가 모르므로 이 함수가 유일한 게이트다.)
 */
function detectLocalViolations(bindings: readonly KeymapBindingInput[]): LocalViolation[] {
  const structurallyValid = bindings.filter((b) => b.keyCombo.trim().length > 0 && hasValidFormat(b.keyCombo))
  const leaderPrefix = findLeaderPrefixViolation(structurallyValid)

  return [
    ...findBlankViolations(bindings),
    ...findFormatViolations(bindings),
    ...findDuplicateViolations(structurallyValid),
    ...(leaderPrefix !== null ? [leaderPrefix] : []),
    ...findDeadLeaderViolations(structurallyValid),
    ...findReservedContextViolations(structurallyValid),
  ]
}

/** action id → 위반 목록 — 한 위반이 여러 action에 걸치면 각 action에 모두 배치 */
function groupViolationsByAction(violations: readonly LocalViolation[]): Map<string, LocalViolation[]> {
  const map = new Map<string, LocalViolation[]>()
  for (const v of violations) {
    for (const action of v.actions) {
      map.set(action, [...(map.get(action) ?? []), v])
    }
  }
  return map
}

/** action id → 한국어 표시명(찾지 못하면 원본 id로 폴백) */
function actionLabelOf(action: string): string {
  const labels: Record<string, string> = keymapSettingsStrings.actionLabels
  return labels[action] ?? action
}

/** 위반 종류별 메시지 빌더 — Record 인덱싱으로 exhaustive switch 없이 안전하게 분기 */
const VIOLATION_MESSAGE_BUILDERS: Record<LocalViolationType, (keyCombo: string, actionNames: string) => string> = {
  blank: (_keyCombo, actionNames) => keymapSettingsStrings.conflictBlank(actionNames),
  format: (_keyCombo, actionNames) => keymapSettingsStrings.conflictFormat(actionNames),
  duplicate: (keyCombo, actionNames) => keymapSettingsStrings.conflictDuplicate(keyCombo, actionNames),
  leaderPrefix: (_keyCombo, actionNames) => keymapSettingsStrings.conflictLeaderPrefix(actionNames),
  deadLeader: (_keyCombo, actionNames) => keymapSettingsStrings.conflictDeadLeader(actionNames),
  reservedContext: (keyCombo, actionNames) =>
    keymapSettingsStrings.conflictReservedContext(
      keyCombo,
      RESERVED_CONTEXT_KEYS.get(keyCombo) ?? '목록 항법',
      actionNames,
    ),
}

/** 위반 하나를 사용자 노출 메시지로 변환 */
function describeViolation(v: LocalViolation): string {
  const actionNames = v.actions.map(actionLabelOf).join(', ')
  return VIOLATION_MESSAGE_BUILDERS[v.type](v.keyCombo ?? '', actionNames)
}

/**
 * mutation 실패(ApiError)에서 서버 409 KeymapConflictError의 conflicts를 안전하게 추출한다.
 * 409가 아니거나 conflicts 스키마 파싱에 실패하면(예상치 못한 에러 바디) 빈 배열 — 이 경우
 * 호출부가 일반 저장 실패 메시지로 폴백한다.
 */
function resolveServerConflicts(error: ApiError | null): LocalViolation[] {
  if (error === null || error.status !== 409) return []
  const parsed = keymapConflictErrorSchema.safeParse(error.body)
  if (!parsed.success) return []
  return parsed.data.conflicts.map((c) => ({ type: c.type, actions: c.actions, keyCombo: c.keyCombo ?? undefined }))
}

// ─────────────────────────────────────────────────────────────────────────────
// draft ↔ API 변환 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** GET 응답 bindings를 action별 편집 draft(Record)로 변환 — 누락된 action은 DEFAULT_KEYMAP으로 채움 */
function toDraftRecord(bindings: readonly KeymapBinding[]): Record<KeymapActionId, string> {
  const draft: Record<KeymapActionId, string> = { ...DEFAULT_KEYMAP }
  for (const b of bindings) {
    draft[b.action] = b.keyCombo
  }
  return draft
}

/** draft record를 action 5종 완비 KeymapBindingInput 배열로 변환(KEYMAP_ACTIONS 순서 고정) */
function toBindingInputs(drafts: Record<KeymapActionId, string>): KeymapBindingInput[] {
  return KEYMAP_ACTIONS.map((action) => ({ action, keyCombo: drafts[action] }))
}

/** 키 캡처에서 combo 자체로 취급하지 않는 수정자 키 — 눌려도 draft를 바꾸지 않는다 */
const IGNORED_MODIFIER_KEYS = new Set(['Shift', 'Control', 'Alt', 'Meta', 'CapsLock'])

// ─────────────────────────────────────────────────────────────────────────────
// action 편집 행 — 키 캡처 input + 기본값 복원 버튼 + 실시간 충돌 배지(SubscriptionCell 선례)
// ─────────────────────────────────────────────────────────────────────────────

/** {@link KeymapActionRow} props */
interface KeymapActionRowProps {
  /** 이 행이 편집하는 action id */
  readonly action: KeymapActionId
  /** 현재 draft key_combo */
  readonly value: string
  /** 이 action에 걸린 로컬 위반 목록(없으면 빈 배열) */
  readonly violations: readonly LocalViolation[]
  /** 키 캡처 handler(부모 상태 갱신) */
  readonly onCapture: (action: KeymapActionId, e: KeyboardEvent<HTMLInputElement>) => void
  /** 기본값 복원 handler(부모 상태 갱신) */
  readonly onReset: (action: KeymapActionId) => void
}

/** action 하나의 편집 행 — 키 캡처 input(aria-label로 WCAG 라벨 제공) + 복원 버튼 + 충돌 배지 */
function KeymapActionRow({ action, value, violations, onCapture, onReset }: KeymapActionRowProps): JSX.Element {
  const actionLabel = actionLabelOf(action)
  return (
    <div className="space-y-1.5 border-b pb-4 last:border-b-0">
      <div className="flex items-center justify-between gap-4">
        <Label htmlFor={`keymap-${action}`}>{actionLabel}</Label>
        <div className="flex items-center gap-2">
          <Input
            id={`keymap-${action}`}
            type="text"
            readOnly
            value={value}
            aria-label={keymapSettingsStrings.captureInputAriaLabel(actionLabel)}
            placeholder={keymapSettingsStrings.captureInputPlaceholder}
            onKeyDown={(e) => onCapture(action, e)}
            className="w-24 text-center"
          />
          <Button
            type="button"
            variant="ghost"
            size="sm"
            aria-label={keymapSettingsStrings.resetButtonAriaLabel(actionLabel)}
            onClick={() => onReset(action)}
          >
            {keymapSettingsStrings.resetButtonLabel}
          </Button>
        </div>
      </div>
      {violations.length > 0 && (
        <p role="alert" className="text-xs text-destructive">
          {violations.map(describeViolation).join(' ')}
        </p>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 단축키 커스터마이즈 편집 폼.
 *
 * action 5종의 현재 key_combo를 로드해 캡처 input으로 재배치하고, 편집 중에는 백엔드
 * 검증 규칙을 로컬 복제한 실시간 배지를 보여준다(최종 방어선은 저장 시 서버 400/409).
 * 저장은 {@link useUpdateKeymap}(invalidate-only)로 action 5종 replace-all PATCH한다.
 */
export function KeymapForm(): JSX.Element {
  const keymapQuery = useKeymap()
  const mutation = useUpdateKeymap()
  const [drafts, setDrafts] = useState<Record<KeymapActionId, string> | null>(null)
  const [pendingLeader, setPendingLeader] = useState<Partial<Record<KeymapActionId, boolean>>>({})

  useEffect(() => {
    if (keymapQuery.data !== undefined && drafts === null) {
      setDrafts(toDraftRecord(keymapQuery.data.bindings))
    }
  }, [keymapQuery.data, drafts])

  if (keymapQuery.isLoading || (keymapQuery.data !== undefined && drafts === null)) {
    return <p className="text-sm text-muted-foreground">{keymapSettingsStrings.loadingMessage}</p>
  }
  if (keymapQuery.isError || keymapQuery.data === undefined) {
    return <p className="text-sm text-destructive">{keymapSettingsStrings.loadErrorMessage}</p>
  }
  if (drafts === null) {
    // 데이터 도착 직후 useEffect가 아직 draft를 초기화하지 못한 한 프레임의 방어적 fallback
    return <p className="text-sm text-muted-foreground">{keymapSettingsStrings.loadingMessage}</p>
  }

  function handleKeyCapture(action: KeymapActionId, e: KeyboardEvent<HTMLInputElement>): void {
    if (e.key === 'Tab') return
    e.preventDefault()

    if (e.key === 'Escape') {
      setPendingLeader((prev) => ({ ...prev, [action]: false }))
      return
    }
    if (e.key === 'Enter' || IGNORED_MODIFIER_KEYS.has(e.key)) return

    // leader 대기 중이면 이번 키가 무엇이든(LEADER_KEY 자신 포함 — dead leader "g g" 캡처 허용)
    // continuation으로 소비한다. 실시간 검증(findDeadLeaderViolations)이 위반 여부를 판정한다.
    const isLeaderPending = pendingLeader[action] ?? false
    if (isLeaderPending) {
      setDrafts((prev) => (prev === null ? prev : { ...prev, [action]: `${LEADER_KEY} ${e.key}` }))
      setPendingLeader((prev) => ({ ...prev, [action]: false }))
      return
    }

    if (e.key === LEADER_KEY) {
      setPendingLeader((prev) => ({ ...prev, [action]: true }))
      return
    }

    setDrafts((prev) => (prev === null ? prev : { ...prev, [action]: e.key }))
  }

  function handleReset(action: KeymapActionId): void {
    setDrafts((prev) => (prev === null ? prev : { ...prev, [action]: DEFAULT_KEYMAP[action] }))
    setPendingLeader((prev) => ({ ...prev, [action]: false }))
  }

  function handleSave(): void {
    if (drafts === null) return
    mutation.mutate({ bindings: toBindingInputs(drafts) })
  }

  const currentBindings = toBindingInputs(drafts)
  const localViolations = detectLocalViolations(currentBindings)
  const violationsByAction = groupViolationsByAction(localViolations)

  const serverConflicts = resolveServerConflicts(mutation.error)
  const showGenericSaveError = mutation.isError && serverConflicts.length === 0

  return (
    <div className="space-y-6">
      {showGenericSaveError && (
        <p role="alert" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">
          {keymapSettingsStrings.saveErrorMessage}
        </p>
      )}

      {serverConflicts.length > 0 && (
        <div role="alert" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">
          <p className="font-medium">{keymapSettingsStrings.conflictHeading}</p>
          <ul className="mt-1 list-disc pl-5">
            {serverConflicts.map((v, idx) => (
              <li key={`${v.type}-${idx}`}>{describeViolation(v)}</li>
            ))}
          </ul>
        </div>
      )}

      {KEYMAP_ACTIONS.map((action) => (
        <KeymapActionRow
          key={action}
          action={action}
          value={drafts[action]}
          violations={violationsByAction.get(action) ?? []}
          onCapture={handleKeyCapture}
          onReset={handleReset}
        />
      ))}

      <div className="flex justify-end pt-2">
        <Button
          type="button"
          aria-label={keymapSettingsStrings.saveButtonAriaLabel}
          disabled={mutation.isPending || localViolations.length > 0}
          onClick={handleSave}
        >
          {keymapSettingsStrings.saveButtonLabel}
        </Button>
      </div>
    </div>
  )
}
