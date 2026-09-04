// 보드 컬럼 관리 API 클라이언트 — 생성·삭제·이름/WIP 갱신·상태 매핑·순서 교체 (부채 177)

import { z } from 'zod'
import { apiFetch, ApiError } from './client'
import { columnStateSchema, boardMetaSchema, type BoardMeta } from './boards'

/**
 * `{ data: T }` 봉투 언랩용 헬퍼.
 *
 * `boards.ts` 의 같은 헬퍼를 export 하지 않아 여기 한 벌 둔다 — 두 줄짜리라 공유 비용이 더 크다.
 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) => z.object({ data: innerSchema })

/**
 * 컬럼 단건 응답 스키마 — 백엔드 `ColumnMetaResponse`.
 *
 * `boards.ts` 의 `boardColumnSchema` 와 **다르다**: 저쪽은 카드를 담고 이쪽은 안 담는다.
 * 컬럼을 고치는 응답에 카드를 실을 이유가 없어 백엔드가 둘을 나눠 뒀고, 여기서도 나눠 둔다.
 */
export const columnMetaSchema = z.object({
  columnId: z.string().uuid(),
  states: z.array(columnStateSchema),
  name: z.string(),
  category: z.enum(['TODO', 'IN_PROGRESS', 'DONE']),
  displayOrder: z.number().int(),
  /** WIP 제한. **null 은 「무제한」이지 「미지정」이 아니다.** 백엔드가 명시적으로 null 을 보낸다. */
  wipLimit: z.number().int().nullable(),
})

/** 컬럼 삭제 응답 — 사라진 카드 수(폭발 반경). */
export const deleteColumnResultSchema = z.object({
  removedCardCount: z.number().int(),
})

export type ColumnMeta = z.infer<typeof columnMetaSchema>
export type DeleteColumnResult = z.infer<typeof deleteColumnResultSchema>

/**
 * 컬럼을 만든다 (R6 · J23).
 *
 * POST `/api/v1/boards/{boardId}/columns` → 201 + `{ data: ColumnMeta }`.
 *
 * ★**`category` 를 보내지 않는다**(편차 X1). 지라는 생성 시 카테고리를 고르게 하지만 BTS 는
 * 담은 상태들의 최댓값으로 **파생**한다 — 보내면 파생값과 선택값이라는 두 번째 진실이 생긴다.
 * ★**`stateKeys` 를 비워 보낸다.** 지라의 「컬럼 먼저, 상태는 드래그로」 흐름(J23→J27)이 그것을
 * 요구하고, 백엔드가 상태 0개 컬럼을 허용한다(#444 E1).
 *
 * @param boardId 보드 UUID
 * @param name 새 컬럼 이름. 공백만 있으면 백엔드가 400.
 * @returns 만들어진 컬럼. `states` 는 빈 배열이다.
 * @throws ApiError 비-2xx (400 이름 공백 · 403 CREATE 권한 없음 · 404 보드 미존재)
 * @throws ZodError 응답 스키마 불일치
 */
