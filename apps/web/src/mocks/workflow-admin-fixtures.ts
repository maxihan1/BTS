// 워크플로우 관리 MSW 의 인메모리 저장소 — 쓰기가 GET 에 보이게 하는 단일 출처
import type { WorkflowView } from '@/api/workflows'
import type { StatusCatalogEntry } from '@/api/workflows-admin'
import { allWorkflowFixtures } from './workflow-fixtures'

/**
 * 워크플로우 저장소.
 *
 * ★ `workflow-handlers.ts` 의 GET 도 **이 저장소를 읽는다**. 목이 정적 픽스처를 그대로
 * 돌려주면 쓰기 결과가 조회에 안 보여, 편집기가 「저장했는데 화면이 그대로」가 된다 —
 * 목 안에서 출처가 둘로 갈리는 자리다.
 */
export const workflowStore = new Map<string, WorkflowView>()

/** 전역 상태 카탈로그 저장소 — key 로 찾는다 */
export const statusCatalogStore = new Map<string, StatusCatalogEntry>()

/** 목 안에서 새로 만든 전환의 일련번호 — 리셋 때 함께 되돌린다 */
let generatedSeq = 0

/**
 * 목 전용 결정적 UUID.
 *
 * 실제 값은 DB 가 정한다. 목은 실행마다 같아야 E2E·스냅샷이 재현되므로 접두와 일련번호로
 * 합성한다. RFC4122 v4 형식(version=4, variant=8) — `z.string().uuid()` 통과 보장.
 */
function mockUuid(prefix: string, seq: number): string {
  return `${prefix}-0000-4000-8000-${`${seq}`.padStart(12, '0')}`
}

/** 상태 key → 결정적 id. 같은 key 는 언제나 같은 id 다. */
function statusIdFor(key: string, seq: number): string {
  return mockUuid(`aaaa${`${seq}`.padStart(4, '0')}`, seq)
}

/** 새로 만드는 전환의 id */
export function nextTransitionId(): string {
  generatedSeq += 1
  return mockUuid('bbbbbbbb', generatedSeq)
}

/** 새로 만드는 상태의 id */
export function nextStatusId(): string {
  generatedSeq += 1
  return mockUuid('cccccccc', generatedSeq)
}

/**
 * 저장소를 픽스처 상태로 되돌린다.
 *
 * 테스트 간 누수를 막는 유일한 장치다 — `test/setup.ts` 의 `afterEach` 가 부른다.
 * 카탈로그는 픽스처 워크플로우가 쓰는 상태의 **합집합**이다. 편성 추가가 고를 대상이
 * 있으려면 어떤 워크플로우에도 안 쓰이는 상태가 최소 하나는 있어야 하므로 `blocked` 를
 * 하나 더 넣는다.
 */
export function resetWorkflowAdminStore(): void {
  workflowStore.clear()
  statusCatalogStore.clear()
  generatedSeq = 0

  for (const fixture of allWorkflowFixtures) {
    workflowStore.set(fixture.key, structuredClone(fixture))
  }

  let seq = 0
  const seen = new Set<string>()
  for (const workflow of allWorkflowFixtures) {
    for (const state of workflow.states) {
      if (seen.has(state.key)) {
        continue
      }
      seen.add(state.key)
      seq += 1
      statusCatalogStore.set(state.key, {
        id: statusIdFor(state.key, seq),
        key: state.key,
        name: state.name,
        description: null,
        category: state.category,
        isSystem: true,
      })
    }
  }

  // 어떤 픽스처에도 안 쓰이는 상태 — 「편성 추가」가 고를 것이 없으면 그 경로를 한 번도
  // 못 태우고, 그것을 지키는 테스트는 도달 불가 조합을 지키는 가짜 그린이 된다.
  seq += 1
  statusCatalogStore.set('blocked', {
    id: statusIdFor('blocked', seq),
    key: 'blocked',
    name: 'Blocked',
    description: '외부 요인으로 멈춘 상태',
    category: 'IN_PROGRESS',
    isSystem: false,
  })
}

/** id 로 카탈로그 항목을 찾는다. */
export function findStatusById(statusId: string): StatusCatalogEntry | undefined {
  return [...statusCatalogStore.values()].find((s) => s.id === statusId)
}

resetWorkflowAdminStore()
