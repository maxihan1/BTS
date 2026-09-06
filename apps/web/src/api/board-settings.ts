// 보드 설정 탭 API 클라이언트 — 카드 레이아웃 PATCH(Task 16) · 시간 추적 PATCH(Task 17) · 작업일 PUT(Task 18) · 상세 보기 PATCH(Task 19)

import { z } from 'zod'
import { apiFetch, ApiError } from './client'
import {
  boardDetailViewFieldsSchema,
  boardWorkingDaysSchema,
  cardLayoutSchema,
  timeTrackingSchema,
} from './boards'
import type { BoardDetailViewFields, BoardWorkingDays, CardLayout, TimeTracking } from './boards'

/**
 * ### 오류 본문 — 호출자는 **상태 코드로만** 갈라야 한다
 *
 * 이 모듈의 네 함수는 실패를 `ApiError(status, body)` 로 던진다. `body` 는 담아만 두고
 * **구조를 읽지 않는다.** 읽으면 안 되는 이유는 봉투가 두 벌이기 때문이다.
 *
 * - **백엔드** — 네 탭이 `BoardExceptionHandler` 한 advice 를 지난다(`assignableTypes` 에
 *   `BoardCardLayoutController` · `BoardEstimationController` · `BoardWorkingDaysController` ·
 *   `BoardDetailViewController` 가 전부 있다). 본문은 RFC 7807 ProblemDetail(`type` · `title` ·
 *   `status` · `detail`)에 `errorCode`(`AGILE_` 접두사) 와 `timestamp` 를 얹은 모양이다.
 *   네 탭의 **404** 본문이 서로 같은지는 백엔드 `BoardSettingsTabErrorEnvelopeTest` 가 잰다.
 * - **MSW 목** — `mocks/board-handlers.ts` 의 `settingsError` 는 `{ errorCode, message }` 를 낸다.
 *   필드 구성도 다르고 코드값도 다르다(`AGILE_CARD_LAYOUT_INVALID` 등은 백엔드 상수에 없다).
 *
 * 그래서 본문 구조에 기대는 구현은 **목 위에서만 초록**이 되고 실제 API 에서 갈린다.
 * 상태 코드는 두 벌이 같으므로 그것으로만 가른다.
 */

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
 * PATCH 응답 바디 — 백엔드 `CardLayoutResponse`.
 *
 * ★**구성 스키마는 `boards.ts` 에 하나뿐이다.** PATCH 응답(`CardLayoutResponse.cardLayout`)과
 * 보드 조회 응답(`BoardDetailResponse.cardLayout`)이 **같은 모양**이라, 여기 한 벌을 더 두면
 * 두 정의가 서로를 검사하지 않은 채 갈린다 — 이 저장소의 지배적 결함 양식이다.
 * 소비자는 `CardLayout` 타입도 `@/api/boards` 에서 가져온다.
 */
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
 *   404 보드 미존재). ★호출자는 **상태 코드로만** 갈라야 한다 — 사유는 이 파일 상단
 *   「오류 본문」 절.
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

/**
 * 작업일 저장 요청 바디 — 백엔드 `WorkingDaysRequest` 미러 (J38·J39·J40).
 *
 * ★★**`standardDays` 의 `null` 과 `[]` 는 다른 값이다**(스펙 R6 · E1).
 * `null` 은 **미설정**이라 200 이고 그 보드의 번다운은 달력일 전부를 그대로 센다.
 * `[]` 는 **근무일 0개**라 400 이다(ideal 선의 0 나눗셈). 그래서 이 타입은 `readonly string[] | null`
 * 이지 `readonly string[]` 이 아니다 — 타입에서 뭉개면 화면이 두 상태를 가를 근거를 잃는다.
 *
 * @property standardDays 표준 근무일 요일 키(`MON`..`SUN`). **null = 미설정**(≠ 빈 배열).
 * @property nonWorkingDates 비근무일(ISO `yyyy-MM-dd`). 중복은 서버가 제거한다.
 * @property timezone IANA 타임존. null = 미설정(UTC).
 */
