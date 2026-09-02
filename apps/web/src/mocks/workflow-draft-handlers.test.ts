// 초안·발행 MSW 계약 테스트 — 목이 서버보다 관대하면 프로덕션에서만 터진다
import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import { server } from '@/test/server'
import {
  getDraft,
  saveDraft,
  discardDraft,
  previewPublish,
  publishDraft,
  migrateStatuses,
  resetToDefault,
} from '@/api/workflows-draft'
import { fetchBulkOperation } from '@/api/bulk-operations'
import { fetchWorkflow } from '@/api/workflows'
import { WorkflowPublishMappingRequiredError } from '@/api/workflows-admin.http'
import { workflowHandlers } from './workflow-handlers'
import { resetWorkflowAdminStore } from './workflow-admin-fixtures'
import { workflowDraftHandlers } from './workflow-draft-handlers'
import { bulkOperationHandlers, LS_KEY_BULK_PARTIAL_FAIL } from './bulk-operation-handlers'
import { pendingIssueStore, resetWorkflowDraftStore } from './workflow-draft-fixtures'

const KEY = 'software-default'

beforeEach(() => {
  resetWorkflowAdminStore()
  resetWorkflowDraftStore()
  // 발행이 정규 정의를 교체하는지 보려면 조회 핸들러도 같은 저장소를 읽어야 한다.
  // 이관 진행률 폴링(GET /api/v1/bulk-operations/:id)을 재려면 그 핸들러도 함께 켠다.
  server.use(...workflowHandlers, ...workflowDraftHandlers, ...bulkOperationHandlers)
})

/** `done` 을 빼는 초안을 저장하고 그 앵커를 돌려준다 — 이관 테스트가 반복하는 준비 절차. */
async function saveDraftRemovingDone(): Promise<number> {
  return saveWith((d) => {
    d.states = d.states.filter((s) => s.key !== 'done')
    d.transitions = d.transitions.filter((t) => t.from !== 'done' && t.to !== 'done')
  })
}

/** 초안 하나를 저장한다. `mutate` 로 정의를 손봐 준다. */
async function saveWith(mutate: (d: Awaited<ReturnType<typeof getDraft>>['definition']) => void) {
  const draft = await getDraft(KEY)
  mutate(draft.definition)
  await saveDraft(KEY, draft.definition, draft.baseVersion)
  return draft.baseVersion
}

describe('초안 조회', () => {
  it('저장된 초안이 없으면 발행본을 주고 exists 는 false 다', async () => {
    const draft = await getDraft(KEY)

    expect(draft.exists).toBe(false)
    expect(draft.definition.states.length).toBeGreaterThan(0)
  })

  it('표준 워크플로우는 복원 가능이라고 알린다', async () => {
    expect((await getDraft(KEY)).canResetToDefault).toBe(true)
  })

  it('저장하면 그 초안이 조회된다', async () => {
    await saveWith((d) => {
      d.name = '고친 이름'
    })

    const draft = await getDraft(KEY)
    expect(draft.exists).toBe(true)
    expect(draft.definition.name).toBe('고친 이름')
  })
})

describe('★ 앵커 write-once', () => {
  it('두 번째 저장이 다른 앵커를 보내도 저장된 앵커는 그대로다', async () => {
    // 백엔드 upsert 의 DO UPDATE 가 base_version 을 빼는 것을 재현한다. 목이 갱신해 주면
    // 「재시도로는 409 가 안 풀린다」는 계약을 E2E 가 잴 수 없다.
    //
    // ★ **과거** 버전으로 잰다. 미래 버전은 `requireAnchorNotAhead` 가 먼저 400 으로 막아
    // write-once 판정에 닿지 못한다 — 과거 버전은 정상 경로이고, 그 초안이 발행에서 409 로
    // 막히는 것이 옳은 결말이다(백엔드 KDoc).
    const first = await getDraft(KEY)
    await saveDraft(KEY, first.definition, first.baseVersion)
    await saveDraft(KEY, first.definition, first.baseVersion - 1)

    expect((await getDraft(KEY)).baseVersion).toBe(first.baseVersion)
  })

  it('미래 버전을 앵커로 주장하면 400 이다', async () => {
    const draft = await getDraft(KEY)

    await expect(saveDraft(KEY, draft.definition, draft.baseVersion + 99)).rejects.toMatchObject({
      status: 400,
    })
  })
})

