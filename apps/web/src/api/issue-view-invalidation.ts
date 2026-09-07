// 이슈를 바꾸는 모든 뮤테이션이 공유하는 캐시 무효화 정본 — 「고쳤는데 새로고침해야 보인다」 차단
import type { QueryClient } from '@tanstack/react-query'

/**
 * 이슈의 내용을 **보여 주는** 화면들의 queryKey 접두 전량.
 *
 * ### 왜 목록을 한 곳에만 두는가
 * 종전에는 뮤테이션마다 무엇을 무효화할지 각자 적었고, 그 결과가 이랬다(2026-09-07 실측).
 *
 * | 뮤테이션 | 상세 | 목록 | 보드 | 백로그 |
 * |---|---|---|---|---|
 * | 이슈 상세 메타 8종 | ✅ | ❌ | ❌ | ❌ |
 * | 제목 수정 | ✅ | ❌ | ❌ | ❌ |
 * | 상태 전환 | ✅ | ❌ | ❌ | ❌ |
 * | 목록 인라인 셀 | ✅ | ✅(그 페이지만) | ❌ | ❌ |
 * | 보드 카드 | ❌ | ❌ | ✅(그 보드만) | ❌ |
 * | 일괄 작업 | ❌ | ❌ | ❌ | ❌ |
 *
 * 「뮤테이션 목록」과 「화면 목록」이 **서로를 검사하지 않는** 상태였고, 사용자에게는
 * 「이슈를 고쳤는데 목록·보드는 새로고침해야 바뀐다」로 나타났다(Maxi 보고 2026-09-07).
 * 목록을 하나로 모으면 화면이 늘어날 때 **여기 한 줄**만 더하면 된다.
 *
 * ### 접두인 이유
 * 실제 키에는 페이지·필터·정렬·보드 UUID 가 섞여 있다
 * (`['issues', projectKey, page, filter, sort]` · `['board', boardId, filter]`).
 * 바꾼 이슈가 **어느 페이지·어느 필터에 걸리는지** 클라이언트는 모른다 — 상태를 바꾸면 필터에서
 * 빠질 수도, 새로 들어올 수도 있다. 그래서 좁히지 않고 접두로 덮는다.
 *
 * 🛑 **`invalidateQueries()` 를 인자 없이 부르지 마라.** 그러면 사용자 목록·프로젝트 설정·권한
 *    까지 전부 다시 부른다. 이 결함의 처방은 「이슈를 보여 주는 화면」이지 「전부」가 아니다.
 *    짝 판별식 = `issue-view-invalidation.test.ts` 의 「무관한 캐시는 건드리지 않는다」.
 */
export const ISSUE_VIEW_KEY_PREFIXES: readonly (readonly string[])[] = [
  /** 이슈 목록 — `['issues', projectKey, page, filter, sort]` */
  ['issues'],
  /** 보드 상세 — `['board', boardId, filter]` */
  ['board'],
  /** 백로그 — `['backlog', projectKey, boardId]` */
  ['backlog'],
]

/**
 * 이슈가 바뀐 뒤, 그 이슈를 보여 주는 화면을 전부 갱신한다.
 *
 * **이슈를 바꾸는 뮤테이션은 예외 없이 이 함수를 부른다.** 직접 `invalidateQueries` 를 적으면
 * 그 자리만 최신이고 나머지는 옛 값을 보여 주는 상태로 되돌아간다 — 그것이 원래 결함이다.
 *
 * TanStack 은 **활성 쿼리만 즉시 refetch** 하고 나머지는 stale 로 표시한다. 그래서 지금 열려
 * 있는 화면 하나만 실제로 다시 부른다 — 접두를 넓게 잡아도 요청이 쏟아지지 않는다.
 *
 * @param queryClient 무효화할 클라이언트
 * @param issueKey 바뀐 이슈 키. **일괄 작업처럼 대상이 여럿이면 생략한다** — 접두만으로
 *   목록·보드·백로그가 회복되고, 열려 있지 않은 상세는 다음 조회에 새로 받는다
 */
export function invalidateIssueViews(queryClient: QueryClient, issueKey?: string): Promise<void> {
  const targets: (readonly unknown[])[] = [...ISSUE_VIEW_KEY_PREFIXES]

  if (issueKey !== undefined && issueKey !== '') {
    // 단건 상세와 그 이슈의 가용 전환 목록. 전환은 상태가 바뀌면 함께 바뀐다.
    targets.push(['issue', issueKey], ['issue-transitions', issueKey])
  }

  return Promise.all(
    targets.map((queryKey) => queryClient.invalidateQueries({ queryKey })),
  ).then(() => undefined)
}
