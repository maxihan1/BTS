// 이슈 상세 우측 메타패널 컴포넌트 — 상태 배지·전환 셀렉터·우선순위·영향도·환경·라벨·담당자·감시자·보안등급·커스텀필드·보고자·프로젝트·유형·버전·날짜 + 삭제 버튼
import type { JSX, RefObject } from 'react'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import type { IssueResponse, IssueTransition, CustomFieldValues } from '@/api/issues'
import { apiGet } from '@/api/client'
import { boardDetailViewFieldsSchema } from '@/api/boards'
import type { BoardDetailViewFields } from '@/api/boards'
import { DETAIL_VIEW_FIELD_GROUPS } from '@/api/board-settings'
import { boardKeys, useBoards } from '@/hooks/use-boards'
import { boardLabels } from '@/i18n/board-labels'
import type { IssueTypeResponse } from '@/api/issue-types'
import type { UserSummary } from '@/api/users'
import type { Component } from '@/api/components'
import type { Version } from '@/api/versions.types'
import { Button } from '@/components/ui/button'
import { ComponentMultiSelect } from '@/components/issue/ComponentMultiSelect'
import { VersionMultiSelect } from '@/components/issue/VersionMultiSelect'
import { IssueSecurityLevelSelect } from '@/components/issue/IssueSecurityLevelSelect'
import { IssueTypeIcon } from '@/components/issue/IssueTypeIcon'
import { useDateFormat } from '@/hooks/use-date-format'
import { issueDetailStrings } from '@/i18n/ko'
import { useIssueCreatePermissionGate } from '@/components/issue/create/use-issue-create-permission-gate'
import { useIssuePermissions } from '@/hooks/use-issue-permissions'
import { useCustomFields } from '@/hooks/use-custom-fields'
import { WatchersSection } from '@/components/issue/WatchersSection'
import { FavoriteButton } from '@/components/favorite/FavoriteButton'
import { IssuePrioritySelect } from '@/components/issue/meta/IssuePrioritySelect'
import { IssueImpactSelect } from '@/components/issue/meta/IssueImpactSelect'
import { IssueTypeSelect } from '@/components/issue/meta/IssueTypeSelect'
import { IssueEnvironmentEdit } from '@/components/issue/meta/IssueEnvironmentEdit'
import { IssueLabelsEdit } from '@/components/issue/meta/IssueLabelsEdit'
import { IssueCustomFieldsEdit } from '@/components/issue/meta/IssueCustomFieldsEdit'
import { IssueAssigneeSelect } from '@/components/issue/meta/IssueAssigneeSelect'
import { IssueReporterRow } from '@/components/issue/meta/IssueReporterRow'
import { IssueStateTransition } from '@/components/issue/meta/IssueStateTransition'

// ─────────────────────────────────────────────────────────────────────────────
// IssueMetaPanel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전환 컨트롤을 노출할 수 없는 사유.
 * - 'no-workflow': GET /transitions 422 — 워크플로우 미설정 (스펙 E5 S5)
 * - 'terminal': 200 + 빈 배열 — 종료상태, 더 이상 전환 없음 (스펙 E5 S6)
 * - null: 전환 가용 (정상 흐름)
 */
export type TransitionUnavailableReason = 'no-workflow' | 'terminal' | null

