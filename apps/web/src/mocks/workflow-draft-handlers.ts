// 초안·발행 MSW 핸들러 — 앵커 write-once · 발행이 정규 정의를 교체 · 이관 필요 409 재현
import { http, HttpResponse } from 'msw'
import type { WorkflowView } from '@/api/workflows'
import type { DraftDefinition, StatusMappingInput } from '@/api/workflows-draft.types'
import { transitionKey } from '@/components/workflow/workflow.types'
import { workflowStore, statusCatalogStore, nextTransitionId } from './workflow-admin-fixtures'
import { isPartialFailEnabled, registerStatusMigration } from './bulk-operation-handlers'
import {
  draftStore,
  versionStore,
  publicationStore,
  pendingIssueStore,
  seedWorkflowKeys,
  versionOf,
  publishedLayouts,
} from './workflow-draft-fixtures'

/** 백엔드와 같은 중첩 봉투로 실패를 돌려준다. */
function problem(status: number, code: string, message: string) {
  return HttpResponse.json({ error: { code, message } }, { status })
}

/** 발행이 이관을 요구할 때의 409 — 표준 봉투 **위에** 잔여 건수를 얹는다. */
function mappingRequired(pending: Record<string, number>) {
  return HttpResponse.json(
    {
      error: {
        code: 'WORKFLOW_PUBLISH_MAPPING_REQUIRED',
        message: '발행하면 사라지는 상태에 아직 이슈가 남아 있습니다. 옮길 상태를 정한 뒤 다시 발행해 주세요.',
      },
      pendingIssueCounts: pending,
    },
    { status: 409 },
  )
}

/** 경로 파라미터에서 워크플로우 키를 꺼낸다. */
function keyOf(params: Record<string, unknown>): string {
  return typeof params['key'] === 'string' ? params['key'] : ''
}


/** 발행된 정의를 초안 형태로 옮긴다. `GET /draft` 가 초안 없을 때 주는 값이다. */
function toDraftDefinition(workflow: WorkflowView): DraftDefinition {
  const layouts = publishedLayouts.get(workflow.key)
  return {
    key: workflow.key,
    name: workflow.name,
    description: workflow.description.length > 0 ? workflow.description : null,
    states: workflow.states.map((s) => ({
      key: s.key,
      name: s.name,
      category: s.category,
      displayOrder: s.displayOrder,
      // 발행 시 실린 좌표를 되읽는다. 없으면 null 이고 캔버스가 자동 배치한다.
      layoutX: layouts?.get(s.key)?.x ?? null,
      layoutY: layouts?.get(s.key)?.y ?? null,
    })),
    transitions: workflow.transitions.map((t) => ({
      from: t.fromStateKey,
      to: t.toStateKey,
      name: t.name,
      kind: t.kind,
      // 서버는 규칙을 함께 싣는다. 목이 빈 목록만 주면 「규칙을 왕복시키는가」를 잴 수 없다.
      validators: [],
      postActions: [],
    })),
  }
}

/** 초안 정의를 발행본(`WorkflowView`) 형태로 되돌린다. 발행이 정규 테이블을 교체하는 자리다. */
function toWorkflowView(definition: DraftDefinition): WorkflowView {
  // 좌표를 옆 저장소에 내려쓴다 — 실서버 `replaceDefinition` 이 `workflow_statuses` 에
  // LAYOUT_X/LAYOUT_Y 를 함께 싣는 자리와 같다. 전량 교체라 이전 값은 남기지 않는다.
  publishedLayouts.set(
    definition.key,
    new Map(definition.states.map((s) => [s.key, { x: s.layoutX, y: s.layoutY }])),
  )
  return {
    key: definition.key,
    name: definition.name,
    description: definition.description ?? '',
    states: definition.states.map((s) => ({
      key: s.key,
      name: s.name,
      category: s.category,
      displayOrder: s.displayOrder,
    })),
    transitions: definition.transitions.map((t) => ({
      key:
        t.from === null ? `${t.kind}__${t.to}` : transitionKey(t.from, t.to),
      fromStateKey: t.from,
      toStateKey: t.to,
      name: t.name,
      // 발행 시점에 DB 가 id 를 정한다 — 초안에는 없던 값이 여기서 생긴다.
      id: nextTransitionId(),
      kind: t.kind,
    })),
  }
}

