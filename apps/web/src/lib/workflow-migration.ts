// 상태 이관 마법사의 도착지 선택 상태와 시작 가능 판정 — 순수 함수 (FR-WF-07 D6b)
import type { DraftDefinition, StatusMappingInput } from '@/api/workflows-draft.types'
import type { EditableDraft } from '@/lib/workflow-draft'
import { migrationTargets, removedStatusKeys, toMappingInputs } from '@/lib/workflow-draft'

/**
 * 이관을 시작할 수 있는지 판정한다 — 빠지는 상태마다 도착지를 **각각** 골라야 한다(Jira J7).
 *
 * 사라지는 상태가 하나라도 선택되지 않았거나, 고른 도착지가 [migrationTargets] 후보 밖이면
 * 시작할 수 없다. 후보 제한은 서버 F16 을 화면이 먼저 지키는 것 — 초안에 없는(아직 발행되지
 * 않은) 상태를 골라 보내면 서버가 400 을 준다.
 */
export function canStartMigration(
  selection: Record<string, string>,
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
export function toMigrationRequest(selection: Record<string, string>): StatusMappingInput[] {
  return toMappingInputs(selection)
}