/** IssueMetaPanel props */
export interface IssueMetaPanelProps {
  /** 렌더할 이슈 데이터 */
  issue: IssueResponse
  /** 활성 이슈 타입 목록 — 유형 셀렉터 옵션 */
  availableTypes: IssueTypeResponse[]
  /** 유형 변경 핸들러 — 선택한 타입의 id(number)를 전달 */
  onTypeChange: (typeId: number) => void
  /** 삭제 버튼 클릭 핸들러 */
  onDeleteClick: () => void
  /** 클론 버튼 클릭 핸들러 */
  onCloneClick: () => void
  /** 현재 상태에서 가용한 전환 목록 */
  transitions: IssueTransition[]
  /** 전환 실행 핸들러 — 선택한 **전환 자체**를 전달 (같은 상태쌍의 두 전환을 가르기 위함) */
  onTransition: (transition: IssueTransition) => void
  /** 전환 진행 중 여부 — true 시 셀렉터 disabled (NFR3 중복클릭 방지) */
  isTransitioning: boolean
  /**
   * 전환 컨트롤을 노출할 수 없는 사유 (스펙 E5).
   * 'no-workflow' → 미설정 안내, 'terminal' | null → 종료상태 안내.
   * transitions.length > 0 이면 이 값과 관계없이 셀렉터가 노출된다.
   */
  unavailableReason?: TransitionUnavailableReason
  /** 우선순위 변경 핸들러 — 선택한 priority(1~5, number)를 전달 */
  onPriorityChange: (priority: number) => void
  /** 영향도 변경 핸들러 — 선택한 impact(1~3, number)를 전달 */
  onImpactChange: (impact: number) => void
  /** 환경 저장 핸들러 — 편집된 environment 문자열을 전달 */
  onEnvironmentSave: (environment: string) => void
  /** 라벨 저장 핸들러 — 편집된 labels 배열을 전달 */
  onLabelsSave: (labels: string[]) => void
  /** 사용자 검색 결과 목록 — 담당자 셀렉터에 노출 (routes에서 useUsers 결과 전달) */
  users: UserSummary[]
  /** 담당자 검색어 변경 핸들러 — 검색 input의 onChange 값을 전달 */
  onAssigneeSearch: (query: string) => void
  /** 담당자 변경 핸들러 — 선택한 사용자 UUID 또는 null(해제)을 전달 */
  onAssigneeChange: (userId: string | null) => void
  /**
   * 현재 담당자 UserSummary — useUsersByIds로 별도 조회한 값 (C1 버그 수정).
   * 검색결과(users)와 분리해 현재 담당자 이름을 안정적으로 표시한다.
   * null이면 "미지정" 표시.
   */
  currentAssignee: UserSummary | null
  /**
   * 보고자 UserSummary — `currentAssignee` 와 같은 `useUsersByIds` 경로로 route 가 해석해 넘긴다.
   *
   * ★옵셔널로 두지 않는다. 기본값 null 을 주면 route 가 안 넘겨도 컴파일이 통과하고,
   * 화면은 오늘과 똑같이 원시 UUID 를 그린다 — 고치려던 버그가 조용히 그대로 남는다.
   * null 은 「해석에 실패했다」(탈퇴·비활성 사용자, 조회 미완)만을 뜻한다.
   */
  reporter: UserSummary | null
  /** 현재 이슈에 할당된 컴포넌트 UUID 목록 — route에서 전달. 미전달 시 빈 배열. */
  componentIds?: string[]
  /** 프로젝트 컴포넌트 전체 목록 — fetchComponents(projectKey) 결과. 미전달 시 빈 배열. */
  components?: Component[]
  /** 컴포넌트 변경 콜백 — 새 UUID 배열 전달 (route가 useChangeComponents mutation 소유). 미전달 시 no-op. */
  onComponentsChange?: (ids: string[]) => void
  /** 프로젝트 버전 전체 목록 — useVersions(projectKey) 결과. 미전달 시 빈 배열. */
  versions?: Version[]
  /** 현재 이슈에 연결된 영향 버전 UUID 목록 — route에서 전달. 미전달 시 빈 배열. */
  affectsVersionIds?: string[]
  /** 영향 버전 변경 콜백 — 새 UUID 배열 전달 (route가 useChangeAffectsVersions mutation 소유). 미전달 시 no-op. */
  onAffectsVersionsChange?: (ids: string[]) => void
  /** 현재 이슈에 연결된 수정 버전 UUID 목록 — route에서 전달. 미전달 시 빈 배열. */
  fixVersionIds?: string[]
  /** 수정 버전 변경 콜백 — 새 UUID 배열 전달 (route가 useChangeFixVersions mutation 소유). 미전달 시 no-op. */
  onFixVersionsChange?: (ids: string[]) => void
  /**
   * 보안등급 변경 콜백 — 선택한 등급 UUID 또는 null(해제)을 전달.
   * route가 useChangeSecurityLevel mutation 소유. 미전달 시 no-op.
   */
  onSecurityLevelChange?: (levelId: string | null) => void
  /**
   * 커스텀 필드 저장 콜백 — 편집된 키-값 맵을 전달. (FR-IS-10)
   * route가 customFieldsMutation 소유. 미전달 시 no-op.
   */
  onCustomFieldsSave?: (customFields: CustomFieldValues) => void
  // ── FR-UX-10 F11 단축키 손잡이 4종 ──────────────────────────────────────────
  // 아래 넷은 이 패널이 **소비하지 않고 그대로 통과시키는** ref다. 실제 소비처는
  // routes/issues.$key.tsx의 useContextShortcuts('issue-detail') 핸들러다.
  // 미전달이면 해당 컨트롤에 aria-keyshortcuts도 붙지 않는다(하위 컴포넌트 규칙).
  /** 담당자 검색 input으로 통과시킬 ref — 단축키 `a` */
  assigneeSearchRef?: RefObject<HTMLInputElement | null>
  /** 라벨 입력으로 통과시킬 ref — 단축키 `l` (IssueLabelsEdit에서 2단 더 내려간다) */
  labelsInputRef?: RefObject<HTMLInputElement | null>
  /** 즐겨찾기 토글 버튼으로 통과시킬 ref — 단축키 `s` */
  favoriteToggleRef?: RefObject<HTMLButtonElement | null>
  /** Watch 토글 버튼으로 통과시킬 ref — 단축키 `w` */
  watchToggleRef?: RefObject<HTMLButtonElement | null>
}

// ─────────────────────────────────────────────────────────────────────────────
// 필드 권한 헬퍼 (FR-PM-07) — meta/IssueCustomFieldsEdit.tsx가 재사용하므로 export 유지
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 해당 필드가 열람 제한 대상인지 판정한다 (FR-PM-07 PR-B).
 *
 * restrictedFields에 포함된 필드는 UI에서 섹션 자체를 숨긴다.
 *
 * @param fieldKey - 판정할 필드 키 (예: 'environment', 'labels', 커스텀 필드 key)
 * @param restrictedFields - 열람 불가 필드 키 목록 (IssueResponse.restrictedFields)
 * @returns 해당 필드를 숨겨야 하면 true
 */
// eslint-disable-next-line react-refresh/only-export-components -- meta/IssueCustomFieldsEdit.tsx가 재사용하는 순수 함수 공개 (분해 A, 파일 분리는 files 범위 밖 — ShareFilterDialog.tsx 선례 동형)
export function isFieldHidden(fieldKey: string, restrictedFields: string[]): boolean {
  return restrictedFields.includes(fieldKey)
}

/**
 * 해당 필드의 편집 컨트롤을 비활성해야 하는지 판정한다 (FR-PM-07 PR-B).
 *
 * canEdit(useIssuePermissions UPDATE 권한)과 noneditableFields 중 하나라도
 * 막으면 disabled — AND 조합.
 *
 * @param fieldKey - 판정할 필드 키
 * @param canEdit - UPDATE 권한 여부 (fail-closed: 권한 미확정 시 false)
 * @param noneditableFields - 편집 불가 필드 키 목록 (IssueResponse.noneditableFields)
 * @returns 편집 컨트롤을 disabled로 설정해야 하면 true
 */
// eslint-disable-next-line react-refresh/only-export-components -- meta/IssueCustomFieldsEdit.tsx가 재사용하는 순수 함수 공개 (분해 A, 파일 분리는 files 범위 밖 — ShareFilterDialog.tsx 선례 동형)
export function isFieldDisabled(fieldKey: string, canEdit: boolean, noneditableFields: string[]): boolean {
  return !canEdit || noneditableFields.includes(fieldKey)
}

// ─────────────────────────────────────────────────────────────────────────────
// 보드 상세 보기 구성 (부채 177 Task 21 · 스펙 R7·R7c · J46~J48)
// ─────────────────────────────────────────────────────────────────────────────

/** 상세 보기 문구 정본 — 설정 화면(`DetailViewPanel`)과 **같은 카탈로그**를 쓴다. */
const detailViewLabels = boardLabels.settings.detailView

/**
 * 값이 없는 필드의 표기.
 *
 * 이 패널의 기존 규약과 같은 글자다 — 생성/수정 날짜가 null 이면 `useDateFormat` 이 이미
 * 이 문자로 그린다. 다른 글자를 쓰면 같은 화면 안에서 「값 없음」이 두 모양이 된다.
 */