export interface WorkingDaysInput {
  standardDays: readonly string[] | null
  nonWorkingDates: readonly string[]
  timezone: string | null
}

/**
 * 작업일 설정 세 값을 통째로 저장한다 (J38·J39·J40).
 *
 * PUT `/api/v1/boards/{boardId}/working-days` → 200 + `{ data: { standardDays, nonWorkingDates, timezone } }`
 * (백엔드 `BoardWorkingDaysController.save`).
 *
 * ★**PATCH 가 아니라 PUT 이다.** 이 탭의 저장은 부분 갱신이 아니라 **교체**라서, 요청에 담지
 * 않은 비근무일은 지워진다. 호출자는 **바꾸지 않은 축까지 함께 실어야** 한다 — 요일만 보내면
 * 저장돼 있던 비근무일과 타임존이 그 순간 사라진다.
 *
 * ★응답은 요청 echo 가 아니라 **정규화된 저장값**이다(중복 날짜 제거 · 요일 주 순서 정렬).
 * 호출자는 자기가 보낸 값이 아니라 이 반환값을 화면 상태로 삼아야 사용자가 실제로 저장된 것을 본다.
 *
 * @param boardId 대상 보드 UUID.
 * @param input 저장할 세 값. `standardDays: null` 은 미설정이고 유효한 저장이다.
 * @returns 정규화 후 실제로 저장된 값.
 * @throws ApiError 비-2xx (400 근무일 0개(E1)·미지원 요일 키·비-IANA 타임존 · 401 ·
 *   403 권한 미충족 · 404 보드 미존재). ★호출자는 **상태 코드로만** 갈라야 한다 —
 *   사유는 이 파일 상단 「오류 본문」 절.
 * @throws ZodError 응답 스키마 불일치
 */
