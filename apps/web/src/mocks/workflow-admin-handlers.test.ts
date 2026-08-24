// 워크플로우 관리 MSW 핸들러 판별식 — 쓰기가 GET 에 실제로 보이는지(두 출처 방지)
import { describe, it, expect, beforeEach } from 'vitest'
import { server } from '@/test/server'
import { fetchWorkflows, fetchWorkflow } from '@/api/workflows'
import {
  fetchStatuses,
  createWorkflow,
  updateWorkflow,
  deleteWorkflow,
  duplicateWorkflow,
  addWorkflowStatus,
  removeWorkflowStatus,
  reorderWorkflowStatuses,
  createTransition,
  updateTransition,
  deleteTransition,
} from '@/api/workflows-admin'
import { resetWorkflowAdminStore } from './workflow-admin-fixtures'
import { workflowHandlers } from './workflow-handlers'
import { workflowAdminHandlers } from './workflow-admin-handlers'

// `test/server.ts` 가 쓰는 `test/handlers.ts` 는 refresh 하나뿐인 최소 집합이다
// (전체 목록 `mocks/handlers.ts` 는 브라우저·E2E 전용). 유닛에서는 이렇게 직접 건다.
beforeEach(() => {
  resetWorkflowAdminStore()
  server.use(...workflowHandlers, ...workflowAdminHandlers)
})

describe('상태 카탈로그', () => {
  it('픽스처 워크플로우의 상태 전부가 카탈로그에 있다', async () => {
    const [catalog, workflows] = await Promise.all([fetchStatuses(), fetchWorkflows()])
    const usedKeys = new Set(workflows.flatMap((w) => w.states.map((s) => s.key)))
    const catalogKeys = new Set(catalog.map((s) => s.key))
    expect([...usedKeys].filter((k) => !catalogKeys.has(k))).toEqual([])
  })

  it('비-공허: 카탈로그가 비어 있지 않다', async () => {
    expect((await fetchStatuses()).length).toBeGreaterThan(0)
  })
})

describe('워크플로우 쓰기가 GET 에 보인다', () => {
  it('이름을 고치면 단건 조회가 새 이름을 준다', async () => {
    await updateWorkflow('software-default', { name: '고친 이름', description: null })
    expect((await fetchWorkflow('software-default')).name).toBe('고친 이름')
  })

  it('만들면 목록에 늘어난다', async () => {
    const before = (await fetchWorkflows()).length
    await createWorkflow({
      key: 'brand-new',
      name: '새 워크플로우',
      description: null,
      statuses: [{ key: 'open', name: 'Open', category: 'TODO', displayOrder: 1 }],
    })
    expect((await fetchWorkflows()).length).toBe(before + 1)
  })

  it('지우면 목록에서 사라지고 단건 조회가 404 다', async () => {
    await deleteWorkflow('simple')
    expect((await fetchWorkflows()).some((w) => w.key === 'simple')).toBe(false)
    await expect(fetchWorkflow('simple')).rejects.toBeDefined()
  })

  it('복제하면 상태와 전환이 함께 복사된다', async () => {
    const source = await fetchWorkflow('software-default')
    await duplicateWorkflow('software-default', { key: 'copy', name: '사본' })
    const copy = await fetchWorkflow('copy')
    expect(copy.states.length).toBe(source.states.length)
    expect(copy.transitions.length).toBe(source.transitions.length)
  })
})

describe('상태 편성이 GET 에 보인다', () => {
  it('추가하면 states 가 늘어난다', async () => {
    const catalog = await fetchStatuses()
    const before = await fetchWorkflow('simple')
    const unused = catalog.find((s) => !before.states.some((st) => st.key === s.key))
    expect(unused).toBeDefined()

    await addWorkflowStatus('simple', { statusId: unused!.id, displayOrder: before.states.length + 1 })
    const after = await fetchWorkflow('simple')
    expect(after.states.map((s) => s.key)).toContain(unused!.key)
  })

  it('추가한 뒤 제거하면 states 에서 빠진다', async () => {
    // 픽스처의 기존 상태는 **전부 전환이 가리킨다** — 그래서 바로 빼면 409 다(아래 실패
    // 계약 절이 그 경로를 따로 본다). 제거의 성공 경로를 보려면 전환이 안 걸린 상태를
    // 먼저 넣어야 한다. 「추가 → 제거」가 사용자가 실제로 밟는 순서이기도 하다.
    const catalog = await fetchStatuses()
    const blocked = catalog.find((s) => s.key === 'blocked')!
    const before = await fetchWorkflow('simple')

    await addWorkflowStatus('simple', { statusId: blocked.id, displayOrder: before.states.length + 1 })
    expect((await fetchWorkflow('simple')).states.map((s) => s.key)).toContain('blocked')

    await removeWorkflowStatus('simple', blocked.id)
    expect((await fetchWorkflow('simple')).states.map((s) => s.key)).not.toContain('blocked')
  })

  it('순서를 뒤집으면 displayOrder 가 뒤집힌 순서를 따른다', async () => {
    const catalog = await fetchStatuses()
    const before = await fetchWorkflow('software-default')
    const reversedKeys = [...before.states].reverse().map((s) => s.key)
    const ids = reversedKeys.map((k) => catalog.find((s) => s.key === k)!.id)

    await reorderWorkflowStatuses('software-default', ids)
    const after = await fetchWorkflow('software-default')
    const ordered = [...after.states].sort((a, b) => a.displayOrder - b.displayOrder).map((s) => s.key)
    expect(ordered).toEqual(reversedKeys)
  })
})

