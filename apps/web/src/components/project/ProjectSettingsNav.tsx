// 프로젝트 설정 서브앱 사이드바 — sticky 복귀 링크 + PROJECT_SETTINGS_NAV 4그룹 10항목 (Jira 패리티 JS-1·JS-2)
import { useId, type JSX } from 'react'
import { Link } from '@tanstack/react-router'
import { ArrowLeft } from 'lucide-react'
import { useProject } from '@/hooks/use-project'
import { useSidebarRailCollapsed } from '@/hooks/use-sidebar-drawer'
import {
  PROJECT_SETTINGS_NAV,
  type ProjectSettingsNavGroup,
} from '@/components/project/project-shell-mode'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이 사이드바의 접근성 이름.
 *
 * 🛑 `navLabels` 에 **넣지 않는다.** `i18n/__tests__/nav-labels.test.ts` FR15 가 그 레지스트리의
 * 모든 값 쌍에 대해 substring 관계 0 과 값 중복 0 을 단언하는데, 설정 사이드바의 자연스러운
 * 이름(`프로젝트 설정`)은 `navLabels.projectNav`(`프로젝트`)를 통째로 품어 **red 다.**
 * `i18n/project-view-labels.ts` 와 `ProjectReportsNav.REPORTS_NAV_LABEL` 이 **같은 이유로**
 * 갈라져 나온 선례다 — 면제를 적어 정본 레지스트리를 오염시키는 대신 판별식 사정권 밖에
 * 자리를 따로 둔다.
 *
 * 값 선정. `프로젝트` 를 substring 으로 **품지 않는다** — 품으면
 * `getByRole('navigation', { name: '프로젝트' })` 가 사이드바 트리와 이것을 함께 잡아
 * Playwright strict mode 로 죽는다. 기존 nav 이름(`메인 메뉴`·`관리 메뉴`·`프로젝트`·
 * `프로젝트 뷰 전환`·`탐색 경로`·`리포트 전환`·`워크플로우 스킴 목록`) 어느 쪽과도
 * substring 관계가 아니다(2026-09-08 실측).
 */
const SETTINGS_NAV_LABEL = '설정 메뉴'

/** 프로젝트 기본 착지 라우트 — 복귀 링크의 목적지(`ProjectTree.PROJECT_SUMMARY_PATH` 와 같은 곳) */
const PROJECT_HOME_PATH = '/projects/$projectKey'

/** 아이콘 공통 스타일 — `Sidebar.NAV_ICON_CLASS` 관례 재사용 */
const NAV_ICON_CLASS = 'size-4 shrink-0'

/**
 * 복귀 링크를 감싸는 상자 — **`sticky top-0`** 이 요점이다 (★리뷰 D-2).
 *
 * 10항목 + 그룹 헤딩 4개면 짧은 뷰포트에서 복귀 링크가 화면 밖으로 나간다. 「내가 어디 있나」와
 * 「어떻게 돌아가나」는 항상 보여야 한다. 스크롤 컨테이너는 `Sidebar` 의
 * `aside`(`overflow-y-auto`)이므로 `top-0` 은 사이드바 상단에 고정된다.
 * `bg-sidebar` 가 없으면 아래로 지나가는 항목이 링크 뒤로 비쳐 둘 다 못 읽는다.
 */
const BACK_LINK_WRAPPER_CLASS = 'sticky top-0 z-10 bg-sidebar pb-1'

/** 복귀 링크 스타일 — 설정 항목보다 한 단계 강한 대비로 「나가는 문」임을 드러낸다 */
const BACK_LINK_CLASS =
  'flex items-center gap-2 rounded-md px-2 py-1.5 text-sm font-semibold ' +
  'text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'