const EMPTY_FIELD_VALUE = '—'

/** 구성 캐시 유지 시간(ms) — `useBoards` 와 같은 30초. 이슈를 열 때마다 재조회하면 왕복이 는다. */
const DETAIL_VIEW_STALE_TIME_MS = 30_000

/** GET `/api/v1/boards/{id}/detail-view-fields` 응답 봉투 — 백엔드 `DetailViewFieldsResponse`. */
const detailViewFieldsEnvelopeSchema = z.object({
  data: z.object({ groups: boardDetailViewFieldsSchema }),
})

/**
 * 보드의 상세 보기 구성을 조회한다 (T19 가 「이 GET 은 T21 의 소비 창구」로 못 박은 자리).
 *
 * ★**응답은 그룹 4종을 항상 채운다**(구성이 없는 그룹은 빈 배열 · R7c). 그 계약에 기대므로
 * 여기서 「키 부재」를 따로 다루지 않는다 — 모달과 사이드패널이 각자 부재를 다르게 처리하면
 * 한쪽만 고쳐진 상태가 생긴다는 것이 T13 이 이 계약을 세운 이유다.
 *
 * ★**`api/board-settings.ts` 가 아니라 여기 있는 이유** — 이 task 의 허용 파일이 셋뿐이라
 * `api/` 를 쓰기로 열 수 없었다(같은 wave 의 다른 task 가 그 파일을 잡고 있다). 응답 스키마는
 * `boards.ts` 의 정본(`boardDetailViewFieldsSchema`)을 그대로 쓰므로 정의가 갈리지는 않는다.
 * 이 함수 자체는 다음 기회에 `api/board-settings.ts` 로 옮기는 것이 맞다.
 *
 * @param boardId 대상 보드 UUID.
 * @returns 그룹 4종 → 필드 키 목록. 순서가 곧 화면 순서다(J48).
 * @throws ApiError 비-2xx (401 · 403 BROWSE 미충족 · 404 보드 미존재)
 * @throws ZodError 응답 스키마 불일치
 */
async function fetchDetailViewFields(boardId: string): Promise<BoardDetailViewFields> {
  const envelope = await apiGet(
    `/api/v1/boards/${boardId}/detail-view-fields`,
    detailViewFieldsEnvelopeSchema,
  )
  return envelope.data.groups
}

/**
 * 필드 값 도출에 필요한 것들 — 이 패널이 이미 들고 있는 것만 담는다.
 *
 * 새 조회를 여기서 만들지 않는다. 값 창구가 없는 필드(감시자·연결된 이슈·보안 등급)는
 * [EMPTY_FIELD_VALUE] 로 그리고 그 사실을 [DETAIL_VIEW_FIELD_SPECS] 에 적어 둔다 —
 * 없는 값을 지어내는 것보다 없다고 말하는 편이 정직하다.
 */
interface DetailViewValueContext {
  /** 그리는 이슈. */
  issue: IssueResponse
  /** 현재 담당자(별도 조회 결과). null 이면 미지정. */
  currentAssignee: UserSummary | null
  /**
   * 보고자(별도 조회 결과). null 이면 **해석 실패**(탈퇴·비활성 사용자, 조회 미완)다.
   *
   * ★[IssueReporterRow] 가 받는 것과 **같은 값**이다. 같은 패널 안에서 기본 보고자 행은
   * 이름을, 구성으로 켠 `reporter` 행은 UUID 를 그리는 두 모양이 되지 않게 하려면 두 자리가
   * 같은 출처를 봐야 한다 — `#462` 가 기본 행에서 고친 버그를 여기에 복제하지 않는다.
   */
  reporter: UserSummary | null
  /** 이 이슈의 컴포넌트 UUID 목록. */
  componentIds: string[]
  /** 프로젝트 컴포넌트 전체 — 이름 해석용. */
  components: Component[]
  /** 프로젝트 버전 전체 — 이름 해석용. */
  versions: Version[]
  /** 이 이슈의 영향 버전 UUID 목록. */
  affectsVersionIds: string[]
  /** 이 이슈의 수정 버전 UUID 목록. */
  fixVersionIds: string[]
  /** 날짜(연-월-일) 포맷터. null 이면 이미 `—` 를 돌려준다. */
  formatDate: (iso: string | null) => string
  /** 날짜+시각 포맷터. */
  formatDateTime: (iso: string | null) => string
}

/**
 * 구성 키 하나에 대응하는 렌더러 (J47 의 카탈로그 주석 — *"키 하나가 렌더러 하나에 대응한다"*).
 *
 * @property restrictedAs 열람 제한 판정(FR-PM-07)에 쓰는 **`IssueResponse` 필드 이름**.
 *   구성 키(`assignee`)와 응답 필드 이름(`assigneeId`)이 다르므로 여기서 잇는다 —
 *   이어 두지 않으면 제한된 필드의 값이 이 구획으로 **새어 나간다**. 제한 대상이 아니면 null.
 * @property resolve 값 도출. 값이 없으면 null 을 돌려주고 화면이 [EMPTY_FIELD_VALUE] 를 그린다.
 */
interface DetailViewFieldSpec {
  restrictedAs: string | null
  resolve: (context: DetailViewValueContext) => string | null
}

/** 구성에 담길 수 있는 표준 필드 키 — **카탈로그가 곧 허용값이다**(`DetailViewPanel` 과 같은 규칙). */
type DetailViewFieldKey = keyof typeof detailViewLabels.fieldLabels

/** 값이 비면 null — 「빈 문자열」과 「없음」을 화면이 가르지 못하게 뭉개지 않는다. */
function joinOrNull(values: readonly string[]): string | null {
  return values.length > 0 ? values.join(', ') : null
}

/** UUID 목록을 이름 목록으로 바꾼다. 카탈로그에 없는 id 는 버린다(삭제된 컴포넌트/버전). */
function namesOf(ids: readonly string[], catalog: readonly { id: string; name: string }[]): string[] {
  return ids
    .map((id) => catalog.find((entry) => entry.id === id)?.name)
    .filter((name): name is string => name !== undefined)
}

