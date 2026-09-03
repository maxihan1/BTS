// 프로젝트 뷰 전환 nav — board/backlog 인라인 nav를 추출한 공유 컴포넌트 (FR-UX-06 PR12 Task 4)
import type { JSX } from 'react'
import { Link } from '@tanstack/react-router'
import { navLabels } from '@/i18n/nav-labels'

/** 뷰 전환 nav 링크 공통 클래스 — board/backlog 인라인 nav 원본과 동일(추출 전후 시각 불변) */
const PROJECT_NAV_TAB_LINK_CLASS = 'text-sm text-muted-foreground hover:text-foreground'

/**
 * {@link ProjectNavTabs} 링크 항목.
 *
 * `to`는 TanStack Router 라우트 경로 문자열(`$projectKey` 플레이스홀더 포함),
 * `label`은 화면에 노출되는 링크 텍스트 — 호출부의 i18n 라벨 상수(`boardLabels`·
 * `backlogLabels` 등)를 그대로 전달한다.
 */
export interface ProjectNavTabLink {
  /** 라우트 경로 (예: `/projects/$projectKey/board`) */
  readonly to: string
  /** 링크 텍스트 */
  readonly label: string
  /**
   * URL search 파라미터. 생략하면 쿼리 없이 이동한다(기존 소비처 회귀 0).
   *
   * 보드 스코프(`?board=`)를 뷰 전환에서 잃지 않기 위한 것이다 (FR-BD-04 PR ⑥).
   * 없으면 보드↔백로그 왕복마다 스코프가 풀려 사용자가 매번 스위처를 다시 눌러야 한다.
   *
   * **옵셔널인 것이 계약이다** — 이 nav 는 board·backlog 말고도 여러 화면이 쓰고,
   * 그 화면들은 실을 스코프가 없다. 편차 X7 의 「공유 상태」는 여전히 범위 밖이고
   * 이 필드는 **링크 전파**까지만 한다(호출부가 실을 값을 스스로 정한다).
   */
  readonly search?: Readonly<Record<string, string>>
}

/** {@link ProjectNavTabs} Props */
export interface ProjectNavTabsProps {
  /** 현재 프로젝트 키 — 모든 링크의 `$projectKey` param에 공통 적용 */
  readonly projectKey: string
  /** 렌더할 뷰 전환 링크 목록(순서대로 렌더) */
  readonly links: ReadonlyArray<ProjectNavTabLink>
}

/**
 * 프로젝트 뷰 전환 nav — board/backlog 등 프로젝트 하위 페이지가 각자 링크 집합을
 * 전달해 재사용하는 공유 컴포넌트다.
 *
 * 🔴 **Radix Tabs 금지** — `role="navigation"`(nav+Link)이 e2e 5건 + 유닛 5건의 계약이다.
 * 뷰 전환은 실제 라우트 이동(URL 변경, 뒤로가기 정상 동작)이므로 같은 라우트 안에서
 * 패널만 바뀌는 탭 위젯이 아니라 네비게이션이 정답이다
 * (`frontend-nav-aria-label-e2e-contract` 학습 노트).
 *
 * `aria-label`은 `navLabels.projectViewNav`(🔒 e2e 계약 문자열 "프로젝트 뷰 전환")를
 * 그대로 사용한다 — 글자를 바꾸면 안 된다.
 *
 * 이 컴포넌트는 **링크 집합을 통합하지 않는다** — board·backlog가 각자 기존 링크
 * 목록을 verbatim으로 전달하며, 여기서는 그대로 순서대로 렌더만 한다(회귀-무해).
 *
 * @param projectKey 현재 프로젝트 키
 * @param links 뷰 전환 링크 목록(호출부가 뷰 집합을 결정)
 */
export function ProjectNavTabs({ projectKey, links }: ProjectNavTabsProps): JSX.Element {
  return (
    <nav aria-label={navLabels.projectViewNav} className="flex items-center gap-3">
      {links.map((link) => (
        <Link
          key={link.to}
          to={link.to}
          params={{ projectKey }}
          search={link.search}
          className={PROJECT_NAV_TAB_LINK_CLASS}
        >
          {link.label}
        </Link>
      ))}
    </nav>
  )
}
