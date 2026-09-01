// 초안·발행 MSW 계약 테스트 — 목이 서버보다 관대하면 프로덕션에서만 터진다
import { describe, it, expect, beforeEach } from 'vitest'
import { server } from '@/test/server'
import { getDraft, saveDraft, discardDraft, previewPublish, publishDraft, resetToDefault } from '@/api/workflows-draft'
import { fetchWorkflow } from '@/api/workflows'
import { WorkflowPublishMappingRequiredError } from '@/api/workflows-admin.http'
import { workflowHandlers } from './workflow-handlers'
import { resetWorkflowAdminStore } from './workflow-admin-fixtures'
import { workflowDraftHandlers } from './workflow-draft-handlers'
import { pendingIssueStore, resetWorkflowDraftStore } from './workflow-draft-fixtures'

const KEY = 'software-default'

beforeEach(() => {
  resetWorkflowAdminStore()
  resetWorkflowDraftStore()
  // 발행이 정규 정의를 교체하는지 보려면 조회 핸들러도 같은 저장소를 읽어야 한다.
  server.use(...workflowHandlers, ...workflowDraftHandlers)
})

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