describe('초안 폐기', () => {
  it('초안이 없으면 404 다', async () => {
    // 없는 것을 지웠다고 204 를 주면 화면이 「폐기됨」을 표시하고 관리자가 오해한다.
    await expect(discardDraft(KEY)).rejects.toMatchObject({ errorCode: 'WORKFLOW_DRAFT_NOT_FOUND' })
  })

  it('폐기하면 다시 발행본이 보인다', async () => {
    await saveWith((d) => {
      d.name = '고친 이름'
    })
    await discardDraft(KEY)

    const draft = await getDraft(KEY)
    expect(draft.exists).toBe(false)
    expect(draft.definition.name).not.toBe('고친 이름')
  })
})

describe('미리보기', () => {
  it('초안이 없으면 404 가 아니라 400 이다', async () => {
    // ★ 실측 계약. requireDraft 는 WorkflowInvalidRequestException(400) 을 던진다.
    //   WORKFLOW_DRAFT_NOT_FOUND(404)는 DELETE /draft 전용이다.
    await expect(previewPublish(KEY)).rejects.toMatchObject({
      status: 400,
      errorCode: 'WORKFLOW_INVALID_REQUEST',
    })
  })

  it('빠지는 상태와 잔여 건수를 계산한다', async () => {
    await saveWith((d) => {
      d.states = d.states.filter((s) => s.key !== 'done')
      d.transitions = d.transitions.filter((t) => t.from !== 'done' && t.to !== 'done')
    })

    const preview = await previewPublish(KEY)

    expect(preview.removedStatusKeys).toContain('done')
    expect(preview.pendingIssueCounts['done']).toBe(3)
  })
})

describe('발행', () => {
  it('★ 발행해야 정규 정의가 바뀐다 — 저장만으로는 안 바뀐다', async () => {
    // 이 화면의 핵심 계약이다. FR-WF-07 이 존재하는 이유이기도 하다.
    const before = await fetchWorkflow(KEY)
    const anchor = await saveWith((d) => {
      d.name = '발행 전 이름'
    })

    expect((await fetchWorkflow(KEY)).name).toBe(before.name)

    await publishDraft(KEY, anchor)

    expect((await fetchWorkflow(KEY)).name).toBe('발행 전 이름')
  })

  it('발행이 초안 행을 지운다', async () => {
    const anchor = await saveWith((d) => {
      d.name = '고친 이름'
    })
    await publishDraft(KEY, anchor)

    expect((await getDraft(KEY)).exists).toBe(false)
  })

  it('앵커가 다르면 409 다', async () => {
    const anchor = await saveWith((d) => {
      d.name = '고친 이름'
    })

    await expect(publishDraft(KEY, anchor + 1)).rejects.toMatchObject({
      errorCode: 'WORKFLOW_VERSION_CONFLICT',
    })
  })

  it('빠지는 상태에 이슈가 남으면 409 이고 건수를 싣는다', async () => {
    const anchor = await saveWith((d) => {
      d.states = d.states.filter((s) => s.key !== 'done')
      d.transitions = d.transitions.filter((t) => t.from !== 'done' && t.to !== 'done')
    })

    const error = await publishDraft(KEY, anchor).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(WorkflowPublishMappingRequiredError)
    expect((error as WorkflowPublishMappingRequiredError).pendingIssueCounts).toEqual({ done: 3 })
  })

  it('그 상태에 이슈가 없으면 발행된다', async () => {
    pendingIssueStore.set('done', 0)
    const anchor = await saveWith((d) => {
      d.states = d.states.filter((s) => s.key !== 'done')
      d.transitions = d.transitions.filter((t) => t.from !== 'done' && t.to !== 'done')
    })

    await expect(publishDraft(KEY, anchor)).resolves.toMatchObject({ versionNo: 1 })
  })
})

