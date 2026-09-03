// 초안·발행 API 클라이언트 계약 테스트 — 응답 언랩 · 204 · 409 잔여건수 · 경로 인코딩
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  getDraft,
  saveDraft,
  discardDraft,
  previewPublish,
  publishDraft,
  resetToDefault,
  migrateStatuses,
} from '../workflows-draft'
import { WorkflowAdminApiError, WorkflowPublishMappingRequiredError } from '../workflows-admin.http'
import type { DraftDefinition } from '../workflows-draft.types'

/** 목 응답을 만든다. `body` 가 undefined 면 본문 없는 204 를 흉내 낸다. */
function reply(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => {
      if (body === undefined) {
        throw new SyntaxError('Unexpected end of JSON input')
      }
      return body
    },
  } as unknown as Response
}

const fetchMock = vi.fn()

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
  fetchMock.mockReset()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

/** 마지막 요청의 URL */
function lastUrl(): string {
  return String(fetchMock.mock.calls.at(-1)?.[0])
}

/** 마지막 요청의 파싱된 본문 */
function lastBody(): unknown {
  const init = fetchMock.mock.calls.at(-1)?.[1] as RequestInit | undefined
  return init?.body === undefined ? undefined : JSON.parse(String(init.body))
}

const DEFINITION = {
  key: 'wf',
  name: '워크플로우',
  description: null,
  states: [{ key: 'open', name: '열림', category: 'TODO', displayOrder: 1, layoutX: null, layoutY: null }],
  transitions: [{ from: null, to: 'open', name: '이슈 생성', kind: 'INITIAL', validators: [], postActions: [] }],
} satisfies DraftDefinition

describe('getDraft', () => {
  it('정의·앵커·존재여부·복원가능을 언랩한다', async () => {
    fetchMock.mockResolvedValue(
      reply(200, { data: { definition: DEFINITION, baseVersion: 7, exists: true, canResetToDefault: false } }),
    )

    const draft = await getDraft('wf')

    expect(draft.baseVersion).toBe(7)
    expect(draft.exists).toBe(true)
    expect(draft.canResetToDefault).toBe(false)
    expect(draft.definition.states[0]?.key).toBe('open')
  })

  it('전환의 validators·postActions 를 버리지 않는다', async () => {
    // ★ 이것을 잃으면 그 초안을 발행하는 순간 전 워크플로우의 전환 규칙이 빈 목록으로
    //   덮어써진다(`DraftRuleWriter.writeAll`). 화면에 아무 표시 없이 사라지는 자리라
    //   스키마가 규칙을 통과시키는지 여기서 못박는다.
    const withRules = {
      ...DEFINITION,
      transitions: [
        {
          from: 'open',
          to: 'done',
          name: '완료',
          kind: 'NORMAL',
          validators: [{ type: 'RequiredField', config: { field: 'assignee' } }],
          postActions: [{ type: 'SetField', config: { field: 'resolution', value: 'done' } }],
        },
      ],
    }
    fetchMock.mockResolvedValue(
      reply(200, { data: { definition: withRules, baseVersion: 1, exists: true, canResetToDefault: true } }),
    )

    const draft = await getDraft('wf')

    expect(draft.definition.transitions[0]?.validators).toEqual([
      { type: 'RequiredField', config: { field: 'assignee' } },
    ])
    expect(draft.definition.transitions[0]?.postActions).toHaveLength(1)
  })

  it('경로 파라미터를 인코딩한다', async () => {
    fetchMock.mockResolvedValue(
      reply(200, { data: { definition: DEFINITION, baseVersion: 1, exists: false, canResetToDefault: false } }),
    )

    await getDraft('../../users')

    // 인코딩이 없으면 fetch 가 `..` 를 정규화해 다른 자원으로 요청이 나간다.
    expect(lastUrl()).not.toContain('../..')
    expect(lastUrl()).toContain('%2F')
  })
})

