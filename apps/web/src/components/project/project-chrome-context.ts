// 프로젝트 셸 크롬의 두 가지 소유권(액션 자리 · 문서 h1)을 담는 컨텍스트와 조회 훅 (Jira 패리티 J5-9 · J5-11)
import { createContext, useContext } from 'react'

/**
 * 셸이 프로젝트 크롬(헤더+탭바)을 그리고 있는가, 그리고 그 헤더의 액션 자리는 어디인가.
 *
 * **두 필드를 하나로 합치지 마라.** `present` 를 `actionHost !== null` 로 유도하면 콜백 ref 가
 * 채워지기 전 첫 렌더에서 `present` 가 false 가 되고, 그 한 프레임 동안 페이지가 자기 `<h1>` 을
 * 그린다 — 문서에 h1 이 2개인 순간이 실제로 생긴다. 유닛 테스트는 첫 렌더만 보므로 **초록인 채로**
 * 통과한다. 짝 판별식 = `ProjectChrome.test.tsx` 의 「호스트가 null 이어도 present 는 true」.
 */
export interface ProjectChromeValue {
  /** 셸 헤더가 존재하는가 = 셸이 문서 `<h1>` 과 액션 자리를 소유하는가 */
  readonly present: boolean
  /** 헤더 우측 액션 영역의 DOM 노드. 콜백 ref 가 채우기 전에는 `null` */
  readonly actionHost: HTMLElement | null
}

/** 기본값 = 크롬 없음. 프로젝트 밖 화면(설정·관리·전역 이슈 목록)이 이 값을 받는다 */
const DEFAULT_VALUE: ProjectChromeValue = { present: false, actionHost: null }

/**
 * 🛑 **이 파일은 `.ts` 다** — 컴포넌트를 두지 않는다.
 *
 * `react-refresh/only-export-components` 는 컴포넌트를 내보내는 파일이 훅·상수를 함께
 * 내보내면 경고하고, pre-commit 의 `eslint --max-warnings 0` 이 그것을 **커밋 차단**으로
 * 승격시킨다. 그래서 컨텍스트·훅은 여기, 컴포넌트(`ProjectChromeProvider` ·
 * `ProjectHeaderActions`)는 `ProjectChrome.tsx` 로 갈라 둔다.
 */
export const ProjectChromeContext = createContext<ProjectChromeValue>(DEFAULT_VALUE)

/**
 * 셸 헤더가 문서 `<h1>` 을 소유하고 있는가.
 *
 * 전역·프로젝트 스코프 **양쪽**에서 쓰이는 화면(`issues.index`·`dashboards`)이 자기 제목행을
 * 그릴지 정하는 데 쓴다. `true` 면 셸이 프로젝트 이름을 h1 으로 이미 그렸으므로 페이지는
 * 제목을 다시 쓰지 않는다(J5-11).
 */
export function useProjectChromePresent(): boolean {
  return useContext(ProjectChromeContext).present
}

/** 헤더 액션 자리와 크롬 존재 여부를 함께 읽는다 — `ProjectHeaderActions` 전용 */
export function useProjectChrome(): ProjectChromeValue {
  return useContext(ProjectChromeContext)
}
