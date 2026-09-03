// 범주색(이슈타입·상태칩·진행바·즐겨찾기)이 Tailwind 리터럴 대신 토큰을 소비하는지 전수 스캔하는 회귀 가드 (FR-UX-06 PR22)
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

/**
 * PR4가 `--chart-*` 동결 때문에 이연했던 범주색 보유 파일 4종.
 * 실측(전체 Tailwind 팔레트 패턴 grep) 결과 프로덕션 리터럴은 이 4파일에만 남아 있다.
 * `components/workflow/WorkflowDiagram.tsx`는 리터럴이 **주석 문자열**이고 mermaid가 var()를
 * 지원하지 않아 PR4가 이미 OUT으로 판정했으므로 목록에서 제외한다.
 *
 * ★ **이 배열은 명시 허용목록이다 — 여기 없는 파일은 스캔되지 않는다.**
 * 범주색(카테고리별로 색이 갈리는 것)을 새로 그리는 파일을 만들면 **같은 커밋에서** 여기 등재하고,
 * 일부러 리터럴을 하나 넣어 red 를 1회 본 뒤 되돌려라. 등재만 하고 발화를 안 보면
 * 「목록에 있는데 안 잡는」 상태를 물려받는다 — 읽지 않는 열은 썩는다.
 * `workflow/editor/StatusNode.tsx`(FR-WF-07 D8)가 그 절차로 들어온 행이다.
 */
const CATEGORY_SOURCES = [
  'timeline/TimelineRow.tsx',
  'issue/EpicProgressBar.tsx',
  'favorite/FavoriteButton.tsx',
  'workflow/editor/StatusNode.tsx',
] as const

/** features/ 아래라 components/ 상대경로로 못 잡는 파일 */
const FEATURE_SOURCES = ['calendar/WeekGrid.tsx'] as const

/** Tailwind 팔레트 리터럴 색 (bg-slate-600 / text-yellow-400 / fill-emerald-500 ...) */
const TAILWIND_LITERAL_COLOR =
  /\b(bg|text|border|fill|stroke|ring|from|to|via)-(slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-\d{2,3}\b/g

/**
 * 주석을 제거한다 — `state-tokens.test.ts`의 stripComments와 같은 이유다.
 * 이관 이력을 설명하는 주석("bg-purple-500 → bg-type-epic")까지 위반으로 잡으면
 * 문서화가 벌점이 되어, 결국 주석을 지우게 만드는 잘못된 유인이 생긴다.
 * WorkflowDiagram이 주석 리터럴이라 OUT인 것과 같은 판정 기준.
 */
function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
}

function componentSource(relativePath: string): string {
  return stripComments(readFileSync(resolve(import.meta.dirname, '..', relativePath), 'utf-8'))
}

function featureSource(relativePath: string): string {
  return stripComments(readFileSync(resolve(import.meta.dirname, '../../features', relativePath), 'utf-8'))
}

describe('FR-UX-06 PR22 — 범주색은 토큰으로만 지정한다', () => {
  it.each(CATEGORY_SOURCES)('%s — Tailwind 리터럴 색 0건', (path) => {
    expect(componentSource(path).match(TAILWIND_LITERAL_COLOR) ?? []).toEqual([])
  })

  it.each(FEATURE_SOURCES)('features/%s — Tailwind 리터럴 색 0건', (path) => {
    expect(featureSource(path).match(TAILWIND_LITERAL_COLOR) ?? []).toEqual([])
  })

  it('TimelineRow 이슈타입 막대가 --type-* 토큰 유틸리티를 쓴다', () => {
    const source = componentSource('timeline/TimelineRow.tsx')
    for (const utility of ['bg-type-epic', 'bg-type-story', 'bg-type-task', 'bg-type-bug', 'bg-type-default']) {
      expect(source).toContain(utility)
    }
  })

  it('WeekGrid 상태 칩이 기존 상태 토큰 + --neutral-bold를 쓴다', () => {
    const source = featureSource('calendar/WeekGrid.tsx')
    expect(source).toContain('neutral-bold')
    expect(source).toContain('bg-info')
    expect(source).toContain('bg-success')
  })

  it('WeekGrid Worklog 칩이 --discovery 토큰을 쓴다', () => {
    // `discovery` 단어만 찾으면 주석/식별자에 걸려 공허하게 통과한다 — 유틸리티 클래스 형태로 못박는다.
    expect(featureSource('calendar/WeekGrid.tsx')).toContain('bg-discovery')
  })

  it('EpicProgressBar 진행 세그먼트가 상태 토큰을 쓴다', () => {
    const source = componentSource('issue/EpicProgressBar.tsx')
    expect(source).toContain('bg-success')
    expect(source).toContain('bg-info')
  })

  it('FavoriteButton 별이 --favorite 토큰을 쓴다', () => {
    // 파일명·컴포넌트명·data-testid에 이미 `favorite`가 들어 있어 단어 검색은 공허하게 통과한다.
    // 색 유틸리티 형태(`text-favorite`)로 못박아야 판별력이 생긴다.
    expect(componentSource('favorite/FavoriteButton.tsx')).toContain('text-favorite')
  })

  it('bold 배경 칩은 text-white 하드코딩 대신 *-foreground 짝을 쓴다', () => {
    // PR4 정본 페어링 규칙. text-white가 남아 있으면 다크 모드에서 밝은 칩 위 흰 글자가 되어 대비가 무너진다.
    expect(featureSource('calendar/WeekGrid.tsx')).not.toContain('text-white')
  })
})
