// 사이드바 트리에서 펼친 프로젝트들의 보드 목록을 한 번에 조회하는 훅 (Jira 패리티 캠페인 PR ⑩ · J2)
import { useMemo } from 'react'
import { useQueries } from '@tanstack/react-query'
import { fetchBoards, type BoardSummary } from '@/api/boards'
import { boardKeys } from '@/hooks/use-boards'

/** 보드 목록 캐시 유지 시간(ms) — {@link useBoards} 와 같은 30초. 같은 queryKey 를 쓰므로 갈리면 안 된다. */
const BOARD_LIST_STALE_TIME_MS = 30_000

/**
 * 프로젝트 키 → 보드 목록.
 *
 * **아직 도착하지 않은 키는 엔트리가 없다**(로딩 중 · 조회 실패). 「엔트리 없음」과
 * 「빈 배열」을 구분하는 것이 계약이다 — 전자는 아무것도 그리지 않고, 후자만 「보드 없음」
 * 문구를 낸다. 하나로 합치면 조회가 끝나기 전에 「보드 없음」이 한 프레임 스쳐 지나간다.
 */
export type BoardsByProject = ReadonlyMap<string, readonly BoardSummary[]>

/**
 * 펼친 프로젝트들의 보드 목록을 **`useQueries` 한 번**으로 모아 온다.
 *
 * ### 왜 행마다 `useBoards` 를 부르지 않는가
 * 훅은 조건부로 부를 수 없으므로 프로젝트 행 컴포넌트에서 `useBoards` 를 부르면 접힌 행까지
 * 전부 조회가 나간다. 프로젝트가 30개인 사용자는 사이드바를 여는 것만으로 30건을 쏜다.
 * 최상위에서 **펼쳐진 키만** 모아 `useQueries` 로 넘기면 요청 수가 펼친 개수와 같아진다
 * (`use-backlog-epics.ts` 가 세운 선례).
 *
 * ### 반환 참조 안정성
 * `combine` 은 **배열만** 돌려준다 — `Map` 은 `replaceEqualDeep` 의 대상이 아니라 결과가
 * 안 바뀌어도 매번 새 참조가 되고, 그러면 소비처의 `useMemo` 가 매 렌더 재계산된다.
 * 맵 조립은 바깥 `useMemo` 가 한다 (`use-backlog-epics.ts` 와 같은 규칙).
 *
 * `queryKey` 는 보드 화면과 **같은 키**(`boardKeys.list`)라 프로젝트를 열어 둔 채 보드로
 * 이동하면 캐시에 그대로 적중한다.
 *
 * @param projectKeys 조회할 프로젝트 키 — 호출자가 **펼쳐진 것만** 걸러 넘긴다
 */
export function useProjectBoards(projectKeys: readonly string[]): BoardsByProject {
  const { boardLists } = useQueries({
    queries: projectKeys.map((projectKey) => ({
      queryKey: boardKeys.list(projectKey),
      queryFn: () => fetchBoards(projectKey),
      staleTime: BOARD_LIST_STALE_TIME_MS,
      // 403/404 는 재시도해도 결과가 같고 트리 표시만 늦춘다 (`use-backlog-epics.ts` 와 동일)
      retry: false,
    })),
    combine: (results) => ({
      boardLists: results.map((result) => result.data ?? null),
    }),
  })

  return useMemo(() => {
    const byProject = new Map<string, readonly BoardSummary[]>()
    projectKeys.forEach((projectKey, index) => {
      const boards = boardLists[index]
      if (boards !== null && boards !== undefined) byProject.set(projectKey, boards)
    })
    return byProject
  }, [projectKeys, boardLists])
}
