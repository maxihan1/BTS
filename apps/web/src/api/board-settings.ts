// 보드 설정 탭 API 클라이언트 — 카드 레이아웃 뷰별 PATCH (부채 177 Task 16 · J17·J18)

import { z } from 'zod'
import { apiFetch, ApiError } from './client'

/**
 * `{ data: T }` 봉투 언랩용 헬퍼.
 *
 * `boards.ts` 의 같은 헬퍼가 export 되지 않아 `board-columns.ts` 와 마찬가지로 여기 한 벌 둔다 —
 * 두 줄짜리라 공유 비용이 더 크다(같은 BC 의 선례를 그대로 따른다).
 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) => z.object({ data: innerSchema })

/**
 * 카드 레이아웃 구성을 가지는 뷰 — 백엔드 `CardLayoutViewScope` 미러 (R3 · J18).
 *
 * 스크럼 보드는 **백로그**와 **활성 스프린트(보드)** 가 서로 다른 필드 집합을 가질 수 있다.
 * **칸반에는 `BACKLOG` 가 없다** — 보내면 서버가 400 이다(백엔드 `CardLayoutSettingsService`).
 * 어느 뷰를 편집할 수 있는지의 판정은 화면(`CardLayoutPanel`)이 보드 종류로 도출한다.
 */
export const CARD_LAYOUT_VIEW_SCOPES = ['BOARD', 'BACKLOG'] as const

/** 카드 레이아웃 뷰 스코프 타입. union 이라 `type` 이다. */
export type CardLayoutViewScope = (typeof CARD_LAYOUT_VIEW_SCOPES)[number]

/**
 * 뷰별 카드 레이아웃 구성 — 백엔드 `CardLayoutResponse.cardLayout` 미러.
 *
 * ★**구성이 없는 뷰는 키가 아예 없다**(백엔드 KDoc — 「빈 구성은 현행 카드를 그린다는 뜻」).
 * 그래서 두 뷰가 모두 `optional` 이다. 없는 뷰를 빈 배열로 채워 받으면 「구성 없음」과
 * 「빈 구성」이 화면에서 구분되지 않는다.
 *
 * 값은 필드 키 목록이고 **순서가 곧 카드에서의 자리**(`position`)다. 표준 필드는
 * `EPIC`·`PRIORITY` 처럼 카탈로그 이름이고 커스텀 필드는 `cf_` 접두사가 붙는다.
 */
export const cardLayoutSchema = z.object({
  BOARD: z.array(z.string()).optional(),
  BACKLOG: z.array(z.string()).optional(),
})

/** 뷰별 카드 레이아웃 구성 타입. */
export type CardLayout = z.infer<typeof cardLayoutSchema>

/** PATCH 응답 바디 — 백엔드 `CardLayoutResponse`. */
const cardLayoutResponseSchema = z.object({ cardLayout: cardLayoutSchema })

/**
 * 한 뷰의 카드 레이아웃을 통째로 교체한다 (J17 · J18).
 *
 * PATCH `/api/v1/boards/{boardId}/card-layout` → 200 + `{ data: { cardLayout } }`.
 *
 * ★**요청에 담지 않은 뷰는 서버가 건드리지 않는다**(백엔드 `CardLayoutSettingsService` KDoc).
 * 그래서 이 함수는 **한 뷰씩** 보낸다 — 보드를 저장할 때 백로그를 함께 실으면, 화면이 아직
 * 안 읽은 백로그 구성을 빈 목록으로 덮어쓴다.
 *
 * ★응답은 요청 echo 가 아니라 **저장 후 다시 읽은 전체 구성**이다. 호출자는 자기가 보낸 값이
 * 아니라 이 반환값을 화면 상태로 삼아야 한다 — 그래야 저장이 실제로 됐는지가 화면에 반영된다.
 *
 * @param boardId 대상 보드 UUID.
 * @param view 교체할 뷰. 칸반 보드에 `BACKLOG` 를 보내면 서버가 400 이다.
 * @param fieldKeys 그 뷰의 필드 키 목록. 순서가 곧 카드에서의 자리다. 빈 배열은
 *   「추가 필드 없음」이고 유효한 저장이다. **4개 이상이면 서버가 400** 이다(J17).
 * @returns 저장 후 **전체** 구성(두 뷰 모두).
 * @throws ApiError 비-2xx (400 상한 초과·미지원 필드 키·칸반의 백로그 · 401 · 403 권한 미충족 ·
 *   404 보드 미존재). ★본문 구조는 탭마다 다르므로(부채 177 Task 29 가 통일 예정)
 *   호출자는 **상태 코드로만** 갈라야 한다.
 * @throws ZodError 응답 스키마 불일치
 */
export async function replaceCardLayout(
  boardId: string,
  view: CardLayoutViewScope,
  fieldKeys: readonly string[],
): Promise<CardLayout> {
  const res = await apiFetch(`/api/v1/boards/${boardId}/card-layout`, {
    method: 'PATCH',
    body: { cardLayout: { [view]: fieldKeys } },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const data: unknown = await res.json()
  return dataResponseSchema(cardLayoutResponseSchema).parse(data).data.cardLayout
}
