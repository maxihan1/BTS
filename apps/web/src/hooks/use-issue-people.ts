// 이슈 상세의 담당자·보고자 UserSummary 를 id 조회로 확보하는 훅
import type { UserSummary } from '@/api/users'
import { useUsersByIds } from '@/hooks/use-users'

/** {@link useIssuePeople} 반환값 */
export interface IssuePeople {
  /** 현재 담당자. 미할당이거나 해석 실패면 null */
  currentAssignee: UserSummary | null
  /** 보고자. 해석 실패(탈퇴·비활성 사용자, 조회 미완)면 null */
  reporter: UserSummary | null
}

/**
 * 이슈 상세 화면의 담당자·보고자 이름을 **id 조회로** 확보한다.
 *
 * ## 검색 결과에서 find() 하지 않는다 (C1 회귀)
 * `useUsers`(담당자 셀렉터 검색 결과)에서 찾으면 검색어가 바뀔 때, 그리고 50건 한도 밖의
 * 사용자일 때 「미지정」으로 오표시된다. 그래서 두 사람 모두 id 조회로 따로 확보한다.
 *
 * ## 담당자와 보고자를 **한 배열로 묶지 않는다**
 * `useUsersByIds` 의 queryKey 에는 ids 배열이 들어간다. 한 배열로 묶으면 담당자가 바뀔 때마다
 * 키가 달라져 캐시 미스가 나고, **이미 알던 보고자 이름까지 같이 깜빡인다**
 * (`useUsersByIds` KDoc 의 C3 회귀와 같은 양식). `reporterId` 는 이슈 수명 동안 불변이라
 * 따로 두면 키가 안정적이다. 쿼리 2개지만 둘 다 캐시를 탄다.
 *
 * @param assigneeId 담당자 UUID (미할당이면 null/undefined)
 * @param reporterId 보고자 UUID (이슈 미로드면 null/undefined)
 * @returns 해석된 담당자·보고자 (각각 실패 시 null)
 */
export function useIssuePeople(
  assigneeId: string | null | undefined,
  reporterId: string | null | undefined,
): IssuePeople {
  const { data: assigneeList = [] } = useUsersByIds(assigneeId != null ? [assigneeId] : [])
  const { data: reporterList = [] } = useUsersByIds(reporterId != null ? [reporterId] : [])

  return {
    currentAssignee: assigneeList[0] ?? null,
    reporter: reporterList[0] ?? null,
  }
}
