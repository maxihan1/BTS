// 활성 프로젝트 조합 훅 — 프로젝트 목록 + 저장값 + URL 키를 하나로 묶는다 (FR-UX-07 Task 4)
import { useEffect, useMemo } from 'react'
import { useProjects } from './use-projects'
import { useActiveProject } from './use-active-project'
import { resolveActiveProjectKey, type ActiveProjectSource } from '@/lib/active-project'

/**
 * 활성 프로젝트 해소 결과 — **판별 유니온**.
 *
 * `projectKey`를 `status === 'ready'`일 때만 노출해, 소비처가 실수로 `null`을
 * `projectKey: string` 계약(`IssueListPage`·`SearchPage`·`SaveFilterDialog`·
 * `ExportDialog`)에 흘리는 것을 **타입 수준에서 차단**한다. 빈 문자열이 새면
 * 백엔드가 빈 스코프로 권한을 평가해 조용히 차단된다.
 */
export type ResolvedActiveProject =
  /** 프로젝트 목록 조회 중 — 이슈/검색 조회를 보류한다 (E1) */
  | { status: 'loading' }
  /** 목록 조회 실패 **且 캐시도 없음** — 저장값으로 추측 진행하지 않는다 (E2). `retry`로 재시도 */
  | { status: 'error'; retry: () => void }
  /** 접근 가능한 프로젝트가 0개 (S5) */
  | { status: 'empty' }
  /** 해소 완료 */
  | { status: 'ready'; projectKey: string; source: ActiveProjectSource }

/** 아직 해소되지 않은 상태 3종 — `ActiveProjectGate`의 입력 계약. `'ready'`는 표현 불가다. */
export type UnresolvedActiveProject = Exclude<ResolvedActiveProject, { status: 'ready' }>

/**
 * URL 키·저장값·접근 가능 목록을 묶어 활성 프로젝트를 해소하고, 필요하면 저장값을 갱신한다.
 *
 * **URL 키를 인자로 받는 이유.** 라우트 어댑터가 `useSearch({ strict: false }).projectKey`를
 * 주입한다. 저장소 관례("RouteAdapter가 라우터 값을 주입, 나머지는 라우터 비의존")를 따라
 * 이 훅을 라우터 없이 단위 테스트할 수 있게 한다.
 *
 * **이 훅이 존재하는 이유.** `/issues`와 `/search`가 각자 같은 조합 로직을 짜면 드리프트가
 * 확정된다(plan 리뷰 C2). 조합은 여기 한 곳에만 있다.
 *
 * **저장 규칙(FR4).** 출처가 `url` 또는 `first`면 저장한다.
 * - `first`를 저장하지 않으면 목록이 재조회될 때(`refetchOnWindowFocus` 기본 true) 첫 원소가
 *   바뀌면서 **사용자가 아무 조작도 안 했는데 프로젝트가 갈아탄다**. 저장이 그 앵커다.
 * - `stored`는 이미 저장된 값이라 write를 생략한다(E7).
 * - 출처가 `url`이어도 **목록에 없는 키는 저장하지 않는다**(E4) — 저장하면 접근 불가한 키가
 *   다음 방문에 낡은 값으로 되살아난다. 권한 실패는 소비처가 에러로 표시한다.
 *
 * @param urlProjectKey URL이 지정한 프로젝트 키(없으면 null/undefined)
 * @returns 해소 결과 — `status`로 분기한다
 */
export function useResolvedActiveProject(
  urlProjectKey: string | null | undefined,
): ResolvedActiveProject {
  const { data: projects, isError, refetch } = useProjects()
  const activeProjectKey = useActiveProject((s) => s.activeProjectKey)
  const setActiveProject = useActiveProject((s) => s.setActiveProject)

  const resolved = useMemo<ResolvedActiveProject>(() => {
    // ★ 캐시가 있으면 재조회 실패에도 그것을 쓴다 — `isError`를 데이터보다 먼저 보면
    // **캐시가 멀쩡한데도** 화면이 통째로 에러로 바뀐다. TanStack Query v5의 `isError`는
    // "데이터가 있는 채로 재조회만 실패한" 경우에도 true다(query-core가 `isRefetchError`를
    // `isError && hasData`로 정의하는 것이 그 증거). 이 저장소는 전역 `retry: false`
    // (`main.tsx`)에 `staleTime: 30_000` + `refetchOnWindowFocus` 기본 true라, 탭을 다녀온
    // 뒤 네트워크가 한 번만 끊겨도 잘 보이던 이슈 목록이 사라진다. 순서가 곧 가용성이다.
    if (projects !== undefined) {
      const { key, source } = resolveActiveProjectKey({
        urlKey: urlProjectKey,
        storedKey: activeProjectKey,
        projects,
      })
      if (key !== null) return { status: 'ready', projectKey: key, source }
      // 캐시가 아무것도 제공하지 못하는 경우에만 최신 실패 신호를 승격한다 —
      // '0개'와 '못 불러왔다'는 서로 다른 사실이고, 후자의 유일한 탈출구가 재시도다.
      if (isError) return { status: 'error', retry: () => void refetch() }
      return { status: 'empty' }
    }

    // 여기부터는 캐시가 없는 경우 — 콜드 에러만 error 로 승격한다
    if (isError) return { status: 'error', retry: () => void refetch() }
    return { status: 'loading' }
  }, [isError, projects, urlProjectKey, activeProjectKey, refetch])

  // 저장 조건은 원시값만 의존한다 — 객체를 의존성에 넣으면 매 렌더 새 참조라 무한 루프가 된다(N3)
  const readyKey = resolved.status === 'ready' ? resolved.projectKey : null
  const readySource = resolved.status === 'ready' ? resolved.source : null
  const isKnownProject = projects?.some((p) => p.key === readyKey) ?? false

  useEffect(() => {
    if (readyKey === null) return
    if (readySource !== 'url' && readySource !== 'first') return
    // 목록에 없는 키(E4 권한 실패 등)는 저장하지 않는다 — 낡은 값으로 되살아난다
    if (!isKnownProject) return
    setActiveProject(readyKey)
  }, [readyKey, readySource, isKnownProject, setActiveProject])

  return resolved
}
