// 초안·발행 MSW 의 인메모리 저장소 — 초안 · 버전 · 상태별 잔여 이슈를 한 출처로 둔다
import type { DraftDefinition } from '@/api/workflows-draft.types'
import { resetBulkOperationState } from './bulk-operation-handlers'

/**
 * 워크플로우별 저장된 초안.
 *
 * ★ 앵커(`baseVersion`)는 **write-once** 다. 두 번째 저장이 다른 값을 실어 보내도 갱신하지
 * 않는다 — 백엔드 `WorkflowDraftRepository.upsert` 가 `DO UPDATE` 에서 그 컬럼을 빼는 것을
 * 그대로 재현한다. 목이 갱신해 주면 「재시도로는 안 풀린다」는 계약을 E2E 가 못 잰다.
 */
export const draftStore = new Map<string, { definition: DraftDefinition; baseVersion: number }>()

/** 워크플로우별 현재 발행 버전. 발행마다 1 오른다. */
export const versionStore = new Map<string, number>()

/** 발행 회차. `workflow_publications.version_no` 에 해당한다. */
export const publicationStore = new Map<string, number>()

/**
 * 상태 키별 남은 이슈 수.
 *
 * 발행이 막히는 근거이자 이관이 옮길 대상이다. 이관이 완료되면 0 으로 내린다 — 그러지 않으면
 * 이관 뒤 발행이 계속 409 라 E2E 가 끝나지 않는다.
 */
export const pendingIssueStore = new Map<string, number>()

/**
 * 발행본의 다이어그램 좌표 — 실서버 `workflow_statuses.layout_x`/`layout_y` 에 대응한다 (D8).
 *
 * ★ **이 저장소가 없으면 좌표 왕복이 목에서 끊긴다.** 프론트 `WorkflowView`(발행본)에는 좌표
 * 필드가 없어서, 발행할 때 좌표가 증발하고 초안을 새로 뜰 때 되읽을 것이 없다. 목이 서버보다
 * 부족하면 프로덕션이 아니라 **E2E 가 그 자리에서 못 선다.**
 *
 * ★★ 핸들러 파일이 아니라 **여기** 두는 이유는 [resetWorkflowDraftStore] 가 비울 수 있어야 하기
 * 때문이다. 핸들러 안 모듈 비공개로 두면 테스트 간에 좌표가 새어, 앞 테스트가 발행한 배치를
 * 뒤 테스트의 `GET /draft` 가 되읽는다 — 옆 저장소들과 출처를 같게 둔다.
 *
 * 키는 워크플로우 키, 값은 상태 키 → 좌표.
 */
export const publishedLayouts = new Map<string, Map<string, { x: number | null; y: number | null }>>()

/**
 * 기본값 YAML 이 있는 워크플로우.
 *
 * 백엔드는 `origin='SEED'` 이고 classpath 에 YAML 이 있을 때만 복원을 허용한다. 목은 그 둘을
 * 이 집합 하나로 흉내 낸다.
 */
export const seedWorkflowKeys = new Set<string>(['software-default', 'bug-tracking', 'simple', 'kanban-basic'])

/** 저장소를 초기 상태로 되돌린다. `test/setup.ts` 의 afterEach 가 부른다. */
export function resetWorkflowDraftStore(): void {
  draftStore.clear()
  versionStore.clear()
  publicationStore.clear()
  pendingIssueStore.clear()
  publishedLayouts.clear()
  // 이 파일의 `POST /publish/migrate` 핸들러가 `bulk-operation-handlers.ts` 의 스토어에 이관
  // 작업을 등록한다(registerStatusMigration) — `test/setup.ts` 는 그 스토어를 직접 모르므로
  // (resetBulkOperationState 는 전용 테스트 파일만 부른다) 여기서 함께 비워야 이관 작업 id 가
  // 다음 테스트로 새지 않는다.
  resetBulkOperationState()

  // 픽스처 워크플로우의 시작 버전. 백엔드 `workflows.version` 에 해당한다.
  versionStore.set('software-default', 4)
  versionStore.set('bug-tracking', 1)
  versionStore.set('simple', 1)
  versionStore.set('kanban-basic', 1)

  // `done` 상태에 이슈가 남아 있다 — 그 상태를 빼고 발행하면 마법사가 떠야 한다.
  // 남아 있는 이슈가 없으면 D7 의 정본 시나리오를 한 번도 못 태운다.
  pendingIssueStore.set('done', 3)
}

/** 현재 버전. 모르는 키는 1 로 본다. */
export function versionOf(key: string): number {
  return versionStore.get(key) ?? 1
}

resetWorkflowDraftStore()