describe('★ 상태 이관 큐잉 — 목이 서버보다 관대하면 프로덕션에서만 터진다', () => {
  it('앵커가 저장된 초안과 다르면 409 다 (write-once 앵커 — 10a 계약과 같은 축)', async () => {
    const anchor = await saveWith((d) => {
      d.states = d.states.filter((s) => s.key !== 'done')
      d.transitions = d.transitions.filter((t) => t.from !== 'done' && t.to !== 'done')
    })

    await expect(
      migrateStatuses(KEY, anchor + 1, [{ fromStatusKey: 'done', toStatusKey: 'closed' }]),
    ).rejects.toMatchObject({ status: 409, errorCode: 'WORKFLOW_VERSION_CONFLICT' })
  })

  it('초안이 없으면 404 가 아니라 400 이다 (preview 규약과 같은 축)', async () => {
    await expect(
      migrateStatuses(KEY, 0, [{ fromStatusKey: 'done', toStatusKey: 'closed' }]),
    ).rejects.toMatchObject({ status: 400, errorCode: 'WORKFLOW_INVALID_REQUEST' })
  })

  it('매핑이 비었거나 출발지가 안 빠지는 상태면 400 이다 (서버 requireSoundMappings 재현)', async () => {
    const anchor = await saveWith((d) => {
      d.states = d.states.filter((s) => s.key !== 'done')
      d.transitions = d.transitions.filter((t) => t.from !== 'done' && t.to !== 'done')
    })

    // E2 — 빈 매핑은 「아무것도 안 옮기는 일괄작업」이다.
    await expect(migrateStatuses(KEY, anchor, [])).rejects.toMatchObject({
      status: 400,
      errorCode: 'WORKFLOW_MIGRATION_INVALID_MAPPING',
    })

    // F7 — closed 는 이 초안에서 빠지지 않는다. 출발지로 실으면 멀쩡한 이슈까지 옮겨진다.
    await expect(
      migrateStatuses(KEY, anchor, [{ fromStatusKey: 'closed', toStatusKey: 'open' }]),
    ).rejects.toMatchObject({ status: 400, errorCode: 'WORKFLOW_MIGRATION_INVALID_MAPPING' })
  })

  it('성공하면 202 와 uuid 형태의 bulkOperationId 를 준다', async () => {
    const anchor = await saveWith((d) => {
      d.states = d.states.filter((s) => s.key !== 'done')
      d.transitions = d.transitions.filter((t) => t.from !== 'done' && t.to !== 'done')
    })

    const accepted = await migrateStatuses(KEY, anchor, [{ fromStatusKey: 'done', toStatusKey: 'closed' }])

    expect(accepted.bulkOperationId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i)
  })
})

describe('★ 상태 이관 진행률 폴링 — 접수와 조회를 잇는다 (task 7a)', () => {
  it('접수 직후 같은 id 로 조회하면 200 이다 (404 가 아니다 — 이 갭이 결함의 본체)', async () => {
    const anchor = await saveDraftRemovingDone()
    const accepted = await migrateStatuses(KEY, anchor, [{ fromStatusKey: 'done', toStatusKey: 'closed' }])

    // fetchBulkOperation 은 응답을 bulkOperationResponseSchema 로 파싱한다 — 여기서 안 던지면
    // 형태도 맞는 것이다(.strict() 판별 갈래는 STATUS_MIGRATION payload 여야만 통과한다).
    const op = await fetchBulkOperation(accepted.bulkOperationId)

    expect(op.id).toBe(accepted.bulkOperationId)
    expect(op.operationType).toBe('STATUS_MIGRATION')
    expect(op.payload).toEqual({ mappings: { done: 'closed' }, projectKeys: [] })
  })

  it('여러 번 폴링하면 종단 상태(COMPLETED)에 도달한다', async () => {
    const anchor = await saveDraftRemovingDone()
    const accepted = await migrateStatuses(KEY, anchor, [{ fromStatusKey: 'done', toStatusKey: 'closed' }])

    // MAX_POLLS_TO_COMPLETE(2) 이내에 도달해야 한다. 한 번에 COMPLETED 로 뛰면 E2E 가 진행
    // 중 상태를 관측할 수 없으므로, 첫 폴은 아직 RUNNING 이어야 한다.
    const firstPoll = await fetchBulkOperation(accepted.bulkOperationId)
    expect(firstPoll.status).toBe('RUNNING')
    expect(firstPoll.processedCount).toBeGreaterThan(0)
    expect(firstPoll.processedCount).toBeLessThan(firstPoll.totalCount)

    const secondPoll = await fetchBulkOperation(accepted.bulkOperationId)
    expect(secondPoll.status).toBe('COMPLETED')
    expect(secondPoll.processedCount).toBe(secondPoll.totalCount)
  })

  it('완료 후 pendingIssueCounts 가 0 이 돼 재발행이 막히지 않는다 (서버 requireNoPending 재현)', async () => {
    const anchor = await saveDraftRemovingDone()
    const accepted = await migrateStatuses(KEY, anchor, [{ fromStatusKey: 'done', toStatusKey: 'closed' }])

    await fetchBulkOperation(accepted.bulkOperationId) // 1차 폴 — 아직 RUNNING
    const completed = await fetchBulkOperation(accepted.bulkOperationId) // 2차 폴 — COMPLETED
    expect(completed.status).toBe('COMPLETED')

    expect(pendingIssueStore.get('done')).toBe(0)
    await expect(publishDraft(KEY, anchor)).resolves.toMatchObject({ versionNo: 1 })
  })
})