export async function saveWorkingDays(
  boardId: string,
  input: WorkingDaysInput,
): Promise<BoardWorkingDays> {
  const res = await apiFetch(`/api/v1/boards/${boardId}/working-days`, {
    method: 'PUT',
    body: {
      standardDays: input.standardDays === null ? null : [...input.standardDays],
      nonWorkingDates: [...input.nonWorkingDates],
      timezone: input.timezone,
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const data: unknown = await res.json()
  return dataResponseSchema(boardWorkingDaysSchema).parse(data).data
}

/**
 * 상세 보기 필드 그룹 4종 — 백엔드 `DETAIL_VIEW_FIELD_GROUPS` 미러 (J47).
 *
 * 선언 순서가 곧 화면 4구획의 위→아래 순서이자 응답의 키 순서다
 * (*"different groups of fields: General fields, Date fields, People, and Links."*).
 * 여기 없는 그룹을 보내면 서버가 **400** 이다(`DetailViewSettingsService`).
 */
export const DETAIL_VIEW_FIELD_GROUPS = ['GENERAL', 'DATE', 'PEOPLE', 'LINKS'] as const

/** 상세 보기 필드 그룹 타입. union 이라 `type` 이다. */
export type DetailViewFieldGroup = (typeof DETAIL_VIEW_FIELD_GROUPS)[number]

/**
 * PATCH·GET 응답 바디 — 백엔드 `DetailViewFieldsResponse`.
 *
 * ★**구성 스키마는 `boards.ts` 에 하나뿐이다.** 이 응답의 `groups` 와 보드 조회 응답의
 * `detailViewFields` 가 **같은 모양**이라(둘 다 그룹 4종을 항상 채운다), 여기 한 벌을 더 두면
 * 두 정의가 서로를 검사하지 않은 채 갈린다 — 카드 레이아웃이 같은 이유로 `cardLayoutSchema`
 * 한 벌만 둔 자리다.
 */
const detailViewFieldsResponseSchema = z.object({ groups: boardDetailViewFieldsSchema })

/**
 * 한 그룹의 상세 보기 필드 구성을 통째로 교체한다 (J47 · J48).
 *
 * PATCH `/api/v1/boards/{boardId}/detail-view-fields` → 200 + `{ data: { groups } }`.
 *
 * ★**요청에 담지 않은 그룹은 서버가 건드리지 않는다**(백엔드 `DetailViewSettingsService.replaceGroups`).
 * 그래서 이 함수는 **한 그룹씩** 보낸다 — 한 그룹을 저장하며 나머지 3종을 함께 실으면, 그 사이
 * 다른 관리자가 바꾼 그룹을 화면이 들고 있던 옛 값으로 덮는다.
 *
 * ★**개수 상한이 없다.** 카드 레이아웃의 0..2(J17)는 **카드**의 제약이고, J48 은 한 그룹에 여러
 * 필드를 순서대로 두는 것을 명시한다. 상한을 복사해 오면 화면이 서버보다 좁아진다.
 *
 * ★응답은 요청 echo 가 아니라 **저장 후 다시 읽은 전체 구성**이고 그룹 4종이 항상 실린다
 * (구성이 없는 그룹은 빈 배열 · R7c). 호출자는 자기가 보낸 값이 아니라 이 반환값을 화면
 * 상태로 삼아야 저장이 실제로 됐는지가 화면에 반영된다.
 *
 * @param boardId 대상 보드 UUID.
 * @param group 교체할 그룹. 4종 밖이면 서버가 400 이다.
 * @param fieldKeys 그 그룹의 필드 키 목록. **순서가 곧 상세 화면에서의 자리**이므로 정렬하거나
 *   집합으로 만들지 않는다(J48 의 드래그 결과가 이 순서다). 빈 배열은 그 그룹을 비운다.
 * @returns 저장 후 **전체** 구성(그룹 4종).
 * @throws ApiError 비-2xx (400 미지원 그룹 · 401 · 403 권한 미충족 · 404 보드 미존재).
 *   ★호출자는 **상태 코드로만** 갈라야 한다 — 사유는 이 파일 상단 「오류 본문」 절.
 * @throws ZodError 응답 스키마 불일치
 */
export async function replaceDetailViewFields(
  boardId: string,
  group: DetailViewFieldGroup,
  fieldKeys: readonly string[],
): Promise<BoardDetailViewFields> {
  const res = await apiFetch(`/api/v1/boards/${boardId}/detail-view-fields`, {
    method: 'PATCH',
    body: { groups: { [group]: [...fieldKeys] } },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const data: unknown = await res.json()
  return dataResponseSchema(detailViewFieldsResponseSchema).parse(data).data.groups
}

/**
 * PATCH 응답 봉투 — 백엔드 `DataResponse<EstimationSettingsResponse>`.
 *
 * 값 열거는 `boards.ts` 의 [timeTrackingSchema] 하나뿐이다. 여기 한 벌을 더 두면 두 정의가
 * 서로를 검사하지 않은 채 갈린다(이 저장소의 지배적 결함 양식).
 */
const estimationResponseSchema = z.object({
  data: z.object({ timeTracking: timeTrackingSchema }),
})

/**
 * 시간 추적 설정을 갱신한다 (J36 · J37).
 *
 * PATCH `/api/v1/boards/{boardId}/estimation` → 200 + `{ data: { timeTracking } }`
 * (백엔드 `BoardEstimationController.updateEstimation`).
 *
 * @param boardId 대상 보드 UUID.
 * @param timeTracking 저장할 값.
 * @returns 저장된 값. 요청 echo 가 아니라 **서버가 확정한 값**이다.
 * @throws ApiError 비-2xx (400 허용값 밖 · 401 · 403 권한 미충족 · 404 보드 미존재 ·
 *   **409 칸반 보드**(E5 — 404 가 아니다. 보드는 있고 조작이 막힌 것이다)).
 *   ★호출자는 **상태 코드로만** 갈라야 한다 — 사유는 이 파일 상단 「오류 본문」 절.
 * @throws ZodError 응답 스키마 불일치.
 */
export async function updateTimeTracking(
  boardId: string,
  timeTracking: TimeTracking,
): Promise<TimeTracking> {
  const res = await apiFetch(`/api/v1/boards/${boardId}/estimation`, {
    method: 'PATCH',
    body: { timeTracking },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const data: unknown = await res.json()
  return estimationResponseSchema.parse(data).data.timeTracking
}