/** 저장된 초안, 없으면 발행본을 초안 형태로. */
function currentDraft(key: string): { definition: DraftDefinition; baseVersion: number; exists: boolean } | null {
  const stored = draftStore.get(key)
  if (stored !== undefined) {
    return { definition: stored.definition, baseVersion: stored.baseVersion, exists: true }
  }
  const published = workflowStore.get(key)
  if (published === undefined) {
    return null
  }
  return { definition: toDraftDefinition(published), baseVersion: versionOf(key), exists: false }
}

/** 발행하면 빠지는 상태 키 — 발행본에 있고 초안에 없는 것. */
function removedKeys(key: string, definition: DraftDefinition): string[] {
  const published = workflowStore.get(key)
  if (published === undefined) {
    return []
  }
  const kept = new Set(definition.states.map((s) => s.key))
  return published.states.filter((s) => !kept.has(s.key)).map((s) => s.key)
}

/** 빠지는 상태 중 이슈가 남은 것과 건수. */
function pendingFor(removed: string[]): Record<string, number> {
  const pending: Record<string, number> = {}
  for (const stateKey of removed) {
    const count = pendingIssueStore.get(stateKey) ?? 0
    if (count > 0) {
      pending[stateKey] = count
    }
  }
  return pending
}

/** `POST /publish/migrate` 요청 본문. 백엔드 `MigrateRequest` 와 1:1. */
interface MigrateRequestBody {
  baseVersion: number
  mappings: StatusMappingInput[]
}

/**
 * RFC4122 v4 UUID 를 만든다. `bulk-operation-handlers.ts` 와 같은 패턴이다 — 그 함수가 export 되지
 * 않아 이 파일이 자기 것을 따로 둔다(이 코드베이스의 mock 파일마다 자기 uuid 헬퍼를 갖는 관례).
 */
