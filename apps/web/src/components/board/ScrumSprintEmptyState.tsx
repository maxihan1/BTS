// 스크럼 보드의 빈 상태 2종 — 활성 스프린트 없음 / 활성 스프린트에 이슈 없음 (FR-BD-04 FR-1 · E1·E2)
/* eslint-disable react-refresh/only-export-components -- resolveScrumEmptyVariant/scrumEmptyStateLabels 는 판정과 문구를 그 문구가 쓰이는 자리 옆에서 재기 위한 테스트용 named export (KanbanBoard·CfdChart 선례) */
import type { JSX } from 'react'
import { Link } from '@tanstack/react-router'

import type { BoardDetail } from '@/api/boards'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'

/**
 * 스크럼 보드 빈 상태의 종류.
 *
 * 둘을 한 문구로 뭉개지 않는 이유는 **사용자가 할 일이 다르기 때문**이다 —
 * `no-active-sprint` 는 백로그에서 스프린트를 **시작**해야 하고, `empty-sprint` 는 이미 시작한
 * 스프린트에 이슈를 **넣어야** 한다. 같은 문장을 쓰면 후자에서 「스프린트를 또 시작하라」는
 * 지시가 된다 (plan Sanity Check 2).
 */
export type ScrumEmptyVariant = 'no-active-sprint' | 'empty-sprint'

/**
 * 스크럼 보드 빈 상태 문구.
 *
 * `i18n/board-labels.ts` 가 아니라 여기 두는 것은 이 화면 조각 전용 문자열이고 소비자가
 * 이 컴포넌트 하나뿐이기 때문이다(`project/ProjectDangerZone.tsx` 의 `dangerZoneLabels` 선례).
 * 두 번째 소비자가 생기면 그때 `boardLabels` 로 올린다.
 */
export const scrumEmptyStateLabels = {
  /** 활성 스프린트가 아직 없는 스크럼 보드 (E1) */
  noActiveSprint: {
    /** 무엇이 없는지 — 「보드가 고장났다」로 읽히지 않게 원인을 먼저 적는다 */
    title: '활성 스프린트가 없습니다',
    /** 다음 행동. 스프린트를 시작하는 자리는 보드가 아니라 백로그다 (J18) */
    description: '백로그에서 스프린트를 시작하세요.',
  },
  /** 활성 스프린트는 있는데 그 스프린트에 이슈가 0건 (E2) */
  emptySprint: {
    /** 🛑 `noActiveSprint.title` 과 **다른 문장**이어야 한다 — 원인이 다르다 */
    title: '이 스프린트에 이슈가 없습니다',
    /** 다음 행동. 이슈를 스프린트로 옮기는 자리도 백로그다 */
    description: '백로그에서 이 스프린트로 이슈를 옮기세요.',
  },
  /** 두 상태가 공유하는 CTA — 어느 쪽이든 다음 행동은 백로그에서 일어난다 */
  backlogLink: '백로그로 이동',
} as const

/**
 * 보드 상세로 스크럼 빈 상태 종류를 판정한다. 빈 상태가 아니면 `null`.
 *
 * ### 근거
 * - **J5** — *"the board displays only the work items added to the sprint you started"*
 * - **J6** — 카드가 보드에 뜨는 조건에 *"is in an active sprint (for Scrum boards)"* 가 있고
 *   *"Active sprints are only available on Scrum boards."* 다. 그래서 **칸반은 이 함수를 통과해도
 *   항상 `null`** 이고, 칸반 화면에는 이 축이 존재하지 않는다 (E3).
 *
 * ### 판정 순서가 계약이다
 * 1. 칸반이면 즉시 `null`.
 * 2. 활성 스프린트가 없으면 `'no-active-sprint'` — **필터보다 앞선다.** 필터를 초기화해도
 *    카드가 생기지 않으므로 「필터 초기화」 CTA 를 내밀면 거짓 안내가 된다.
 * 3. 필터가 0건을 만든 것이면 `null` — 그 자리는 초기화 CTA 가 있는 `FilteredEmptyState` 소관이다.
 * 4. 컬럼이 0개면 `null`. 카드가 0건인 이유가 스프린트가 아니라 **보드 설정**이라서다
 *    (`isFilteredEmpty` 가 같은 자리에서 같은 판단을 한다).
 *
 * @param board 보드 상세. 로딩 중이면 `undefined`
 * @param isFilteredEmpty 필터가 걸린 채 카드가 0건인지 — 호출부가 이미 계산한 값
 * @returns 빈 상태 종류. 빈 상태가 아니면 `null`
 */