/**
 * 구성 키 → 렌더러 표.
 *
 * ★**`Record<DetailViewFieldKey, …>` 로 묶는 것이 이 표의 방어선이다.** 카탈로그
 * (`i18n/board-labels.ts` 의 `fieldLabels`)에 키가 늘면 여기가 컴파일 에러가 되어
 * 「설정 화면에서는 고를 수 있는데 상세는 못 그리는」 상태가 조용히 생기지 않는다 —
 * 두 목록이 서로를 검사하지 않는 자리를 타입으로 닫았다.
 *
 * ★**값 창구가 없는 셋**(`securityLevel`·`watchers`·`issueLinks`)은 null 을 돌려준다.
 * 이름만 있고 값이 없는 셈인데, 그 셋을 그리려면 이 패널에 조회를 새로 달아야 하고
 * (보안 등급 이름 · 감시자 목록 · 링크 목록) 그것은 이 task 의 파일 범위를 넘는다.
 * 숨기지 않는 이유는 「구성에 넣었는데 상세에 없다」가 더 나쁜 거짓말이기 때문이다.
 */
const DETAIL_VIEW_FIELD_SPECS: Record<DetailViewFieldKey, DetailViewFieldSpec> = {
  status: { restrictedAs: null, resolve: (c) => c.issue.currentStateKey },
  issueType: { restrictedAs: null, resolve: (c) => c.issue.typeName },
  priority: { restrictedAs: null, resolve: (c) => c.issue.priorityName },
  impact: { restrictedAs: 'impact', resolve: (c) => c.issue.impactName },
  resolution: { restrictedAs: null, resolve: (c) => c.issue.resolution?.name ?? null },
  labels: { restrictedAs: 'labels', resolve: (c) => joinOrNull(c.issue.labels) },
  components: {
    restrictedAs: 'componentIds',
    resolve: (c) => joinOrNull(namesOf(c.componentIds, c.components)),
  },
  environment: { restrictedAs: 'environment', resolve: (c) => c.issue.environment },
  securityLevel: { restrictedAs: 'securityLevelId', resolve: () => null },
  fixVersions: {
    restrictedAs: 'fixVersionIds',
    resolve: (c) => joinOrNull(namesOf(c.fixVersionIds, c.versions)),
  },
  affectsVersions: {
    restrictedAs: 'affectsVersionIds',
    resolve: (c) => joinOrNull(namesOf(c.affectsVersionIds, c.versions)),
  },
  createdAt: { restrictedAs: null, resolve: (c) => c.formatDateTime(c.issue.createdAt) },
  updatedAt: { restrictedAs: null, resolve: (c) => c.formatDateTime(c.issue.updatedAt) },
  startDate: { restrictedAs: null, resolve: (c) => c.formatDate(c.issue.startDate ?? null) },
  dueDate: { restrictedAs: null, resolve: (c) => c.formatDate(c.issue.dueDate ?? null) },
  assignee: {
    restrictedAs: 'assigneeId',
    resolve: (c) =>
      c.currentAssignee === null
        ? null
        : (c.currentAssignee.displayName ?? c.currentAssignee.username),
  },
  // ★해석 사다리와 폴백은 [IssueReporterRow] 와 **글자 단위로 같다** — `displayName ?? username`,
  //   실패하면 원시 UUID. 담당자(`assignee`)처럼 null 을 돌려 `—` 로 그리지 않는다. 보고자는
  //   이슈 생성 시 반드시 정해지므로 「없음」이라는 상태 자체가 없고, 비우면 「보고자가 없는 이슈」
  //   라는 더 나쁜 거짓말이 된다(그 근거는 `IssueReporterRow` KDoc 에 있다).
  reporter: {
    restrictedAs: null,
    resolve: (c) =>
      c.reporter !== null ? (c.reporter.displayName ?? c.reporter.username) : c.issue.reporterId,
  },
  watchers: { restrictedAs: null, resolve: () => null },
  issueLinks: { restrictedAs: null, resolve: () => null },
  parent: { restrictedAs: null, resolve: (c) => c.issue.parent?.key ?? null },
  epic: { restrictedAs: null, resolve: (c) => c.issue.epic?.key ?? null },
}

/**
 * 필드 키의 표시 이름.
 *
 * ★**모르는 키는 숨기지 않고 원문 그대로 그린다** — 설정 화면(`DetailViewPanel.fieldLabel`)과
 * **같은 규칙**이다. 다른 경로로 저장된 키를 상세가 못 그리면 사용자는 자기가 설정한 것이
 * 반영되지 않았다고 읽는다.
 *
 * @param key 저장된 필드 키.
 * @returns 사람이 읽는 이름. 카탈로그에 없으면 키 원문.
 */
function detailViewFieldLabel(key: string): string {
  const known: Record<string, string> = detailViewLabels.fieldLabels
  return known[key] ?? key
}

/**
 * 필드 키의 렌더러. 카탈로그 밖 키는 렌더러가 없다(이름만 그리고 값은 비운다).
 *
 * @param key 저장된 필드 키.
 */
function detailViewFieldSpec(key: string): DetailViewFieldSpec | undefined {
  const table: Record<string, DetailViewFieldSpec | undefined> = DETAIL_VIEW_FIELD_SPECS
  return table[key]
}

/** 화면에 그릴 한 줄. */
interface DetailViewRow {
  /** 저장된 필드 키 — `key` prop 과 `data-field-key` 에 그대로 쓴다. */
  fieldKey: string
  /** 표시 이름. */
  label: string
  /** 표시 값. 값이 없으면 [EMPTY_FIELD_VALUE]. */
  value: string
}

/**
 * 한 그룹의 구성을 화면 줄로 바꾼다.
 *
 * ★**정렬하지 않는다.** 배열 순서가 곧 J48 의 드래그 결과이고, 그것이 상세에서의 자리다.
 * ★**열람 제한 필드는 뺀다**(FR-PM-07). 구성은 「무엇을 보여줄지」이지 「권한을 무시할지」가 아니다.
 * ★**개수 상한이 없다**(J48). 카드 레이아웃의 0..2(J17)는 카드의 제약이라 복사해 오지 않는다.
 *
 * @param fieldKeys 그 그룹의 필드 키 목록(저장 순서).
 * @param context 값 도출에 쓰는 것들.
 * @returns 그릴 줄 목록. 비면 그 그룹은 아무것도 그리지 않는다.
 */
