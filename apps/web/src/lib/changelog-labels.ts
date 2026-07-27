// 이슈 변경 이력 타임라인에서 필드명·값을 사람이 읽는 표시명으로 해석하는 순수 함수 유틸 — FR-HS-02
import type { IssueTypeResponse } from '@/api/issue-types'
import type { Component } from '@/api/components.types'
import type { Version } from '@/api/versions.types'
import type { CustomField } from '@/api/custom-fields.types'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 타입 정의
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 변경 이력 단건 항목 — F1 changelog.ts의 ChangeItem과 동일 형태.
 * F1이 아직 없는 wave 병렬 환경을 위해 로컬 정의 유지.
 */
export interface ChangeItem {
  /** 변경된 필드 키 (예: "priority", "customField:my_key") */
  field: string
  /** 변경 전 raw 값 (null = 미설정) */
  fromValue: string | null
  /** 변경 후 raw 값 (null = 미설정) */
  toValue: string | null
  /** 변경 전 박제 표시명 (assignee/securityLevel만 non-null) */
  fromLabel: string | null
  /** 변경 후 박제 표시명 (assignee/securityLevel만 non-null) */
  toLabel: string | null
}

/**
 * resolveFieldLabel / resolveValueLabel에 주입하는 참조 데이터.
 * 컴포넌트 레벨에서 페이지 로드 시 조회한 데이터를 주입하므로 순수 함수 유지.
 */
