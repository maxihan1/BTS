// 워크플로우 초안·발행 REST 클라이언트 — 초안 CRUD · 미리보기 · 발행 · 이관 큐잉 · 기본값 복원
import { apiFetch } from './client'
import { parseData, expectNoContent, seg } from './workflows-admin.http'
import {
  draftResponseSchema,
  publishPreviewSchema,
  publishResponseSchema,
  migrateResponseSchema,
} from './workflows-draft.types'
import type {
  DraftDefinition,
  DraftResponse,
  PublishPreview,
  PublishResult,
  MigrateAccepted,
  StatusMappingInput,
} from './workflows-draft.types'

export * from './workflows-draft.types'

/** `/api/v1/workflows/{key}` 아래 경로를 만든다. 인코딩을 한 곳에 모은다. */
const at = (key: string, path: string): string => `/api/v1/workflows/${seg(key)}${path}`

/**
 * 초안을 읽는다. 저장된 초안이 없으면 **지금 발행된 정의**가 담겨 온다(`exists=false`).
 *
 * 편집기를 빈 화면으로 열지 않기 위한 것이고, 이 호출이 DB 에 초안을 만들지는 않는다.
 * 응답의 `baseVersion` 이 이후 저장·발행·이관·복원 전부에 쓰는 앵커다.
 */
export async function getDraft(key: string): Promise<DraftResponse> {
  return parseData(await apiFetch(at(key, '/draft'), { method: 'GET' }), draftResponseSchema)
}

/**
 * 초안을 저장한다(자동 저장 포함). 204 이고 본문이 없다.
 *
 * ★ `baseVersion` 은 **처음 받은 값 그대로**여야 한다. 서버 앵커는 write-once 라 첫 저장에서만
 * 기록되지만, 화면이 값을 갈아 끼우면 「무엇을 보고 편집했는가」라는 앵커의 의미가 사라진다.
 */
export async function saveDraft(key: string, definition: DraftDefinition, baseVersion: number): Promise<void> {
  await expectNoContent(await apiFetch(at(key, '/draft'), { method: 'PUT', body: { definition, baseVersion } }))
}

/**
 * 초안을 폐기한다. 없으면 404(`WORKFLOW_DRAFT_NOT_FOUND`).
 *
 * 앵커가 죽어 발행이 영구 409 가 된 초안의 **유일한 출구**다 — 다시 불러와도 저장된 앵커는
 * 그대로라 재시도로는 풀리지 않는다.
 */
export async function discardDraft(key: string): Promise<void> {
  await expectNoContent(await apiFetch(at(key, '/draft'), { method: 'DELETE' }))
}

/**
 * 발행하지 않고 무엇이 바뀌는지만 계산한다.
 *
 * ★ **초안이 저장돼 있어야 한다.** 없으면 404 가 아니라 **400 `WORKFLOW_INVALID_REQUEST`**
 * (「발행할 초안이 없다」)다 — `WORKFLOW_DRAFT_NOT_FOUND`(404)는 `DELETE /draft` 전용이다.
 * `getDraft` 는 초안이 없어도 발행본을 돌려주므로 화면은 멀쩡해 보이는데 여기서 터진다.
 * 발행 버튼은 이 호출 **전에** 저장을 한 번 태워야 한다.
 *
 * 응답을 캐시하지 마라 — 초안이 바뀌면 옛 결과는 거짓이다.
 */
export async function previewPublish(key: string): Promise<PublishPreview> {
  return parseData(await apiFetch(at(key, '/publish/preview'), { method: 'POST' }), publishPreviewSchema)
}

/**
 * 초안을 발행한다.
 *
 * 빠지는 상태에 이슈가 남아 있으면 409 이고, 그때는 `WorkflowPublishMappingRequiredError` 가
 * 상태별 잔여 건수를 들고 온다. 남이 먼저 발행했으면 409 `WORKFLOW_VERSION_CONFLICT` 다.
 */
export async function publishDraft(key: string, baseVersion: number): Promise<PublishResult> {
  return parseData(
    await apiFetch(at(key, '/publish'), { method: 'POST', body: { baseVersion } }),
    publishResponseSchema,
  )
}

/**
 * 빠지는 상태에 남은 이슈를 옮길 일괄작업을 큐잉한다. **발행하지는 않는다** — 202 다.
 *
 * 진행률은 `GET /api/v1/bulk-operations/{id}` 로 따로 폴링하고, 완료된 뒤에 [publishDraft] 를
 * 이어 부른다. 지라는 조작 1회지만 BTS 는 이관과 발행이 나뉜 의도적 편차다.
 *
 * ★ **`projectKeys` 를 보내지 않는다.** 범위를 요청이 정하게 두면 워크플로우 하나에 발행
 * 권한을 가진 사람이 남의 프로젝트 이슈까지 옮길 수 있다. 서버가 스킴 할당을 거슬러 직접
 * 채우며, 필드가 없는 것이 그 유일한 방어다.
 */
export async function migrateStatuses(
  key: string,
  baseVersion: number,
  mappings: StatusMappingInput[],
): Promise<MigrateAccepted> {
  return parseData(
    await apiFetch(at(key, '/publish/migrate'), { method: 'POST', body: { baseVersion, mappings } }),
    migrateResponseSchema,
  )
}

/**
 * YAML 기본값을 **초안으로** 불러온다. 정규 테이블은 건드리지 않는다.
 *
 * 되돌림의 주체가 부팅 이벤트에서 사람으로 바뀐 것이 이 기능의 핵심이라, 눌러도 곧바로 운영에
 * 반영되지 않는다 — 관리자가 내용을 보고 발행해야 나간다. 응답이 새 앵커를 함께 준다.
 *
 * `canResetToDefault` 가 false 인 워크플로우에서 부르면 400 이다.
 */
export async function resetToDefault(key: string, baseVersion: number): Promise<DraftResponse> {
  return parseData(
    await apiFetch(at(key, '/reset-to-default'), { method: 'POST', body: { baseVersion } }),
    draftResponseSchema,
  )
}