/**
 * 그룹 헤딩 스타일.
 *
 * `--sidebar-*` 팔레트에 그룹 헤딩 대응 토큰이 없다. **새 CSS 변수를 신설하지 않고**
 * `text-sidebar-foreground/<알파>` 관례를 쓴다(★리뷰 D-6).
 *
 * 알파는 `/70` 이다 — D-6 이 지정한 `/60` 에서 한 단계 올렸다. 알파 조정은 새 CSS 변수가
 * 아니라 같은 토큰의 다른 불투명도이므로 D-6 의 「새 토큰 금지」와 충돌하지 않는다.
 *
 * 근거는 실측 대비다(2026-09-08 · Chromium 캔버스 합성으로 실제 픽셀을 읽어 WCAG 계산).
 *
 * | 알파 | 라이트 | 다크 |
 * |---|---|---|
 * | `/50` (기존 트리 헤딩 관례) | 2.96:1 ❌ | 3.80:1 ❌ |
 * | `/60` (D-6 최초 지정) | **3.91:1 ❌** | 4.80:1 ✅ |
 * | **`/70` (채택)** | **5.30:1 ✅** | **6.14:1 ✅** |
 *
 * 🛑 `/60` 으로 되돌리지 마라 — 라이트가 AA(4.5:1) 미달이다. 「기존 트리 헤딩보다는 낫다」는
 * **「더 나쁜 것이 있다」이지 「통과했다」가 아니다.** 12px 이라 large text 완화도 못 받는다.
 */
const GROUP_HEADING_CLASS =
  'px-2 pb-0.5 pt-2 text-xs font-semibold uppercase tracking-wide text-sidebar-foreground/70'

/** 설정 항목 링크 스타일 — `Sidebar.NAV_LINK_CLASS` 와 같은 시각 언어(`[&.active]` 강조 포함) */
const SETTINGS_LINK_CLASS =
  'flex items-center gap-2 rounded-md px-2 py-1.5 text-sm ' +
  'text-sidebar-foreground/80 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground ' +
  '[&.active]:bg-sidebar-accent [&.active]:text-sidebar-accent-foreground [&.active]:font-semibold'

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** {@link ProjectSettingsNavGroupSection} Props */
export interface ProjectSettingsNavGroupSectionProps {
  /** 그릴 그룹 1건 */
  readonly group: ProjectSettingsNavGroup
  /** 현재 프로젝트 키 — 그룹 안 모든 링크가 `$projectKey` 를 받는다 */
  readonly projectKey: string
  /** 접힘 레일(64px) 여부 — 참이면 라벨을 `sr-only` 로 내리고 아이콘만 남긴다 */
  readonly collapsed: boolean
}

/**
 * 설정 메뉴 그룹 한 구간 — 헤딩 + 항목 목록.
 *
 * **항목이 0개면 헤딩까지 통째로 사라진다** (★리뷰 D-5 · `PROJECT_SETTINGS_NAV` KDoc 이 소비자
 * 몫으로 명시한 계약). 지금은 권한 게이팅이 없어 항상 10항목이지만 설정은 게이팅이 붙는
 * 표면이고(스펙 GAP-1), 그날 빈 헤딩만 남은 사이드바는 「권한이 없다」가 아니라 **「고장났다」**로
 * 읽힌다.
 *
 * 🛑 `export` 는 테스트 편의가 아니라 **그 계약을 직접 재게 하기 위해서**다 — 정본
 * `PROJECT_SETTINGS_NAV` 가 항상 10항목이라 부모만 렌더해서는 「빈 그룹」에 도달할 수 없고,
 * 도달 불가 상태를 지키는 단언은 가짜 그린이다(`unreachable-state-fixture-is-fake-green`).
 *
 * @param props 그룹 · 프로젝트 키 · 접힘 여부
 * @returns 항목이 있으면 헤딩+목록, 0개면 `null`
 */
export function ProjectSettingsNavGroupSection({
  group,
  projectKey,
  collapsed,
}: ProjectSettingsNavGroupSectionProps): JSX.Element | null {
  // 🛑 훅은 조기 반환보다 **위**에 있어야 한다 — 빈 그룹에서 건너뛰면 렌더 간 훅 순서가
  //    어긋난다(`ProjectTree.ProjectTreeGroup` 선례).
  const headingId = useId()

  if (group.items.length === 0) return null

  return (
    <>
      {/* 접힘 레일에서는 헤딩 텍스트가 잘려 읽히므로 시각적으로만 숨긴다 — DOM 에는 남아
          구간 경계가 스크린리더에 계속 들린다. `aria-labelledby` 로 아래 목록과 **묶어야**
          그 약속이 실제로 지켜진다(트리 그룹 헤딩과 같은 관례). */}
      <p id={headingId} className={collapsed ? 'sr-only' : GROUP_HEADING_CLASS}>
        {group.label}
      </p>
      <ul aria-labelledby={headingId} className="flex flex-col gap-0.5">
        {group.items.map(({ to, label, Icon }) => (
          <li key={to}>
            <Link
              to={to}
              params={{ projectKey }}
              className={collapsed ? `${SETTINGS_LINK_CLASS} justify-center` : SETTINGS_LINK_CLASS}
            >
              <Icon aria-hidden="true" className={NAV_ICON_CLASS} />
              <span className={collapsed ? 'sr-only' : 'truncate'}>{label}</span>
            </Link>
          </li>
        ))}
      </ul>
    </>
  )
}