describe('saveDraft', () => {
  it('204 를 본문 파싱 없이 통과시킨다', async () => {
    fetchMock.mockResolvedValue(reply(204))

    await expect(saveDraft('wf', DEFINITION, 3)).resolves.toBeUndefined()
    expect(lastBody()).toEqual({ definition: DEFINITION, baseVersion: 3 })
  })

  it('400 은 서버 코드와 문장을 보존한다', async () => {
    fetchMock.mockResolvedValue(
      reply(400, { error: { code: 'WORKFLOW_INVALID_REQUEST', message: '시작 전환은 정확히 하나여야 한다' } }),
    )

    await expect(saveDraft('wf', DEFINITION, 3)).rejects.toMatchObject({
      errorCode: 'WORKFLOW_INVALID_REQUEST',
      detail: '시작 전환은 정확히 하나여야 한다',
    })
  })
})

describe('discardDraft', () => {
  it('204 를 통과시킨다', async () => {
    fetchMock.mockResolvedValue(reply(204))
    await expect(discardDraft('wf')).resolves.toBeUndefined()
  })

  it('초안이 없으면 404 코드를 그대로 올린다', async () => {
    fetchMock.mockResolvedValue(reply(404, { error: { code: 'WORKFLOW_DRAFT_NOT_FOUND', message: '초안이 없다' } }))

    await expect(discardDraft('wf')).rejects.toMatchObject({ errorCode: 'WORKFLOW_DRAFT_NOT_FOUND' })
  })
})

describe('previewPublish', () => {
  it('빠지는 상태와 상태별 잔여 건수를 언랩한다', async () => {
    fetchMock.mockResolvedValue(
      reply(200, {
        data: { baseVersion: 4, currentVersion: 4, removedStatusKeys: ['done'], pendingIssueCounts: { done: 3 } },
      }),
    )

    const preview = await previewPublish('wf')

    expect(preview.removedStatusKeys).toEqual(['done'])
    expect(preview.pendingIssueCounts).toEqual({ done: 3 })
  })

  it('초안이 없으면 404 가 아니라 400 이다', async () => {
    // ★ 실측 계약. preview·publish·migrate 는 requireDraft 를 지나고 그것은
    //   WorkflowInvalidRequestException(400) 이다. WORKFLOW_DRAFT_NOT_FOUND(404) 는
    //   DELETE /draft 전용이다. 화면이 404 를 기다리면 이 경로의 안내가 통째로 어긋난다.
    fetchMock.mockResolvedValue(
      reply(400, { error: { code: 'WORKFLOW_INVALID_REQUEST', message: '발행할 초안이 없다' } }),
    )

    await expect(previewPublish('wf')).rejects.toMatchObject({
      status: 400,
      errorCode: 'WORKFLOW_INVALID_REQUEST',
    })
  })
})

describe('publishDraft', () => {
  it('회차를 돌려주고 앵커를 본문에 싣는다', async () => {
    fetchMock.mockResolvedValue(reply(200, { data: { versionNo: 5 } }))

    expect(await publishDraft('wf', 4)).toEqual({ versionNo: 5 })
    expect(lastBody()).toEqual({ baseVersion: 4 })
  })

  it('앵커 충돌 409 를 코드로 구별한다', async () => {
    fetchMock.mockResolvedValue(
      reply(409, { error: { code: 'WORKFLOW_VERSION_CONFLICT', message: '다른 사람이 먼저 발행했다' } }),
    )

    const err = await publishDraft('wf', 4).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(WorkflowAdminApiError)
    expect(err).not.toBeInstanceOf(WorkflowPublishMappingRequiredError)
  })

  it('이관 필요 409 는 상태별 잔여 건수를 살려 던진다', async () => {
    // ★ 백엔드 PublishPendingResponse 는 표준 봉투 위에 pendingIssueCounts 를 얹는다.
    //   봉투만 파싱하면 그 건수를 버리고, 마법사는 preview 를 다시 쏘는 수밖에 없다.
    fetchMock.mockResolvedValue(
      reply(409, {
        error: { code: 'WORKFLOW_PUBLISH_MAPPING_REQUIRED', message: '옮길 상태를 정해 주세요' },
        pendingIssueCounts: { done: 3, review: 1 },
      }),
    )

    const err = await publishDraft('wf', 4).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(WorkflowPublishMappingRequiredError)
    expect((err as WorkflowPublishMappingRequiredError).pendingIssueCounts).toEqual({ done: 3, review: 1 })
  })

  it('잔여 건수가 없는 형태여도 오류 자체는 잃지 않는다', async () => {
    // 부가 정보 파싱 실패가 오류를 통째로 삼키면 화면이 아무 안내도 못 한다.
    fetchMock.mockResolvedValue(
      reply(409, { error: { code: 'WORKFLOW_PUBLISH_MAPPING_REQUIRED', message: '옮길 상태를 정해 주세요' } }),
    )

    const err = await publishDraft('wf', 4).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(WorkflowPublishMappingRequiredError)
    expect((err as WorkflowPublishMappingRequiredError).pendingIssueCounts).toEqual({})
  })
})