export async function createColumn(boardId: string, name: string): Promise<ColumnMeta> {
  const res = await apiFetch(`/api/v1/boards/${boardId}/columns`, {
    method: 'POST',
    body: { name, stateKeys: [] },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const data: unknown = await res.json()
  return dataResponseSchema(columnMetaSchema).parse(data).data
}

/**
 * 컬럼을 지운다 (R7 · J26 · J28).
 *
 * DELETE `/api/v1/boards/{boardId}/columns/{columnId}` → 200 + `{ data: { removedCardCount } }`.
 * 담겨 있던 상태는 미매핑 패널로 돌아간다 — 지라와 같다(J28).
 *
 * ★`removedCardCount` 는 **삭제 후** 값이라 확인 창의 사전 표시에 쓸 수 없다. 사전 표시는
 * 호출자가 `column.cards.length` 로 세되 `truncated` 면 정확한 수를 주장하지 않는다(스펙 S6).
 *
 * @param boardId 보드 UUID
 * @param columnId 지울 컬럼 UUID
 * @returns 사라진 카드 수
 * @throws ApiError 비-2xx (403 · 404 타 보드 소속/미존재)
 */
export async function deleteColumn(boardId: string, columnId: string): Promise<DeleteColumnResult> {
  const res = await apiFetch(`/api/v1/boards/${boardId}/columns/${columnId}`, { method: 'DELETE' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const data: unknown = await res.json()
  return dataResponseSchema(deleteColumnResultSchema).parse(data).data
}

/**
 * 컬럼의 이름 · WIP 제한을 부분 갱신한다 (R8 · R9 · J24 · J29).
 *
 * PATCH `/api/v1/boards/{boardId}/columns/{columnId}` → 200 + `{ data: ColumnMeta }`.
 *
 * ★**보내지 않은 필드는 서버가 건드리지 않는다**(백엔드 `UpdateColumnRequest` 3-state).
 * 그래서 이름만 바꿀 때 `wipLimit` 을 얹지 않는다 — 얹으면 WIP 제한이 함께 덮인다.
 * 반대로 **WIP 해제는 `wipLimit: null` 을 명시 전송**해야 한다(J29 "clear the existing value").
 * `undefined` 는 JSON 직렬화에서 사라져 「무변경」이 되므로 해제가 되지 않는다.
 *
 * @param boardId 보드 UUID
 * @param columnId 대상 컬럼 UUID
 * @param patch 바꿀 것만 담는다. 둘 다 생략하면 백엔드가 400.
 * @returns 갱신된 컬럼
 * @throws ApiError 비-2xx (400 빈 바디·공백 이름·wipLimit 0 이하 · 403 · 404)
 */
export async function updateColumn(
  boardId: string,
  columnId: string,
  patch: { name?: string; wipLimit?: number | null },
): Promise<ColumnMeta> {
  const body: Record<string, unknown> = {}
  if (patch.name !== undefined) body.name = patch.name
  // `in` 으로 본다 — `patch.wipLimit !== undefined` 로 보면 명시 null 은 통과하지만
  // 「키를 아예 안 준 것」과 「null 을 준 것」의 구분이 호출자 쪽 관용구에 의존하게 된다.
  if ('wipLimit' in patch) body.wipLimit = patch.wipLimit

  const res = await apiFetch(`/api/v1/boards/${boardId}/columns/${columnId}`, {
    method: 'PATCH',
    body,
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const data: unknown = await res.json()
  return dataResponseSchema(columnMetaSchema).parse(data).data
}

/**
 * 컬럼이 담는 상태 집합을 **통째로 교체**한다 (R5 · J27).
 *
 * PUT `/api/v1/boards/{boardId}/columns/{columnId}/states` → 200 + `{ data: ColumnMeta }`.
 *
 * ★부분 추가·제거가 아니라 집합 전체다. 드롭 하나가 「이 컬럼의 새 상태 목록 전부」를 보낸다 —
 * 그래야 「지금 이 컬럼의 상태 집합」이 클라이언트와 서버 사이에서 갈리지 않는다(#444 R9).
 * 다른 컬럼이 이미 쓰는 상태를 넣으려 하면 **409** 다.
 *
 * @param boardId 보드 UUID
 * @param columnId 대상 컬럼 UUID
 * @param stateKeys 교체 후 이 컬럼이 담을 상태 키 전량. 빈 배열이면 상태 0개 컬럼이 된다.
 * @returns 갱신된 컬럼
 * @throws ApiError 비-2xx (409 다른 컬럼이 이미 그 상태를 쓴다 · 403 · 404)
 */
export async function replaceColumnStates(
  boardId: string,
  columnId: string,
  stateKeys: string[],
): Promise<ColumnMeta> {
  const res = await apiFetch(`/api/v1/boards/${boardId}/columns/${columnId}/states`, {
    method: 'PUT',
    body: { stateKeys },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const data: unknown = await res.json()
  return dataResponseSchema(columnMetaSchema).parse(data).data
}

/**
 * 컬럼 표시 순서를 **통째로 교체**한다 (R10 · J25).
 *
 * PUT `/api/v1/boards/{boardId}/columns/order` → 200 + `{ data: BoardMeta }`.
 *
 * ★[columnIds] 는 이 보드의 **전 컬럼**을 원하는 순서대로 담아야 한다. 빠지거나 중복되거나
 * 타 보드 컬럼이 섞이면 400 이다 — 부분 이동 명령을 받지 않는 이유는 위 두 함수와 같다.
 *
 * @param boardId 보드 UUID
 * @param columnIds 원하는 순서대로 담은 전 컬럼 UUID
 * @returns 보드 메타(순서 자체는 보드 재조회로 읽는다)
 * @throws ApiError 비-2xx (400 집합 불일치·빈 배열 · 403 · 404)
 */
export async function reorderColumns(boardId: string, columnIds: string[]): Promise<BoardMeta> {
  const res = await apiFetch(`/api/v1/boards/${boardId}/columns/order`, {
    method: 'PUT',
    body: { columnIds },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const data: unknown = await res.json()
  return dataResponseSchema(boardMetaSchema).parse(data).data
}