function toDetailViewRows(
  fieldKeys: readonly string[],
  context: DetailViewValueContext,
): DetailViewRow[] {
  const rows: DetailViewRow[] = []
  for (const fieldKey of fieldKeys) {
    const spec = detailViewFieldSpec(fieldKey)
    // 카탈로그 밖 키는 제한 판정도 키 원문으로 한다 — 커스텀 필드 키가 그대로 제한 목록에 실린다.
    const restrictedAs = spec === undefined ? fieldKey : spec.restrictedAs
    if (restrictedAs !== null && isFieldHidden(restrictedAs, context.issue.restrictedFields)) continue
    rows.push({
      fieldKey,
      label: detailViewFieldLabel(fieldKey),
      value: spec?.resolve(context) ?? EMPTY_FIELD_VALUE,
    })
  }
  return rows
}

/** 한 그룹의 화면 조각 — 그릴 줄이 있는 그룹만 담는다. */
interface DetailViewGroupRows {
  /** 그룹 키. 표시 이름과 목록 접근성 이름을 여기서 뽑는다. */
  group: (typeof DETAIL_VIEW_FIELD_GROUPS)[number]
  /** 그 그룹에 그릴 줄. **비어 있는 그룹은 이 목록에 들어오지 않는다.** */
  rows: DetailViewRow[]
}

/** [useIssueDetailViewFields] 반환. */
interface IssueDetailViewFieldsState {
  /** 그릴 그룹 — 구성이 없으면 빈 배열이고, 그때 화면은 이 구획을 통째로 그리지 않는다. */
  groups: DetailViewGroupRows[]
  /** 구성 조회가 실패했는가. 보드가 **없는** 것은 실패가 아니다. */
  isError: boolean
  /** 재시도 — 보드 목록과 구성을 함께 다시 부른다. */
  retry: () => void
}

/**
 * 이 이슈를 그릴 때 쓸 **보드 상세 보기 구성**을 읽는다 (스펙 R7·R7c · J46~J48).
 *
 * ## ★이 훅이 하나뿐이어야 하는 이유 (R7c)
 *
 * 상세는 세 자리에 뜬다 — 전체화면(`/issues/$key`) · 모달(`IssueDetailModal`) ·
 * 사이드패널(`IssueDetailSidePanel`). 뒤의 둘은 `#455` 가 낸 토글이고 **셋 다 같은
 * `IssueDetailPage`(variant='pane')를 그리며, 그 안의 메타패널은 이 컴포넌트 하나**다.
 * 그래서 구성 읽기를 **여기** 두는 한 「같은 이슈가 열기 방식에 따라 다르게 보인다」가
 * 구조적으로 불가능하다 — 분기가 없기 때문이다.
 *
 * 🛑 **이 읽기를 껍데기(`IssueDetailModal`/`IssueDetailSidePanel`)로 끌어올리지 마라.**
 * 그 순간 같은 로직이 두 벌이 되고, 한쪽만 고치는 날 R7c 가 깨진다. 그것이 이 task 의
 * RED 가 재현한 상태이고 판정은 `IssueMetaPanel.test.tsx` 의 T21-1 ↔ T21-2 짝이 진다
 * (실측 — 이 훅이 `presentation === 'modal'` 일 때만 구성을 내게 바꾸면 T21-2 **만** red 다).
 *
 * ## ★어느 보드의 구성인가 — 판단과 근거
 *
 * 이슈는 보드가 아니라 **프로젝트**에 속하는데 상세 보기 구성은 **보드 단위**다(J46 · 편차
 * X8·X10). 스펙(R7·R7c)과 계획(Task 21) 어디에도 「보드가 여럿일 때 어느 것인가」에 대한
 * 답이 없다. 그래서 **가장 단순한 해석**을 택했다 — **그 프로젝트의 첫 보드**(`useBoards`
 * 응답의 0번). 근거 셋.
 *
 * 1. 보드 화면이 `?board=` 없이 열렸을 때 고르는 것과 **같은 보드**다
 *    (`projects.$projectKey.board.tsx` 의 `boards[0]?.boardId`). 새 「기본 보드」 규칙을
 *    만들지 않는다 — 그 규칙이 이미 세 곳에서 갈렸다는 것이 부채 164 다.
 * 2. 상세는 진입점이 13곳(보드·백로그·목록·인박스·검색·대시보드…)이라 「열린 맥락의 보드」를
 *    쓰려면 그 13곳 전부가 보드 id 를 실어 와야 한다. 실어 오지 않는 곳에서는 같은 이슈가
 *    **어디서 열었느냐에 따라** 다르게 보이게 되고, 그것은 R7c 가 막으려는 증상과 같은 종류다.
 * 3. 보드 id 를 상세로 흘리는 배선은 이 task 의 허용 파일 셋(메타패널·상세 라우트·그 테스트)
 *    밖이다(스토어·보드 라우트·백로그를 함께 고쳐야 한다).
 *
 * ★**알면서 남기는 한계** — 한 프로젝트에 보드가 둘 이상이면 **둘째 보드의 구성은 상세에
 * 반영되지 않는다.** 보드 스코프를 상세까지 흘리기로 결정하면 **고칠 자리는 이 훅 한 곳**이다.
 *
 * ## 상태 3종
 *
 * - 로딩 — 아무것도 그리지 않는다. 이 구획은 **구성이 있는 보드에서만** 뜨는 보조 정보라,
 *   스켈레톤을 깔면 대부분의 보드에서 「떴다가 사라지는 빈 상자」가 된다.
 * - 에러 — 화면에 남는 안내 + 재시도([IssueDetailViewFieldsState.isError]). 토스트 단독으로
 *   두면 지나친 사용자는 「구성이 비었다」와 「못 읽었다」를 가를 수 없다.
 * - 빈 — 구획 자체가 없다(현행 유지). 보드가 아예 없는 프로젝트도 같다 — **실패가 아니다.**
 *
 * @param context 값 도출에 쓰는 것들. 이 패널이 이미 들고 있는 것만 담는다.
 * @returns 그릴 그룹 · 에러 여부 · 재시도.
 */
