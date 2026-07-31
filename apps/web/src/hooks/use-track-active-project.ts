// 경로 파라미터(/projects/$projectKey/*) → 활성 프로젝트 저장값 기록기 (FR-UX-07 Task 5)
import { useEffect } from 'react'
import { useParams } from '@tanstack/react-router'
import { useActiveProject } from './use-active-project'
import { useRecentProjects } from './use-recent-projects'
import { useProjects } from './use-projects'

/**
 * 현재 라우트의 `$projectKey` 경로 파라미터를 활성 프로젝트 저장값에 기록한다.
 *
 * **왜 필요한가 (ADR D3).** "활성 프로젝트"는 URL이 프로젝트를 담을 때 갱신돼야 한다 —
 * `/projects/INFRA/board`를 보다가 사이드바 "이슈"를 누르면 INFRA 이슈가 나와야 한다.
 * 그런데 `/issues`에는 `$projectKey` 경로 파라미터가 없고, `/projects/$projectKey/*`
 * 라우트 어댑터들은 파라미터를 **하위 컴포넌트로 내리기만 할 뿐 저장하지 않는다**.
 * 기록하는 자리가 없으면 S4가 성립하지 않는다.
 *
 * **왜 셸에 두나.** 전 인증 라우트가 공유하는 유일한 지점이 `ShellLayout`이다. 라우트마다
 * 배선하면 새 프로젝트 라우트가 추가될 때 빠뜨린다.
 *
 * `enabled`가 false면 아무것도 하지 않는다 — `ShellLayout`의 미인증 분기에서 훅 규칙을
 * 지키면서(조건부 호출 금지) 기록만 끄기 위한 인자다.
 *
 * **접근 가능 목록에 없는 키는 기록하지 않는다** (코드리뷰 CR3). 저장값을 쓰는 생산 지점이
 * `useResolvedActiveProject`와 여기 **둘**인데 목록 대조 가드가 한쪽에만 있었다. 없으면
 * `/projects/TYPO/board`(404)를 한 번 여는 것만으로 사용자가 쓰던 활성 프로젝트가 조용히
 * 날아간다.
 *
 * 미인증 분기에는 `Sidebar`(→`ProjectTree`)가 렌더되지 않으므로 `enabled=false` 로 **쿼리 자체를
 * 끈다**. `_shell` 아래 미인증 도달 라우트는 `router.ts:82`(`/`)와 `router.ts:588`
 * (`/dashboards/shared/$token`, EC-11 — 로그인 리다이렉트 금지) 2곳이다. 인증 분기에서는
 * 사이드바와 queryKey 를 공유해 요청이 늘지 않는다.
 *
 * @param enabled 기록 활성화 여부 — `ShellLayout`은 `isAuthenticated`를 넘긴다
 */
export function useTrackActiveProject(enabled: boolean): void {
  const params = useParams({ strict: false }) as { projectKey?: unknown }
  const setActiveProject = useActiveProject((s) => s.setActiveProject)
  const pushRecentProject = useRecentProjects((s) => s.pushRecentProject)
  const { data: projects } = useProjects(false, { enabled })

  const raw = params.projectKey
  // 빈 문자열은 무시한다 — 흘러가면 백엔드가 빈 스코프로 권한을 평가해 조용히 차단된다
  const projectKey = typeof raw === 'string' && raw.length > 0 ? raw : null
  // 목록이 아직 없으면 기록을 보류한다 — 도착하면 이 effect가 다시 돈다
  const isKnownProject = projects?.some((p) => p.key === projectKey) ?? false

  useEffect(() => {
    if (!enabled) return
    if (projectKey === null) return
    if (!isKnownProject) return
    // 같은 값이면 스토어가 no-op 한다(E7) — 여기서 중복 가드를 또 두지 않는다
    setActiveProject(projectKey)
    // ★ 최근 목록도 **같은 가드 아래**에서 기록한다 (FR-UX-08 FR3).
    // 이 줄을 위 세 가드 밖으로 빼거나 별도 훅으로 분리하면, 저장값 생산 지점이 둘이 되고
    // 가드가 한쪽에만 걸린 상태가 된다 — CR3 가 정확히 그 결함이었다.
    // 이미 맨 앞이면 스토어가 no-op 한다(E5).
    pushRecentProject(projectKey)
  }, [enabled, projectKey, isKnownProject, setActiveProject, pushRecentProject])
}