/** {@link ProjectSettingsNav} Props */
export interface ProjectSettingsNavProps {
  /** 현재 프로젝트 키 — 복귀 링크와 10항목 전부가 이 값을 받는다 */
  readonly projectKey: string
}

/**
 * 프로젝트 설정 서브앱의 사이드바. 설정 경로에서 `ProjectTree` **대신** 선다 (JS-2).
 *
 * ### 왜 트리를 대체하는가
 * Jira 는 스페이스 설정 안에서 **설정 자체의 사이드바**로 페이지를 고른다("…then selecting
 * **Space settings** and choosing **Permissions from the sidebar**" · JS-2). 설정은 화면이
 * 여럿인 독립 서브앱이라 트리 밑 중첩 그룹으로 매달면 스캔이 되지 않는다.
 *
 * ### 그룹 편성은 Jira 근거가 아니다 (편차 X-N5)
 * Jira 문서는 스페이스 설정 사이드바의 **그룹 이름·묶음**을 열거하지 않는다. 4그룹(일반·이슈·
 * 액세스·연동)은 이 저장소가 정한 것이고 정본은 {@link PROJECT_SETTINGS_NAV} 다.
 *
 * ### 아이콘이 필수인 이유 (★리뷰 D-1)
 * 트리의 `sr-only` 관례는 **첫 글자 아바타라는 시각 앵커가 있어서** 성립한다. 설정 10항목에
 * 앵커가 없으면 64px 레일에서 **구분 불가능한 빈 행 10개**가 된다. 그래서 정본이 항목마다
 * `Icon: LucideIcon` 을 들고 있고(`lib/settings-hub-links.ts` 와 같은 패턴), 라벨은 DOM 에
 * 남긴 채 `sr-only` 로만 내려 `getByRole('link', { name })` 계약을 지킨다.
 *
 * 🛑 **`<h1>` 을 두지 않는다.** 프로젝트 하위 화면의 `<h1>` 은 `ProjectViewHeader` 단독 소유이고
 * (즉사 계약 C-3 · e2e `<h1>` 단독 34건), 그룹 헤딩도 heading role 을 쓰지 않는다 —
 * 설정 본문이 `<h2>` 를 쓰므로 사이드바가 heading 을 내면 본문 헤딩 조회가 함께 잡힌다.
 *
 * @param projectKey 현재 프로젝트 키
 */
export function ProjectSettingsNav({ projectKey }: ProjectSettingsNavProps): JSX.Element {
  // 🛑 `collapsed` 직접 읽기 금지 — 모바일 드로어는 264px 로 열리므로 레일이 아니다(트리와 동일).
  const collapsed = useSidebarRailCollapsed()
  // `ProjectViewHeader` 가 같은 화면에서 이미 같은 queryKey 를 조회한다 — TanStack 이 중복
  // 제거하므로 추가 요청이 아니다. 응답 전에는 키로 폴백해 복귀 링크가 **빈 채로 뜨지 않는다**.
  const { data: project } = useProject(projectKey)
  const projectName = project?.name ?? projectKey

  return (
    <nav aria-label={SETTINGS_NAV_LABEL} className="flex flex-col gap-1 p-2">
      <div className={BACK_LINK_WRAPPER_CLASS}>
        <Link to={PROJECT_HOME_PATH} params={{ projectKey }} className={BACK_LINK_CLASS}>
          <ArrowLeft aria-hidden="true" className={NAV_ICON_CLASS} />
          <span className={collapsed ? 'sr-only' : 'truncate'}>{projectName}</span>
        </Link>
      </div>
      {PROJECT_SETTINGS_NAV.map((group) => (
        <ProjectSettingsNavGroupSection
          key={group.key}
          group={group}
          projectKey={projectKey}
          collapsed={collapsed}
        />
      ))}
    </nav>
  )
}