function useIssueDetailViewFields(context: DetailViewValueContext): IssueDetailViewFieldsState {
  const boardsQuery = useBoards(context.issue.projectKey)
  const boardId = boardsQuery.data?.[0]?.boardId

  // queryKey 는 `boardKeys.detail` 을 접두로 쓴다 — 설정 화면이 저장 후 무효화하는 키가
  // `boardKeys.detail(boardId)` 라(`DetailViewPanel`), 접두 일치로 이 구성도 함께 신선해진다.
  const fieldsQuery = useQuery({
    queryKey: [...boardKeys.detail(boardId), 'detail-view-fields'],
    queryFn: (): Promise<BoardDetailViewFields> => {
      // `enabled` 가 막지만 타입은 그것을 모른다 — `useBoard` 와 같은 형태로 둔다.
      if (boardId === undefined) {
        throw new Error('boardId is required')
      }
      return fetchDetailViewFields(boardId)
    },
    enabled: boardId !== undefined,
    staleTime: DETAIL_VIEW_STALE_TIME_MS,
  })

  const fields = fieldsQuery.data
  const groups =
    fields === undefined
      ? []
      : DETAIL_VIEW_FIELD_GROUPS.map((group) => ({
          group,
          rows: toDetailViewRows(fields[group], context),
        })).filter((entry) => entry.rows.length > 0)

  return {
    groups,
    isError: boardsQuery.isError || fieldsQuery.isError,
    retry: () => {
      void boardsQuery.refetch()
      void fieldsQuery.refetch()
    },
  }
}

/**
 * 보드가 정한 상세 보기 필드를 그린다 (J47 그룹 4종 · J48 순서).
 *
 * 그릴 것이 없으면 **아무것도 렌더하지 않는다** — 빈 칸을 그리면 사용자가 조회 실패로 읽는다.
 * (`DESIGN.md` §4 의 빈 상태 규칙은 「조회 결과 없음」에 대한 것이고, 여기 빈 것은
 * 「이 보드는 구성을 두지 않았다」라 안내할 내용 자체가 없다.)
 *
 * @param props.state [useIssueDetailViewFields] 결과.
 */