function generateBulkOperationId(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

/**
 * 이관 매핑이 안전한지 본다. **거절 사유가 있으면 그 문장을, 없으면 null 을 준다.**
 *
 * 서버 `requireSoundMappings` 는 축이 여덟(§WorkflowPublishService)이지만, 화면이 실제로 만들 수
 * 있는 잘못된 입력은 이 둘뿐이다 — 나머지(중복 출발지·초안에 없는 도착지·미발행 도착지·범위
 * 없음·형제 워크플로우·상한)는 `migrationTargets()` 후보 제한이나 서버 전용 판단이라, 목이 흉내
 * 내면 화면이 만들 수 없는 상태를 검사하는 죽은 코드가 된다.
 */
function invalidMappingReason(removed: string[], mappings: StatusMappingInput[]): string | null {
  if (mappings.length === 0) {
    return '옮길 매핑이 없다. 빠지는 상태마다 옮길 곳을 정할 것'
  }
  const notRemoved = mappings.map((m) => m.fromStatusKey).filter((key) => !removed.includes(key))
  if (notRemoved.length > 0) {
    return `상태 ${notRemoved.join(' · ')} 는 이 초안에서 빠지지 않는다. 옮길 대상이 아니다`
  }
  return null
}

export const workflowDraftHandlers = [
  // ── 초안 조회 ────────────────────────────────────────────────────────────
  http.get('/api/v1/workflows/:key/draft', ({ params }) => {
    const key = keyOf(params)
    const draft = currentDraft(key)
    if (draft === null) {
      return problem(404, 'WORKFLOW_NOT_FOUND', '워크플로우를 찾을 수 없습니다')
    }
    return HttpResponse.json({
      data: { ...draft, canResetToDefault: seedWorkflowKeys.has(key) },
    })
  }),

  // ── 초안 저장 ────────────────────────────────────────────────────────────
  http.put('/api/v1/workflows/:key/draft', async ({ params, request }) => {
    const key = keyOf(params)
    if (!workflowStore.has(key)) {
      return problem(404, 'WORKFLOW_NOT_FOUND', '워크플로우를 찾을 수 없습니다')
    }
    const body = (await request.json()) as { definition: DraftDefinition; baseVersion: number }

    // 편집기가 아직 존재하지 않는 버전을 앵커로 주장하면 거절한다.
    if (body.baseVersion > versionOf(key)) {
      return problem(400, 'WORKFLOW_INVALID_REQUEST', '편집 기준 판이 아직 존재하지 않습니다')
    }
    // 상태 이름·카테고리의 정본은 카탈로그다 — 다른 값을 담은 초안은 저장 단계에서 막힌다.
    for (const state of body.definition.states) {
      const catalog = statusCatalogStore.get(state.key)
      if (catalog !== undefined && catalog.name !== state.name) {
        return problem(400, 'WORKFLOW_INVALID_REQUEST', `상태 '${state.key}' 의 이름은 카탈로그가 정합니다`)
      }
    }

    // ★ 앵커는 **write-once** 다. 이미 초안이 있으면 요청 값을 무시하고 기존 앵커를 유지한다 —
    //   백엔드 upsert 의 `DO UPDATE` 가 그 컬럼을 빼는 것과 같다. 목이 갱신해 주면
    //   「재시도로는 안 풀린다」는 계약을 E2E 가 잴 수 없다.
    const existing = draftStore.get(key)
    draftStore.set(key, {
      definition: body.definition,
      baseVersion: existing?.baseVersion ?? body.baseVersion,
    })
    return new HttpResponse(null, { status: 204 })
  }),

  // ── 초안 폐기 ────────────────────────────────────────────────────────────
  http.delete('/api/v1/workflows/:key/draft', ({ params }) => {
    const key = keyOf(params)
    if (!draftStore.has(key)) {
      // 없는 것을 지웠다고 204 를 주면 화면이 「폐기됨」을 표시하고 관리자가 오해한다.
      return problem(404, 'WORKFLOW_DRAFT_NOT_FOUND', '편집 중인 초안이 없습니다')
    }
    draftStore.delete(key)
    return new HttpResponse(null, { status: 204 })
  }),

  // ── 발행 미리보기 ─────────────────────────────────────────────────────────
  http.post('/api/v1/workflows/:key/publish/preview', ({ params }) => {
    const key = keyOf(params)
    const stored = draftStore.get(key)
    // ★ 초안이 없으면 **400** 이다. 404 가 아니다 — 그 코드는 DELETE /draft 전용이고,
    //   여기는 requireDraft 가 WorkflowInvalidRequestException 을 던진다.
    if (stored === undefined) {
      return problem(400, 'WORKFLOW_INVALID_REQUEST', '발행할 초안이 없다. 먼저 초안을 저장할 것')
    }
    const removed = removedKeys(key, stored.definition)
    return HttpResponse.json({
      data: {
        baseVersion: stored.baseVersion,
        currentVersion: versionOf(key),
        removedStatusKeys: removed,
        pendingIssueCounts: pendingFor(removed),
      },
    })
  }),

  // ── 발행 ─────────────────────────────────────────────────────────────────
  http.post('/api/v1/workflows/:key/publish', async ({ params, request }) => {
    const key = keyOf(params)
    const stored = draftStore.get(key)
    if (stored === undefined) {
      return problem(400, 'WORKFLOW_INVALID_REQUEST', '발행할 초안이 없다. 먼저 초안을 저장할 것')
    }
    const body = (await request.json()) as { baseVersion: number }

    // ★ 대조 대상은 **저장된 초안의 앵커**다. 지금 DB 쪽 판이 아니다 — 그렇게 하면 화면이
    //   미리보기의 currentVersion 을 되실어 보내는 것만으로 락이 풀린다.
    if (body.baseVersion !== stored.baseVersion) {
      return problem(409, 'WORKFLOW_VERSION_CONFLICT', '다른 사용자가 먼저 발행했습니다')
    }
    // ★★ 서버는 여기서 한 번 **더** 본다 — `bumpVersionIfMatches` 의 `WHERE VERSION = ?` 는
    //    저장된 앵커를 **살아 있는 판**과 대조하고, 0 rows 면 409 다
    //    (`WorkflowPublishRepository.kt`). 초안 저장은 과거 앵커를 받아 주므로
    //    (`requireAnchorNotAhead` 는 미래만 막는다) 죽은 앵커로 만들어진 초안이 실재할 수 있고,
    //    그 발행이 서버에서는 막히는데 목에서만 통과하면 **목이 서버보다 관대해진다.**
    if (stored.baseVersion !== versionOf(key)) {
      return problem(409, 'WORKFLOW_VERSION_CONFLICT', '초안의 기준 판이 이미 지났습니다')
    }

    const removed = removedKeys(key, stored.definition)
    const pending = pendingFor(removed)
    if (Object.keys(pending).length > 0) {
      return mappingRequired(pending)
    }

    // 정규 정의를 교체한다 — 이 한 줄이 「편집 ≠ 배포」를 성립시킨다.
    workflowStore.set(key, toWorkflowView(stored.definition))
    versionStore.set(key, versionOf(key) + 1)
    const versionNo = (publicationStore.get(key) ?? 0) + 1
    publicationStore.set(key, versionNo)
    // 발행은 초안 행을 지운다.
    draftStore.delete(key)

    return HttpResponse.json({ data: { versionNo } })
  }),

  // ── 상태 이관 큐잉 ───────────────────────────────────────────────────────
  http.post('/api/v1/workflows/:key/publish/migrate', async ({ params, request }) => {
    const key = keyOf(params)
    const stored = draftStore.get(key)
    // ★ 초안이 없으면 발행/미리보기와 같은 축으로 400 이다. 404 가 아니다.
    if (stored === undefined) {
      return problem(400, 'WORKFLOW_INVALID_REQUEST', '발행할 초안이 없다. 먼저 초안을 저장할 것')
    }
    const body = (await request.json()) as MigrateRequestBody

    // ★ write-once 앵커 — 대조 대상은 저장된 초안의 앵커다. `publish` 핸들러와 같은 축이다.
    if (body.baseVersion !== stored.baseVersion) {
      return problem(409, 'WORKFLOW_VERSION_CONFLICT', '다른 사용자가 먼저 발행했습니다')
    }

    const removed = removedKeys(key, stored.definition)
    const reason = invalidMappingReason(removed, body.mappings)
    if (reason !== null) {
      return problem(400, 'WORKFLOW_MIGRATION_INVALID_MAPPING', reason)
    }

    // mappings 배열을 서버 payload 형태(출발 상태 키 → 도착 상태 키 레코드)로 접고, 이관 대상
    // 이슈를 **출발 상태별로** 만든다. 서버 `requireNoPending` 은 완료 후 DB 를 상태마다 다시
    // 세므로, 목도 어느 이슈가 어느 상태에서 왔는지 알아야 같은 판정을 낼 수 있다.
    const mappings: Record<string, string> = {}
    const issueKeysByStatus = new Map<string, string[]>()
    for (const mapping of body.mappings) {
      mappings[mapping.fromStatusKey] = mapping.toStatusKey
      const remaining = pendingIssueStore.get(mapping.fromStatusKey) ?? 0
      issueKeysByStatus.set(
        mapping.fromStatusKey,
        Array.from({ length: remaining }, (_, idx) => `MIGRATION-${mapping.fromStatusKey}-${String(idx)}`),
      )
    }

    // ★ 부분 실패(E1) — 출발 상태마다 마지막 1건이 실패한다. 이 분기가 없으면 목의 이관은
    //   **절대 실패하지 않아** E1 경로가 어디서도 실행되지 않는다. 플래그는 일괄 편집이 쓰는
    //   것과 같은 하나다(`isPartialFailEnabled`).
    const failKeys = new Set<string>()
    if (isPartialFailEnabled()) {
      for (const issueKeys of issueKeysByStatus.values()) {
        const last = issueKeys.at(-1)
        if (last !== undefined) {
          failKeys.add(last)
        }
      }
    }

    // ★ 발행하지 않는다 — 큐잉만 한다. 진행률은 `GET /api/v1/bulk-operations/{id}` 가 따로 준다.
    // 그 폴링이 이 작업을 찾으려면 응답을 돌려주기 **전에** bulkOpsStore 에 등록해야 한다.
    const bulkOperationId = generateBulkOperationId()
    registerStatusMigration(
      bulkOperationId,
      { issueKeys: [...issueKeysByStatus.values()].flat(), mappings, projectKeys: [], failKeys },
      (failedIssueKeys) => {
        // 서버 `requireNoPending` 재현 — COMPLETED 는 「전량 옮겨졌다」가 **아니다.** 서버는 DB 를
        // 다시 세므로 옮겨지지 않은 이슈가 남아 있으면 이어지는 발행이 계속 409
        // (WORKFLOW_PUBLISH_MAPPING_REQUIRED) 다. 목이 여기서 0 으로 일괄 초기화하면 목이 서버보다
        // 관대해지고, 그 차이는 프로덕션에서만 터진다.
        const failed = new Set(failedIssueKeys)
        for (const [fromStatusKey, issueKeys] of issueKeysByStatus) {
          pendingIssueStore.set(fromStatusKey, issueKeys.filter((key) => failed.has(key)).length)
        }
      },
    )

    return HttpResponse.json({ data: { bulkOperationId } }, { status: 202 })
  }),

  // ── 기본값 복원 ───────────────────────────────────────────────────────────
  http.post('/api/v1/workflows/:key/reset-to-default', async ({ params, request }) => {
    const key = keyOf(params)
    if (!seedWorkflowKeys.has(key)) {
      return problem(400, 'WORKFLOW_INVALID_REQUEST', '사용자가 만든 워크플로우에는 되돌릴 기본값이 없다')
    }
    const published = workflowStore.get(key)
    if (published === undefined) {
      return problem(404, 'WORKFLOW_NOT_FOUND', '워크플로우를 찾을 수 없습니다')
    }
    const body = (await request.json()) as { baseVersion: number }

    // 목의 「기본값」은 지금 발행본에 표식을 더한 것이다. 실제 YAML 을 흉내 내는 것이
    // 목적이 아니라 **복원이 초안까지만 간다**는 계약을 재는 것이 목적이다.
    //
    // ★ 단 **좌표는 반드시 비운다.** 서버 `resetToDefault` 는 `yaml.toDraftDefinition()` 으로
    // 정의를 새로 만드는데 YAML 에 좌표가 없어 `DraftStateDto` 기본값 `null` 이 실린다 —
    // 즉 복원은 배치를 초기화한다. `toDraftDefinition(published)` 를 그대로 쓰면 목이
    // `publishedLayouts` 의 좌표를 살려 줘 **서버보다 관대해진다**(게이트 2 리뷰 #5).
    const base = toDraftDefinition(published)
    const restored: DraftDefinition = {
      ...base,
      name: `${published.name} (기본값)`,
      states: base.states.map((s) => ({ ...s, layoutX: null, layoutY: null })),
    }
    const existing = draftStore.get(key)
    draftStore.set(key, { definition: restored, baseVersion: existing?.baseVersion ?? body.baseVersion })

    return HttpResponse.json({
      data: {
        definition: restored,
        baseVersion: existing?.baseVersion ?? body.baseVersion,
        exists: true,
        canResetToDefault: true,
      },
    })
  }),
]
