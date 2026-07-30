// 상단바 프로젝트 스위처 — 활성 프로젝트를 클릭 한 번으로 바꾼다 (FR-UX-08 PR-A Task 5, ADR D5)
import { useState, type JSX } from 'react'
import { useNavigate, useParams, useSearch } from '@tanstack/react-router'
import { ChevronsUpDown, Check } from 'lucide-react'
import { useProjects } from '@/hooks/use-projects'
import { useRecentProjects } from '@/hooks/use-recent-projects'
import { useResolvedActiveProject } from '@/hooks/use-resolved-active-project'
import { useActiveProject } from '@/hooks/use-active-project'
import { Popover, PopoverTrigger, PopoverContent } from '@/components/ui/popover'
import { Button } from '@/components/ui/button'
import {
  partitionProjectsForSwitcher,
  resolveSwitcherLanding,
} from './project-switcher-order'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 트리거 aria-label.
 *
 * ⚠️ `navLabels` 의 e2e 계약 문자열(`프로젝트`·`프로젝트 뷰 전환`·`메인 메뉴`·`관리 메뉴`·
 * `검색`·`탐색 경로`) 어느 것과도 substring 관계가 아니어야 한다. `프로젝트 선택` 은
 * `프로젝트` 를 **포함하지만**, 이 요소는 `role="button"` 이고 계약은 `role="navigation"`
 * 조회라 `getByRole('navigation', { name: '프로젝트' })` 와 충돌하지 않는다.
 */
const TRIGGER_LABEL = '프로젝트 선택'

/**
 * 트리거 이름 표시 폭 상한 (리뷰 CONCERN C3).
 *
 * `TopBar` 는 48px 고정 높이에 좌측 3요소(토글·로고·검색)가 붙어 있다. 프로젝트 이름은
 * 가변 길이라 상한이 없으면 긴 이름이 검색·만들기 버튼을 밀어낸다. **시각적으로만** 자르고
 * 접근가능 이름(`aria-label`)은 온전히 유지한다.
 */
const CURRENT_LABEL_CLASS = 'max-w-[180px] truncate'

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 상단바 프로젝트 스위처.
 *
 * FR-UX-07 이 "활성 프로젝트" 컨텍스트를 세웠지만 **그것을 손으로 바꿀 UI 가 없었다** —
 * 사용자는 URL 을 직접 고치거나 사이드바 트리에서 그 프로젝트의 보드로 들어가는 우회로만
 * 있었다. 이 컴포넌트가 그 구멍을 닫는다.
 *
 * **🛑 `<nav>` 로 만들지 않는다 (ADR §D5).** `navLabels.projectNav`(`'프로젝트'`)가
 * `projectViewNav`(`'프로젝트 뷰 전환'`)의 substring 이고 Playwright `getByRole` 은 기본이
 * substring 매칭이라, 또 하나의 `navigation` 랜드마크가 생기면 e2e 계약 18건이 흔들린다.
 * 목록 선택 시맨틱에는 `role="listbox"` / `role="option"` 이 정확하다
 * (`SenderAutocomplete`·`LabelAutocompleteInput`·`MentionDropdown` 등 5곳 선례).
 *
 * **착지점은 §1-B 3갈래다** — {@link resolveSwitcherLanding}.
 *
 * **목록 순서는 "최근 그룹 + 나머지"** — {@link partitionProjectsForSwitcher}.
 *
 * 프로젝트가 0개면 아무것도 렌더하지 않는다(S10) — 빈 상태 안내는 `ActiveProjectGate`
 * 소관이고, 고를 것이 없는 스위처는 죽은 버튼이다.
 */
