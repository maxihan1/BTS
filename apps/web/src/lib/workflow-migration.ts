// 상태 이관 마법사의 도착지 선택 상태와 시작 가능 판정 — 순수 함수 (FR-WF-07 D6b)
import type { DraftDefinition, StatusMappingExceptionInput, StatusMappingInput } from '@/api/workflows-draft.types'
import type { EditableDraft } from '@/lib/workflow-draft'
import { migrationTargets, removedStatusKeys, toMappingInputs } from '@/lib/workflow-draft'

/**
 * 마법사가 화면에서 고른 선택 — 사라지는 상태 키(removedKey) → 옮겨 갈 상태 키(targetKey).
 *
 * 아직 고르지 않은 사라지는 상태는 이 객체에 키가 없다(값을 `undefined` 로 넣지 않는다).
 */
export type MigrationSelection = Record<string, string>

/**
 * 이관을 시작할 수 있는지 판정한다 — 빠지는 상태마다 도착지를 **각각** 골라야 한다(Jira J7).
 *
 * 사라지는 상태가 하나라도 선택되지 않았거나, 고른 도착지가 [migrationTargets] 후보 밖이면
 * 시작할 수 없다. 후보 제한은 서버 F16 을 화면이 먼저 지키는 것 — 초안에 없는(아직 발행되지
 * 않은) 상태를 골라 보내면 서버가 400 을 준다.
 */
export function canStartMigration(
  selection: MigrationSelection,
  draft: EditableDraft,
  published: DraftDefinition,
): boolean {
  const removed = removedStatusKeys(draft, published)
  const validTargets = new Set(migrationTargets(draft, published).map((state) => state.key))

  return removed.every((key) => {
    const target = selection[key]
    return target !== undefined && validTargets.has(target)
  })
}

/** 선택 상태를 서버 이관 요청 형태로 옮긴다 — [toMappingInputs] 위임. */
export function toMigrationRequest(selection: MigrationSelection): StatusMappingInput[] {
  return toMappingInputs(selection)
}

// ─────────────────────────────────────────────────────────────────────────────
// 기본 대상 제안 · 예외 (R11C · Jira H7)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사라지는 상태마다 같은 범주의 첫 도착지 후보를 기본으로 제안한다(Jira 편집기 모달과 같다).
 * 같은 범주가 없으면 제안하지 않는다 — 고르게 둔다.
 */
export function suggestedSelection(draft: EditableDraft, published: DraftDefinition): MigrationSelection {
  const targets = migrationTargets(draft, published)
  const suggested: MigrationSelection = {}
  for (const key of removedStatusKeys(draft, published)) {
    const category = published.states.find((s) => s.key === key)?.category
    const target = targets.find((t) => t.category === category)
    if (target !== undefined) suggested[key] = target.key
  }
  return suggested
}

/** 화면에서 편집 중인 예외 한 줄. 아직 고르지 않은 칸은 빈 문자열 · null 이다. */
export interface ExceptionDraft {
  fromStatusKey: string
  projectKey: string
  issueTypeId: number | null
  toStatusKey: string
}

/** 이 프로젝트에서 이 워크플로우가 맡는 업무 유형 — `exceptListed` 면 목록 **밖의** 유형 전부. */
export function scopedIssueTypes<T extends { id: number }>(
  scope: { issueTypeIds: readonly number[]; exceptListed: boolean } | undefined,
  issueTypes: readonly T[],
): T[] {
  if (scope === undefined) return []
  return issueTypes.filter((t) => scope.issueTypeIds.includes(t.id) !== scope.exceptListed)
}

/** 모든 칸이 차 있고 (프로젝트, 유형, 현재 상태)가 겹치지 않는가 — 서버 C2 의 화면 쪽 판정. */
export function exceptionsComplete(rows: readonly ExceptionDraft[]): boolean {
  const filled = rows.every(
    (r) => r.fromStatusKey !== '' && r.projectKey !== '' && r.issueTypeId !== null && r.toStatusKey !== '',
  )
  const slots = new Set(rows.map((r) => `${r.projectKey}:${String(r.issueTypeId)}:${r.fromStatusKey}`))
  return filled && slots.size === rows.length
}

/** 편집 중인 예외를 서버 요청 형태로 옮긴다. 빈 칸이 남은 줄은 뺀다 — [exceptionsComplete] 가 먼저 막는다. */
export function toExceptionRequest(rows: readonly ExceptionDraft[]): StatusMappingExceptionInput[] {
  return rows.flatMap((r) =>
    r.issueTypeId === null
      ? []
      : [{ projectKey: r.projectKey, issueTypeId: r.issueTypeId, fromStatusKey: r.fromStatusKey, toStatusKey: r.toStatusKey }],
  )
}
