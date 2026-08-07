// 카드의 typeKey 를 유형 목록에서 해석해 카드에 넘길 원시 2필드로 바꾸는 단일 규칙 (FR-UX-14 F14)
import type { IssueTypeResponse } from '@/api/issue-types'

/** 카드가 받는 유형 표시용 원시 2필드. 객체 prop 을 카드에 넘기지 않기 위한 중간 형태다. */
export interface ResolvedCardType {
  /** lucide 아이콘 식별자. 해석 실패 시 null (IssueTypeIcon 이 Circle 로 fallback) */
  iconName: string | null
  /** 접근성 이름으로 쓰이는 유형 표시 이름. 해석 실패 시 typeKey 원문 */
  typeName: string
}

/**
 * `typeKey` 를 유형 목록에서 해석한다.
 *
 * ★ **보드·백로그의 세 호출부(`BoardColumn`·`BacklogColumn`·`SprintColumn`)가 공유하는
 * 단일 규칙이다.** 예전엔 같은 두 줄이 세 곳에 복제돼 있었는데, 그러면 FR6 이 바뀔 때
 * 두 곳만 고친 상태를 잡아낼 장치가 없어 **같은 이슈가 화면에 따라 다른 유형 이름으로
 * 보이는** 결함이 열린다 — 이 저장소의 지배 결함 양식이다.
 *
 * 해석 실패(로딩 중 · 조회 실패 · 미등록 커스텀 유형) 시 **`typeKey` 원문**을 이름으로 쓴다.
 * 빈 문자열이나 '알 수 없음' 같은 조용한 문구로 덮지 않는다 (FR6).
 *
 * @param issueTypesByKey `key` → 유형 맵 (상위에서 `useIssueTypes()` 로 1회 만든다)
 * @param typeKey 카드가 들고 있는 이슈 유형 키
 * @returns 카드에 그대로 넘길 원시 2필드
 */
export function resolveCardType(
  issueTypesByKey: Map<string, IssueTypeResponse>,
  typeKey: string,
): ResolvedCardType {
  const type = issueTypesByKey.get(typeKey)
  return {
    iconName: type?.iconName ?? null,
    typeName: type?.name ?? typeKey,
  }
}