export function resolveScrumEmptyVariant(
  board: BoardDetail | undefined,
  isFilteredEmpty: boolean,
): ScrumEmptyVariant | null {
  if (board === undefined || board.boardType !== 'SCRUM') return null
  if (board.activeSprint === null) return 'no-active-sprint'
  if (isFilteredEmpty) return null
  if (board.columns.length === 0) return null
  return board.columns.every((col) => col.cards.length === 0) ? 'empty-sprint' : null
}

/** {@link ScrumSprintEmptyState} props */
export interface ScrumSprintEmptyStateProps {
  /** 빈 상태 종류 — {@link resolveScrumEmptyVariant} 의 반환값 */
  variant: ScrumEmptyVariant
  /** 백로그 링크에 실을 프로젝트 키 */
  projectKey: string
  /** 컨테이너 여백/최소높이 override — 보드 화면은 `FilteredEmptyState` 와 같은 `min-h-48` 을 준다 */
  className?: string
}

/**
 * 스크럼 보드에서 카드가 하나도 없을 때의 안내.
 *
 * `FilteredEmptyState` 와 같은 패턴이다 — `EmptyState` 프리미티브에 문구와 액션만 주입하고
 * 새 프리미티브를 만들지 않는다. 다른 점은 액션이 「필터 초기화」 버튼이 아니라 **백로그 링크**
 * 라는 것 하나뿐이다. 두 빈 상태 모두 다음 행동이 백로그에서 일어나기 때문이다 (J18).
 *
 * 🛑 이 컴포넌트는 KanbanBoard **자리만** 대체한다. 호출부가 이것으로 early-return 하면
 * 헤더·`⋯` 관리 메뉴·필터바가 함께 사라져 보드를 지울 수도 이름을 바꿀 수도 없게 된다.
 */
export function ScrumSprintEmptyState({
  variant,
  projectKey,
  className,
}: ScrumSprintEmptyStateProps): JSX.Element {
  const copy =
    variant === 'no-active-sprint'
      ? scrumEmptyStateLabels.noActiveSprint
      : scrumEmptyStateLabels.emptySprint

  return (
    <EmptyState
      title={copy.title}
      description={copy.description}
      className={className}
      action={
        // `FilteredEmptyState` 와 같은 `outline` 버튼 모양이다 — 같은 자리에 번갈아 뜨는 두 빈
        // 상태의 CTA 가 서로 다르게 보이면 「다른 화면으로 튀었다」로 읽힌다.
        //
        // `asChild` 로 안을 라우터 `Link` 로 바꾼다. 두 가지를 동시에 지키기 위해서다.
        // ① 역할은 **링크**다 — 실제 라우트 이동이라 새 탭·뒤로가기가 살아야 한다.
        // ② 다크에서 `text-primary`(#0C66E4) 평문 링크는 어두운 배경 대비가 4.5:1 에 못 미친다.
        //    outline 은 글자가 `--foreground` 라 양쪽 테마에서 대비가 확보된다.
        <Button asChild variant="outline" size="sm">
          <Link to="/projects/$projectKey/backlog" params={{ projectKey }}>
            {scrumEmptyStateLabels.backlogLink}
          </Link>
        </Button>
      }
    />
  )
}