describe('★ 부분 실패 — 목이 서버보다 관대하면 E1 이 프로덕션에서만 터진다', () => {
  // 일괄 편집과 같은 플래그 하나로 켠다(`isPartialFailEnabled`). 켜 두고 나가면 다음 테스트가
  // 이유 없이 실패하므로 파일 안에서 반드시 끈다.
  beforeEach(() => {
    globalThis.localStorage?.setItem(LS_KEY_BULK_PARTIAL_FAIL, 'true')
  })
  afterEach(() => {
    globalThis.localStorage?.clear?.()
  })

  /** 이관을 접수하고 COMPLETED 까지 폴링한다. */
  async function migrateDoneToClosed(anchor: number) {
    const accepted = await migrateStatuses(KEY, anchor, [{ fromStatusKey: 'done', toStatusKey: 'closed' }])
    await fetchBulkOperation(accepted.bulkOperationId) // 1차 폴 — 아직 RUNNING
    return fetchBulkOperation(accepted.bulkOperationId) // 2차 폴 — COMPLETED
  }

  it('COMPLETED 여도 옮겨지지 않은 이슈만큼 잔여가 남는다 (서버는 DB 를 다시 센다)', async () => {
    const anchor = await saveDraftRemovingDone()

    const completed = await migrateDoneToClosed(anchor)

    expect(completed.status).toBe('COMPLETED')
    expect(completed.failedCount).toBe(1)
    // ★ 0 이 아니다. 실패한 1건은 아직 `done` 에 있다 — 목이 여기서 0 으로 내리면
    //   「COMPLETED = 전량 성공」이라는 거짓을 화면에 심는다.
    expect(pendingIssueStore.get('done')).toBe(1)
  })

  it('잔여가 남으면 재발행이 서버와 같은 이유로 409 다 (requireNoPending · E1)', async () => {
    const anchor = await saveDraftRemovingDone()
    await migrateDoneToClosed(anchor)

    const error = await publishDraft(KEY, anchor).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(WorkflowPublishMappingRequiredError)
    expect((error as WorkflowPublishMappingRequiredError).pendingIssueCounts).toEqual({ done: 1 })
  })
})

describe('기본값 복원', () => {
  it('복원은 초안까지만 간다 — 정규 정의는 그대로다', async () => {
    const before = await fetchWorkflow(KEY)

    const restored = await resetToDefault(KEY, before.states.length > 0 ? 4 : 0)

    expect(restored.definition.name).toContain('기본값')
    expect((await fetchWorkflow(KEY)).name).toBe(before.name)
  })

  it('표준이 아닌 워크플로우는 400 이다', async () => {
    await expect(resetToDefault('custom-made', 1)).rejects.toMatchObject({ status: 400 })
  })
})