function DetailViewFieldsSection({ state }: { state: IssueDetailViewFieldsState }): JSX.Element | null {
  if (state.isError) {
    return (
      <div
        className="border border-border rounded-xl px-3.5 py-3"
        role="alert"
        data-testid="detail-view-error"
      >
        <p className="text-sm mb-2">{boardLabels.settings.loadError}</p>
        <Button variant="outline" size="sm" className="w-full min-h-[44px]" onClick={state.retry}>
          {boardLabels.settings.retry}
        </Button>
      </div>
    )
  }

  if (state.groups.length === 0) return null

  return (
    <div className="border border-border rounded-xl overflow-hidden" data-testid="detail-view-fields">
      {state.groups.map(({ group, rows }) => (
        <div key={group} className="px-3.5 py-3 border-b border-border last:border-b-0">
          <p className="text-xs text-muted-foreground mb-1.5">{detailViewLabels.groupLabels[group]}</p>
          <ul
            className="flex flex-col gap-1.5"
            aria-label={detailViewLabels.listLabel(detailViewLabels.groupLabels[group])}
          >
            {rows.map((row) => (
              <li
                key={row.fieldKey}
                data-field-key={row.fieldKey}
                className="flex items-baseline justify-between gap-2"
              >
                <span
                  className="text-xs text-muted-foreground shrink-0"
                  data-testid="detail-view-field-name"
                >
                  {row.label}
                </span>
                <span className="text-sm font-medium truncate">{row.value}</span>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  )
}

/** IssueMetaPanel 하단 액션 두 개 — 클론·삭제. props 는 판정 결과만 받고 스스로 판정하지 않는다. */
function IssueMetaActions({
  onCloneClick,
  isCloneExplicitlyDenied,
  onDeleteClick,
  canDelete,
}: {
  onCloneClick: () => void
  isCloneExplicitlyDenied: boolean
  onDeleteClick: () => void
  canDelete: boolean
}): JSX.Element {
  return (
    <>
      {/* 클론 버튼 — 삭제 버튼 바로 위 배치.

          클론은 **대상 프로젝트의 CREATE 권한**을 요구한다(`IssueApplicationService.cloneIssue`).
          예전 주석은 「프론트 권한 API 가 CREATE 를 안 줘서 게이트가 불가능하다」였는데
          그 근거는 **거짓이 됐다** — `project-permissions.ts` 가 `CREATE` 를 내준다.

          ★판정식은 `CREATE === false`(**명시 거부만**)다. `!isLoading && === true`(미지=거부)를
          쓰면 권한 조회가 아직 안 끝났거나 실패한 구간에서 **CREATE 를 실제로 가진 사용자를
          영구 차단**한다 — `use-project-permissions.ts` 에 retry 도 에러 폴백도 없다.
          정본 논거는 `components/issue/create/use-issue-create-permission-gate.ts` KDoc.

          미지에서는 버튼을 열어 두고 서버 403 + `useCloneIssue.onError` 토스트가 최종 판정한다
          (기존 fail-safe 유지). 여기서 막는 것은 「서버가 확실히 거절할 액션」뿐이다. */}
      <Button
        variant="secondary"
        className="w-full min-h-[44px]"
        onClick={onCloneClick}
        disabled={isCloneExplicitlyDenied}
        aria-label={
          isCloneExplicitlyDenied
            ? issueDetailStrings.cloneButtonNoPermission
            : issueDetailStrings.cloneButton
        }
        title={isCloneExplicitlyDenied ? issueDetailStrings.cloneButtonNoPermission : undefined}
        data-testid="issue-clone"
      >
        {issueDetailStrings.cloneButton}
      </Button>

      {/* 삭제 버튼 — WCAG AA 44px 터치 타깃, 권한 없으면 disabled + 사유 표시 */}
      <Button
        variant="destructive"
        className="w-full min-h-[44px]"
        onClick={onDeleteClick}
        disabled={!canDelete}
        aria-label={canDelete ? issueDetailStrings.deleteButton : issueDetailStrings.deleteButtonNoPermission}
        title={canDelete ? undefined : issueDetailStrings.deleteButtonNoPermission}
        data-testid="issue-delete"
      >
        {issueDetailStrings.deleteButton}
      </Button>
    </>
  )
}

/**
 * 이슈 상세 우측 메타패널 컴포넌트.
 *
 * - 상태: 읽기전용 배지 + IssueStateTransition 전환 셀렉터
 * - 유형: IssueTypeIcon + typeName 표시 + 셀렉터(availableTypes 옵션)
 * - 셀렉터 현재값은 issue.typeId props 파생 (useState 초기화 금지 — stale key prop 회귀 방지)
 * - 우선순위: IssuePrioritySelect — 1~5 즉시 콜백
 * - 영향도: IssueImpactSelect — 1~3 즉시 콜백, impact null이면 미지정 활성, 설정 후 미지정 disabled
 * - 환경: IssueEnvironmentEdit — 로컬상태 + 저장 버튼 (refetch 시 props로 seed)
 * - 라벨: IssueLabelsEdit — 칩 추가/삭제 + 저장 버튼 (refetch 시 props로 seed)
 * - 보고자 UUID, 프로젝트 키, 버전(낙관락), 생성/수정 날짜 표시
 * - 생성/수정 날짜 null → "—" 표기
 * - 하단 "이슈 삭제" 버튼 → onDeleteClick 호출 (WCAG AA: min-h-[44px])
 */
export function IssueMetaPanel({
  issue,
  availableTypes,
  onTypeChange,
  onDeleteClick,
  onCloneClick,
  transitions,
  onTransition,
  isTransitioning,
  unavailableReason = null,
  onPriorityChange,
  onImpactChange,
  onEnvironmentSave,
  onLabelsSave,
  users,
  onAssigneeSearch,
  onAssigneeChange,
  currentAssignee,
  reporter,
  componentIds = [],
  components = [],
  onComponentsChange = () => { /* no-op */ },
  versions = [],
  affectsVersionIds = [],
  onAffectsVersionsChange = () => { /* no-op */ },
  fixVersionIds = [],
  onFixVersionsChange = () => { /* no-op */ },
  onSecurityLevelChange = () => { /* no-op */ },
  onCustomFieldsSave = () => { /* no-op */ },
  assigneeSearchRef,
  labelsInputRef,
  favoriteToggleRef,
  watchToggleRef,
}: IssueMetaPanelProps): JSX.Element {
  // 프로젝트 커스텀 필드 정의 조회 (FR-IS-10)
  const { data: customFieldDefs = [] } = useCustomFields(issue.projectKey)

  /**
   * 이 프로젝트에 이슈를 만들 권한이 **명시적으로** 없는가.
   *
   * 클론이 요구하는 권한은 UPDATE 가 아니라 **대상 프로젝트의 CREATE** 다.
   * 미지(로딩·조회 실패)는 거부로 읽지 않는다 — 아래 클론 버튼 주석에 근거가 있다.
   */
  const isCloneExplicitlyDenied = useIssueCreatePermissionGate(issue.projectKey)

  // 로그인 사용자의 date_format 환경설정을 반영한 날짜 포맷터 (FR-PF-01 Task 8)
  const { formatDate, formatDateTime } = useDateFormat()

  // 보드가 정한 상세 보기 구성 — **모달·사이드패널·전체화면이 공유하는 단 하나의 읽기**(R7c).
  const detailView = useIssueDetailViewFields({
    issue,
    currentAssignee,
    reporter,
    componentIds,
    components,
    versions,
    affectsVersionIds,
    fixVersionIds,
    formatDate,
    formatDateTime,
  })

  // 권한 조회 — fail-closed: 로딩 중·에러·미확정이면 false(비활성)
  const { data: permissionsData, isLoading: isPermissionsLoading, isError: isPermissionsError } =
    useIssuePermissions(issue.key)
  const canEdit =
    !isPermissionsLoading && !isPermissionsError && permissionsData?.permissions.UPDATE === true
  const canDelete =
    !isPermissionsLoading && !isPermissionsError && permissionsData?.permissions.SOFT_DELETE === true

  /** issue.typeId에 해당하는 타입 항목 — iconName 해석에 사용 */
  const currentType = availableTypes.find((t) => t.id === issue.typeId)

  /**
   * 전환 셀렉터 제어값 — 전환 시도 후(성공/실패 모두) placeholder로 리셋.
   * 실패 후 같은 옵션 재선택 시 onChange 재발화를 보장한다 (C1 회귀 방지).
   */
  const [selectedTransition, setSelectedTransition] = useState('')

  function handleTransition(transition: IssueTransition) {
    onTransition(transition)
    // 전환 요청 직후 즉시 리셋 — 성공/실패 모두 placeholder로 복귀
    setSelectedTransition('')
  }

  return (
    <aside className="flex flex-col gap-3">
      {/* 보드가 정한 상세 보기 필드 — 조회 실패 안내와 빈 상태 판단은 섹션이 쥔다. */}
      <DetailViewFieldsSection state={detailView} />

      {/* 메타 패널 카드 */}
      <div className="border border-border rounded-xl overflow-hidden">
        {/* 상태 — 읽기전용 배지 + 전환 셀렉터 (FR-IS-01) */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.statLabel}</p>
          <span
            data-testid="issue-state-badge"
            className="inline-flex items-center gap-1.5 text-sm font-medium mb-1.5"
          >
            <span className="size-2 rounded-full bg-primary/60 shrink-0" aria-hidden="true" />
            {issue.currentStateKey}
          </span>
          <IssueStateTransition
            transitions={transitions}
            onTransition={handleTransition}
            isTransitioning={isTransitioning}
            unavailableReason={unavailableReason}
            selectedValue={selectedTransition}
            onSelectedValueChange={setSelectedTransition}
          />
        </div>

        {/* 해결 결과 — DONE 전환 후 resolution이 있을 때만 표시 (FR-IS-07) */}
        {issue.resolution != null && (
          <div className="px-3.5 py-3 border-b border-border">
            <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.resolutionLabel}</p>
            <p className="text-sm font-medium" data-testid="issue-resolution">{issue.resolution.name}</p>
          </div>
        )}

        {/* 유형 — 아이콘 + typeName 표시 + 셀렉터 */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.typeLabel}</p>
          <div className="flex items-center gap-1.5 mb-1.5">
            <IssueTypeIcon
              iconName={currentType?.iconName ?? null}
              typeName={issue.typeName}
            />
            <span className="text-sm font-medium" data-testid="issue-type-name">{issue.typeName}</span>
          </div>
          <IssueTypeSelect
            value={issue.typeId}
            availableTypes={availableTypes}
            onTypeChange={onTypeChange}
            currentTypeId={issue.typeId}
          />
        </div>

        {/* 우선순위 — IssuePrioritySelect (FR-IS-04) */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.priorityLabel}</p>
          <IssuePrioritySelect value={issue.priority} onPriorityChange={onPriorityChange} />
        </div>

        {/* 영향도 — IssueImpactSelect (FR-IS-04) */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.impactLabel}</p>
          <IssueImpactSelect value={issue.impact} onImpactChange={onImpactChange} />
        </div>

        {/* 환경 — IssueEnvironmentEdit (FR-IS-04). restrictedFields에 "environment"가 있으면 섹션 전체 숨김 (FR-PM-07) */}
        {!isFieldHidden('environment', issue.restrictedFields) && (
          <div className="px-3.5 py-3 border-b border-border" data-testid="environment-section">
            <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.environmentLabel}</p>
            <IssueEnvironmentEdit
              value={issue.environment}
              onSave={onEnvironmentSave}
              canEdit={!isFieldDisabled('environment', canEdit, issue.noneditableFields)}
            />
          </div>
        )}

        {/* 라벨 — IssueLabelsEdit (FR-IS-04). restrictedFields에 "labels"가 있으면 섹션 전체 숨김 (FR-PM-07) */}
        {!isFieldHidden('labels', issue.restrictedFields) && (
          <div className="px-3.5 py-3 border-b border-border" data-testid="labels-section">
            <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.labelsLabel}</p>
            <IssueLabelsEdit
              value={issue.labels}
              onSave={onLabelsSave}
              canEdit={!isFieldDisabled('labels', canEdit, issue.noneditableFields)}
              focusRef={labelsInputRef}
            />
          </div>
        )}

        {/* 담당자 — IssueAssigneeSelect (FR-IS-03). restrictedFields에 "assigneeId"가 있으면 섹션 전체 숨김 (FR-PM-07) */}
        {!isFieldHidden('assigneeId', issue.restrictedFields) && (
          <div className="px-3.5 py-3 border-b border-border" data-testid="assignee-section">
            <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.assigneeLabel}</p>
            <IssueAssigneeSelect
              value={issue.assigneeId ?? null}
              currentAssignee={currentAssignee}
              users={users}
              onSearch={onAssigneeSearch}
              onAssigneeChange={onAssigneeChange}
              canEdit={!isFieldDisabled('assigneeId', canEdit, issue.noneditableFields)}
              focusRef={assigneeSearchRef}
            />
          </div>
        )}

        {/* 즐겨찾기 — FavoriteButton (FR-UX-02). 감시자 섹션 위 배치. */}
        <div className="px-3.5 py-2 border-b border-border flex items-center" data-testid="favorite-section">
          <FavoriteButton targetType="ISSUE" targetId={issue.key} focusRef={favoriteToggleRef} />
        </div>

        {/* 감시자 — WatchersSection (FR-WT-01). 담당자/사람 관련 섹션 근처에 배치. */}
        <div className="px-3.5 py-3 border-b border-border" data-testid="watchers-section-wrapper">
          <WatchersSection issueKey={issue.key} focusRef={watchToggleRef} />
        </div>

        {/* 컴포넌트 — ComponentMultiSelect (FR-CM-02) */}
        <div className="px-3.5 py-3 border-b border-border" data-testid="components-section">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.componentsLabel}</p>
          <ComponentMultiSelect
            value={componentIds}
            options={components}
            onChange={onComponentsChange}
            disabled={!canEdit}
          />
        </div>

        {/* 영향 버전 — VersionMultiSelect affects (FR-VR-03) */}
        <div className="px-3.5 py-3 border-b border-border" data-testid="affects-versions-section">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.affectsVersionsLabel}</p>
          <VersionMultiSelect
            variant="affects"
            value={affectsVersionIds}
            options={versions}
            onChange={onAffectsVersionsChange}
            disabled={!canEdit}
          />
        </div>

        {/* 수정 버전 — VersionMultiSelect fix (FR-VR-03) */}
        <div className="px-3.5 py-3 border-b border-border" data-testid="fix-versions-section">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.fixVersionsLabel}</p>
          <VersionMultiSelect
            variant="fix"
            value={fixVersionIds}
            options={versions}
            onChange={onFixVersionsChange}
            disabled={!canEdit}
          />
        </div>

        {/* 보안등급 — IssueSecurityLevelSelect (FR-PM-06 PR-B) */}
        <div className="px-3.5 py-3 border-b border-border" data-testid="security-level-section">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.securityLevelLabel}</p>
          <IssueSecurityLevelSelect
            projectKey={issue.projectKey}
            value={issue.securityLevelId ?? null}
            onChange={onSecurityLevelChange}
            disabled={!canEdit}
          />
        </div>

        {/* 커스텀 필드 — 활성 정의 기준 렌더, 미정의 잔존 키 생략 (FR-IS-10 E-2).
            restrictedFields에 포함된 커스텀 필드는 IssueCustomFieldsEdit 내부에서 필터링 (FR-PM-07) */}
        {customFieldDefs.length > 0 && (
          <IssueCustomFieldsEdit
            fieldDefs={customFieldDefs}
            values={issue.customFields}
            onSave={onCustomFieldsSave}
            canEdit={canEdit}
            restrictedFields={issue.restrictedFields}
            noneditableFields={issue.noneditableFields}
          />
        )}

        <IssueReporterRow reporter={reporter} fallbackId={issue.reporterId} />

        {/* 프로젝트 */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.projectLabel}</p>
          <p className="text-sm font-medium">{issue.projectKey}</p>
        </div>

        {/* 버전 (낙관락 OCC 버전 번호) */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.versionLabel}</p>
          <p className="text-sm font-medium">v{issue.version}</p>
        </div>

        {/* 생성일 */}
        <div className="px-3.5 py-3 border-b border-border">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.createdAtLabel}</p>
          <p className="text-sm font-medium">{formatDateTime(issue.createdAt)}</p>
        </div>

        {/* 수정일 */}
        <div className="px-3.5 py-3">
          <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.updatedAtLabel}</p>
          <p className="text-sm font-medium">{formatDateTime(issue.updatedAt)}</p>
        </div>
      </div>

      <IssueMetaActions
        onCloneClick={onCloneClick}
        isCloneExplicitlyDenied={isCloneExplicitlyDenied}
        onDeleteClick={onDeleteClick}
        canDelete={canDelete}
      />
    </aside>
  )
}