export interface ChangelogRefs {
  /** 이슈 타입 목록 — typeId 숫자를 이름으로 해석 */
  types: IssueTypeResponse[]
  /** 프로젝트 컴포넌트 목록 — UUID를 이름으로 해석 */
  components: Component[]
  /** 프로젝트 버전 목록 — UUID를 이름으로 해석 */
  versions: Version[]
  /** priority 숫자 키 → 표시명 (1~5) */
  priorityMap: Record<number, string>
  /** impact 숫자 키 → 표시명 (1~3) */
  impactMap: Record<number, string>
  /** 커스텀 필드 정의 목록 — customField:<key>를 정의명으로 해석 */
  customFieldDefinitions: CustomField[]
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 상수
// ─────────────────────────────────────────────────────────────────────────────

/** customField 필드 키 prefix */
const CUSTOM_FIELD_PREFIX = 'customField:'

/**
 * 댓글 본문 변경 항목의 field 키 prefix — FR-CO-02.
 * 백엔드 `IssueHistoryRecorder.COMMENT_FIELD_PREFIX` 와 같은 값이어야 한다.
 */
const COMMENT_FIELD_PREFIX = 'comment:'

/**
 * 이력 피드에 노출할 댓글 본문 최대 길이(자) — NFR-5.
 *
 * 댓글 본문 상한은 32,000자인데 이력 항목 한 행에 이전·이후 **두 벌**이 나란히 실린다.
 * 자르지 않으면 댓글 수정 1건이 화면을 덮어 상태·담당자 변경 같은 다른 항목을 밀어낸다.
 * 120자면 두 벌 합쳐 240자로, 무엇을 고쳤는지 알아볼 만하면서 한 행이 서너 줄을 넘지 않는다.
 * 저장은 전문 그대로이고 여기서 자르는 것은 화면 표시뿐이다.
 */
const COMMENT_BODY_DISPLAY_MAX_LENGTH = 120

/**
 * 절단했음을 알리는 접미사.
 * 언어 중립 문장부호라 i18n 테이블이 아니라 여기에 둔다 (`IssueChangelog.tsx` 의 `→` 와 동류).
 */
const TRUNCATION_SUFFIX = '...'

/** UUID 배열 JSON을 저장하는 필드 키 집합 */
const UUID_ARRAY_FIELDS = new Set(['components', 'affectsVersions', 'fixVersions'])

/** raw 값을 그대로 표시하는 텍스트 필드 집합 */
const RAW_TEXT_FIELDS = new Set([
  'summary',
  'description',
  'environment',
  'labels',
  'startDate',
  'dueDate',
  'targetDate',
])

/** lifecycle 이벤트 키 */
const LIFECYCLE_CREATED = 'created'
const LIFECYCLE_DELETED = 'deleted'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 필드에 따라 UUID → name lookup Map을 빌드한다.
 * components 필드는 refs.components, 나머지(affectsVersions/fixVersions)는 refs.versions를 사용한다.
 */
function buildUuidLookup(field: string, refs: ChangelogRefs): ReadonlyMap<string, string> {
  const map = new Map<string, string>()
  const items = field === 'components' ? refs.components : refs.versions
  for (const item of items) {
    map.set(item.id, item.name)
  }
  return map
}

/**
 * UUID 배열 JSON 문자열을 name 배열로 변환한다.
 * 맵에 없는 UUID는 "(삭제됨)"으로 폴백한다.
 * 빈 배열은 "(없음)"을 반환한다.
 */
function resolveUuidArrayValue(
  raw: string,
  lookup: ReadonlyMap<string, string>,
): string {
  let ids: unknown
  try {
    ids = JSON.parse(raw)
  } catch {
    // JSON 파싱 실패 시 raw 그대로 반환
    return raw
  }

  if (!Array.isArray(ids)) {
    return raw
  }

  if (ids.length === 0) {
    return issueDetailStrings.changelogValueNone
  }

  return ids
    .map((id) =>
      typeof id === 'string'
        ? (lookup.get(id) ?? issueDetailStrings.changelogDeletedEntity)
        : issueDetailStrings.changelogDeletedEntity,
    )
    .join(', ')
}

/**
 * 댓글 본문을 이력 표시용으로 절단한다 (NFR-5).
 *
 * **★절단을 `comment:` 항목에만 적용하는 이유.**
 * `RAW_TEXT_FIELDS` 같은 공통 경로에 넣으면 `summary`·`description`·`environment`·`labels`·
 * 날짜 3종까지 함께 잘려 **기존 필드의 표시가 바뀐다(회귀)**. 길이 문제를 가진 것은 상한
 * 32,000자인 댓글 본문뿐이고, 나머지 필드는 지금까지 원문 전체를 보여 왔다.
 * (기존 텍스트 필드 테스트는 짧은 값만 써서 이 회귀를 잡지 못한다 — `changelog-labels.test.ts` 참조.)
 */
function truncateCommentBody(raw: string): string {
  if (raw.length <= COMMENT_BODY_DISPLAY_MAX_LENGTH) {
    return raw
  }
  return `${raw.slice(0, COMMENT_BODY_DISPLAY_MAX_LENGTH)}${TRUNCATION_SUFFIX}`
}

// ─────────────────────────────────────────────────────────────────────────────
// 공개 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 필드 키를 사람이 읽는 표시명으로 변환한다.
 *
 * - 표준 필드는 i18n 라벨 테이블을 참조한다.
 * - `customField:<key>` 형태는 refs.customFieldDefinitions에서 정의명을 찾는다.
 *   찾지 못하면 `<key>` 원문(prefix 제거)을 반환한다.
 * - `comment:<commentId>` 형태는 commentId를 버리고 "댓글"만 반환한다.
 *
 * @param field - 필드 키 (예: "priority", "customField:my_field", "comment:<uuid>")
 * @param refs  - 참조 데이터 (customFieldDefinitions만 사용)
 * @returns 사용자에게 노출할 필드 표시명
 */
export function resolveFieldLabel(field: string, refs: ChangelogRefs): string {
  if (field.startsWith(CUSTOM_FIELD_PREFIX)) {
    const key = field.slice(CUSTOM_FIELD_PREFIX.length)
    const def = refs.customFieldDefinitions.find((d) => d.key === key)
    return def?.name ?? key
  }

  // 댓글 본문 수정 — commentId는 사용자에게 의미 없는 UUID이므로 라벨에 싣지 않는다.
  // 이 분기가 없으면 `label ?? field` 폴백이 "comment:3f9a-..."를 그대로 화면에 노출한다.
  if (field.startsWith(COMMENT_FIELD_PREFIX)) {
    return issueDetailStrings.changelogCommentFieldLabel
  }

  const label = issueDetailStrings.changelogFieldLabels[field]
  return label ?? field
}

/**
 * 변경 항목의 값(from 또는 to)을 사람이 읽는 표시명으로 변환한다.
 *
 * 해석 우선순위(스펙 FR6).
 * 1. 박제 label (fromLabel/toLabel non-null) — 그대로 반환.
 * 2. lifecycle 특수 처리 — toValue 기준으로 "이슈를 생성/삭제했습니다" 반환.
 * 3. 텍스트/labels 필드 — raw 값 그대로.
 * 4. ID/숫자 필드 — refs로 해석 (priority/impact/type/components/versions/resolution/status).
 * 5. raw·label 모두 null(clear) — "(없음)".
 *
 * @param item - 변경 항목
 * @param side - 'from' 또는 'to'
 * @param refs - 참조 데이터
 * @returns 사용자에게 노출할 값 표시명
 */
export function resolveValueLabel(
  item: ChangeItem,
  side: 'from' | 'to',
  refs: ChangelogRefs,
): string {
  const label = side === 'from' ? item.fromLabel : item.toLabel
  const raw = side === 'from' ? item.fromValue : item.toValue

  // 우선순위 1. 박제 label
  if (label !== null && label !== undefined) {
    return label
  }

  // lifecycle 특수 처리 — side 무관하게 toValue 기준
  if (item.field === 'lifecycle') {
    if (item.toValue === LIFECYCLE_CREATED) {
      return issueDetailStrings.changelogLifecycleCreated
    }
    if (item.toValue === LIFECYCLE_DELETED) {
      return issueDetailStrings.changelogLifecycleDeleted
    }
    return raw ?? issueDetailStrings.changelogValueNone
  }

  // 댓글 본문 항목 — 아래 공통 null 폴백보다 **먼저** 판정해야 한다.
  // 백엔드가 삭제된 댓글의 값 4종을 null로 마스킹해 보내므로(FR-CO-02 S10),
  // 여기서의 null은 "값이 비어 있었다"가 아니라 "값은 있었지만 가려졌다"는 뜻이다.
  // 공통 폴백에 맡기면 "(없음)"이 나와 사용자가 빈 댓글로 고친 것으로 오해한다.
  if (item.field.startsWith(COMMENT_FIELD_PREFIX)) {
    if (raw === null || raw === undefined) {
      return issueDetailStrings.changelogCommentMasked
    }
    return truncateCommentBody(raw)
  }

  // raw가 null이면 "(없음)"
  if (raw === null || raw === undefined) {
    return issueDetailStrings.changelogValueNone
  }

  // 우선순위 3. 텍스트/labels 필드 — raw 그대로
  if (RAW_TEXT_FIELDS.has(item.field)) {
    return raw
  }

  // 우선순위 4. ID/숫자 필드 refs 해석

  if (item.field === 'priority') {
    const num = parseInt(raw, 10)
    return refs.priorityMap[num] ?? raw
  }

  if (item.field === 'impact') {
    const num = parseInt(raw, 10)
    return refs.impactMap[num] ?? raw
  }

  if (item.field === 'type') {
    const typeId = parseInt(raw, 10)
    const found = refs.types.find((t) => t.id === typeId)
    return found?.name ?? raw
  }

  if (UUID_ARRAY_FIELDS.has(item.field)) {
    return resolveUuidArrayValue(raw, buildUuidLookup(item.field, refs))
  }

  // status / resolution / 나머지 — raw 그대로
  return raw
}