describe('전환 정의가 GET 에 보인다', () => {
  it('만들면 transitions 가 늘어나고 id 가 붙는다', async () => {
    const before = await fetchWorkflow('simple')
    const created = await createTransition('simple', {
      fromStatusKey: before.states[0]!.key,
      toStatusKey: before.states[1]!.key,
      name: '새 전환',
      kind: 'NORMAL',
    })
    const after = await fetchWorkflow('simple')
    expect(after.transitions.length).toBe(before.transitions.length + 1)
    expect(after.transitions.some((t) => t.id === created.id)).toBe(true)
  })

  it('같은 상태쌍에 이름이 다른 전환을 둘 만들 수 있다 (FR-WF-05 F2)', async () => {
    const w = await fetchWorkflow('simple')
    const pair = { fromStatusKey: w.states[0]!.key, toStatusKey: w.states[1]!.key, kind: 'NORMAL' as const }
    const a = await createTransition('simple', { ...pair, name: '전환 A' })
    const b = await createTransition('simple', { ...pair, name: '전환 B' })
    expect(a.id).not.toBe(b.id)
  })

  it('이름을 고치면 GET 이 새 이름을 준다', async () => {
    const w = await fetchWorkflow('simple')
    const target = w.transitions.find((t) => t.kind === 'NORMAL')!
    await updateTransition('simple', target.id, {
      fromStatusKey: target.fromStateKey,
      toStatusKey: target.toStateKey,
      name: '바뀐 전환',
      kind: 'NORMAL',
    })
    const after = await fetchWorkflow('simple')
    expect(after.transitions.find((t) => t.id === target.id)?.name).toBe('바뀐 전환')
  })

  it('지우면 transitions 에서 빠진다', async () => {
    const w = await fetchWorkflow('simple')
    const target = w.transitions.find((t) => t.kind === 'NORMAL')!
    await deleteTransition('simple', target.id)
    expect((await fetchWorkflow('simple')).transitions.some((t) => t.id === target.id)).toBe(false)
  })
})

describe('실패 계약 — 사유가 코드로 갈린다', () => {
  it('전환이 가리키는 상태를 빼면 409 REFERENCED_BY_TRANSITION', async () => {
    const catalog = await fetchStatuses()
    const w = await fetchWorkflow('software-default')
    const referenced = w.transitions.find((t) => t.kind === 'NORMAL')!.toStateKey
    const id = catalog.find((s) => s.key === referenced)!.id
    await expect(removeWorkflowStatus('software-default', id)).rejects.toMatchObject({
      status: 409,
      errorCode: 'WORKFLOW_STATUS_REFERENCED_BY_TRANSITION',
    })
  })

  it('이미 쓰는 key 로 만들면 409 WORKFLOW_KEY_CONFLICT', async () => {
    await expect(
      createWorkflow({
        key: 'simple',
        name: '중복',
        description: null,
        statuses: [{ key: 'open', name: 'Open', category: 'TODO', displayOrder: 1 }],
      }),
    ).rejects.toMatchObject({ status: 409, errorCode: 'WORKFLOW_KEY_CONFLICT' })
  })

  it('없는 워크플로우를 고치면 404 WORKFLOW_NOT_FOUND', async () => {
    await expect(updateWorkflow('nope', { name: 'x', description: null })).rejects.toMatchObject({
      status: 404,
      errorCode: 'WORKFLOW_NOT_FOUND',
    })
  })

  it('리셋하면 앞 테스트의 변경이 남지 않는다 — 테스트 간 누수 차단', async () => {
    expect((await fetchWorkflow('software-default')).name).toBe('소프트웨어 개발 기본 워크플로우')
  })
})