export function ProjectSwitcher(): JSX.Element | null {
  const navigate = useNavigate()
  const { projectKey: pathProjectKey } = useParams({ strict: false }) as {
    projectKey?: string
  }
  const { projectKey: searchProjectKey } = useSearch({ strict: false }) as {
    projectKey?: string
  }
  const { data: projects } = useProjects()
  const recentProjectKeys = useRecentProjects((s) => s.recentProjectKeys)
  const setActiveProject = useActiveProject((s) => s.setActiveProject)
  const [open, setOpen] = useState(false)

  // 트리거 표시용 — 저장값·첫 프로젝트 폴백까지 포함한 "지금 무엇이 활성인가"가 필요하므로
  // 조합 훅을 쓴다(FR-UX-07 FR3-b — 조합 로직을 각자 짜면 드리프트가 확정된다).
  // ⚠️ 이 훅은 읽기 전용이 아니다(`:85-91`) — 해소 출처가 `url`·`first` 면 저장값을 갱신한다.
  // 스위처는 상단바에 있어 전 인증 페이지에서 도므로, 첫 방문자가 어느 페이지로 들어와도
  // 활성 프로젝트가 앵커된다. 의도된 확장이다 (스펙 FR11-b).
  const resolved = useResolvedActiveProject(searchProjectKey ?? pathProjectKey)
  const activeKey = resolved.status === 'ready' ? resolved.projectKey : undefined
  const activeProject = projects?.find((p) => p.key === activeKey)

  if (projects === undefined || projects.length === 0) return null

  const { recent, rest } = partitionProjectsForSwitcher(projects, recentProjectKeys)
  const ordered = [...recent, ...rest]

  const handleSelect = (key: string): void => {
    setOpen(false)
    switch (resolveSwitcherLanding(pathProjectKey, searchProjectKey)) {
      case 'path':
        // 같은 하위 경로를 유지한 채 프로젝트만 갈아끼운다 (/projects/A/board → /projects/B/board)
        void navigate({ to: '.', params: { projectKey: key }, replace: false })
        break
      case 'search':
        // ★ `...prev` 를 반드시 펼친다 — TanStack `search` 는 객체형이면 병합이 아니라
        // 치환이고 전 필드가 optional 이라 타입 체크로도 안 잡힌다. 안 펼치면 사용자의
        // 필터·정렬·`?selected=` 가 스위처 한 번에 전부 날아간다 (FR-UX-07 FR4-b).
        void navigate({
          to: '.',
          search: (prev: Record<string, unknown>) => ({ ...prev, projectKey: key }),
          replace: false,
        })
        break
      case 'none':
        // URL 이 프로젝트를 안 담는다 — 라우트가 해소 ②(저장값)로 스스로 갱신한다.
        // 원치 않는 화면 이동을 만들지 않는다.
        setActiveProject(key)
        break
    }
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="default"
          className="ml-1 gap-1 rounded-md px-2 hover:bg-accent"
          aria-label={`${TRIGGER_LABEL}${activeProject === undefined ? '' : ` — 현재 ${activeProject.name}`}`}
        >
          <span data-testid="project-switcher-current" className={CURRENT_LABEL_CLASS}>
            {activeProject?.name ?? TRIGGER_LABEL}
          </span>
          <ChevronsUpDown aria-hidden="true" className="size-3.5 shrink-0 opacity-60" />
        </Button>
      </PopoverTrigger>

      <PopoverContent align="start" className="w-64 p-1">
        <ul role="listbox" aria-label={TRIGGER_LABEL} className="flex flex-col gap-0.5">
          {ordered.map((project, index) => {
            const isActive = project.key === activeKey
            // 최근 그룹과 나머지의 경계에 구분선을 둔다 — 두 구간이 다른 의미임을 시각화
            const isFirstOfRest = recent.length > 0 && index === recent.length
            return (
              <li
                key={project.id}
                role="option"
                aria-selected={isActive}
                data-project-key={project.key}
                tabIndex={0}
                className={`flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm focus:bg-accent focus:outline-none hover:bg-accent ${
                  isFirstOfRest ? 'mt-1 border-t border-border pt-2' : ''
                }`}
                onClick={() => { handleSelect(project.key) }}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault()
                    handleSelect(project.key)
                  }
                }}
              >
                <Check
                  aria-hidden="true"
                  className={`size-3.5 shrink-0 ${isActive ? 'opacity-100' : 'opacity-0'}`}
                />
                <span className="truncate">{project.name}</span>
              </li>
            )
          })}
        </ul>
      </PopoverContent>
    </Popover>
  )
}