describe('migrateStatuses', () => {
  it('202 의 bulkOperationId 를 언랩하고 매핑을 목록으로 싣는다', async () => {
    fetchMock.mockResolvedValue(reply(202, { data: { bulkOperationId: '3f2504e0-4f89-41d3-9a0c-0305e82c3301' } }))

    const accepted = await migrateStatuses('wf', 4, [{ fromStatusKey: 'done', toStatusKey: 'open' }])

    expect(accepted.bulkOperationId).toBe('3f2504e0-4f89-41d3-9a0c-0305e82c3301')
    expect(lastBody()).toEqual({
      baseVersion: 4,
      mappings: [{ fromStatusKey: 'done', toStatusKey: 'open' }],
    })
  })

  it('projectKeys 를 보내지 않는다', async () => {
    // ★ 범위를 요청이 정하게 두면 워크플로우 하나에 발행 권한을 가진 사람이 남의 프로젝트
    //   이슈를 통째로 옮길 수 있다. 백엔드가 스킴을 거슬러 직접 채우므로 필드가 없는 것이
    //   그 방어다 — 화면이 친절을 부려 실어 보내면 안 된다.
    fetchMock.mockResolvedValue(reply(202, { data: { bulkOperationId: '3f2504e0-4f89-41d3-9a0c-0305e82c3301' } }))

    await migrateStatuses('wf', 4, [{ fromStatusKey: 'done', toStatusKey: 'open' }])

    expect(lastBody()).not.toHaveProperty('projectKeys')
  })

  it('진행 중 이관이 있으면 409 코드를 그대로 올린다', async () => {
    fetchMock.mockResolvedValue(
      reply(409, { error: { code: 'WORKFLOW_MIGRATION_IN_FLIGHT', message: '이미 진행 중이다' } }),
    )

    await expect(migrateStatuses('wf', 4, [{ fromStatusKey: 'done', toStatusKey: 'open' }])).rejects.toMatchObject({
      errorCode: 'WORKFLOW_MIGRATION_IN_FLIGHT',
    })
  })
})

describe('resetToDefault', () => {
  it('복원된 초안과 앵커를 돌려준다', async () => {
    fetchMock.mockResolvedValue(
      reply(200, { data: { definition: DEFINITION, baseVersion: 2, exists: true, canResetToDefault: true } }),
    )

    const restored = await resetToDefault('wf', 2)

    expect(restored.exists).toBe(true)
    expect(restored.canResetToDefault).toBe(true)
    expect(lastBody()).toEqual({ baseVersion: 2 })
  })

  it('사용자 워크플로우는 400 이다', async () => {
    fetchMock.mockResolvedValue(
      reply(400, {
        error: { code: 'WORKFLOW_INVALID_REQUEST', message: '사용자가 만든 워크플로우에는 되돌릴 기본값이 없다' },
      }),
    )

    await expect(resetToDefault('wf', 2)).rejects.toMatchObject({ status: 400 })
  })
})
