// 이슈 이동 「이동」 버튼 활성 판정 — 루트와 모든 자식 매핑이 유효한지 보는 순수 함수

import type { MovePreview } from '@/api/issue-move'
import { isNodeMappingValid, type NodeMappingState } from '@/components/issues/NodeMappingSection'

/**
 * 「이동」 버튼을 누를 수 있는 상태인지 판정한다 — 루트와 **모든 자식**의 매핑이 유효해야 한다.
 *
 * 상태를 읽기만 하고 쓰지 않아 컴포넌트 밖에 둔다. 렌더마다 재생성되지 않고,
 * DOM 없이 직접 테스트된다 — `MoveIssueDialog.test.tsx` T4-10 이 그렇게 잰다.
 *
 * **왜 컴포넌트 파일이 아니라 여기인가.** `.tsx` 컴포넌트 파일에서 비-컴포넌트를 export 하면
 * `react-refresh/only-export-components` 가 발화하고, `--max-warnings 0` 인 pre-commit 이
 * 커밋을 막는다(2026-08-12 실측). Fast Refresh 를 깨지 않으려면 순수 함수의 거처는 `lib/` 다.
 */
export function isMoveEnabled(
  preview: MovePreview | null,
  rootMapping: NodeMappingState | null,
  subtaskMappings: Record<string, NodeMappingState>,
): boolean {
  if (preview === null || rootMapping === null) return false

  // 루트 유효성
  if (!isNodeMappingValid(
    rootMapping,
    preview.workflow.compatible,
    preview.customFields.requiredMissing,
  )) return false

  // 자식 유효성
  for (const child of preview.subtasks) {
    const childMapping = subtaskMappings[child.issueKey]
    if (childMapping === undefined) return false
    if (!isNodeMappingValid(
      childMapping,
      child.workflow.compatible,
      child.customFields.requiredMissing,
    )) return false
  }

  return true
}
