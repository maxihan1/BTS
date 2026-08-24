// 설정 허브의 모든 하위 페이지가 모바일 폭에서도 UI 경로로 도달 가능한지 지키는 판별식 (F24)
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { SETTINGS_HUB_LINKS } from '@/lib/settings-hub-links'

const SRC = resolve(__dirname, '../../..')

function read(rel: string): string {
  return readFileSync(resolve(SRC, rel), 'utf-8')
}

/**
 * 주석을 걷어낸 소스.
 *
 * 🛑 소스 텍스트 가드는 **주석에 적힌 예시**를 코드로 오인한다. 실측 — 이 파일의 백드롭 단언이
 *    `ShellLayout.tsx` 의 주석에 든 `md:hidden` 을 맞춰, 백드롭을 `lg:hidden` 으로 바꿔도
 *    초록이었다(뮤테이션 2건 모두 통과). 저장소가 반복해서 밟은 양식
 *    (`invariant-satisfied-by-helptext-not-logic`)이라 여기서 한 번에 끊는다.
 */
function withoutComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '') // 블록 주석 · JSX 주석
    .split('\n')
    .filter((line) => !/^\s*(\/\/|\*)/.test(line)) // 줄 주석
    .join('\n')
}

/**
 * 설정 경로 목적지를 모두 뽑는다.
 *
 * 🛑 `to="…"` 한 가지만 보면 안 된다. JSX 는 `to={'…'}` · `to={"…"}` 도 같은 뜻이고,
 * 이 저장소는 쌍따옴표 import(`components/ui/tabs.tsx`)를 실제로 쓰므로 따옴표 종류를
 * 가정할 수 없다. 표기 하나만 보면 리팩터 한 번으로 이 가드가 조용히 눈이 먼다.
 *
 * 허브 목록 쪽은 정규식이 아니라 [SETTINGS_HUB_LINKS] **정본을 직접 import** 하므로,
 * 두 목록 중 중요한 쪽은 소스 파싱에 기대지 않는다.
 *
 * ★남는 사각 — `to={SOME_CONST}` 처럼 상수로 뺀 형태는 못 잡는다. 그때는 아래
 *  「계정 메뉴에서 설정 링크를 하나라도 찾았다」 단언이 먼저 red 가 되어 눈이 먼 것을 알린다.
 */
function settingsLinksIn(source: string): Set<string> {
  const found = new Set<string>()
  for (const m of withoutComments(source).matchAll(/to=\{?['"](\/settings[^'"]*)['"]\}?/g)) {
    const dest = m[1]
    if (dest !== undefined) found.add(dest)
  }
  return found
}

/** `/settings` **루트** 링크 한 개를 가리키는 표기. `.test`/`.search` 에 쓰므로 `g` 를 붙이지 않는다 */
const SETTINGS_ROOT_LINK = /to=\{?['"]\/settings['"]\}?/

/**
 * [needle] 을 품은 JSX **여는 태그 전체**를 돌려준다. 없으면 undefined.
 *
 * 🛑 줄 단위로 찾으면 안 된다 — 실측. `TopBar.tsx` 의 그 줄은 106자이고 `apps/web/.prettierrc`
 *    의 `printWidth` 는 100 이라 prettier 를 **한 번만 돌려도** 속성이 여러 줄로 갈라진다.
 *    그러면 `to="/settings"` 가 든 줄에 `className` 이 없어 「감춰져 있지 않다」로 뒤집히고,
 *    아래 도달성 단언 2개가 통째로 공허하게 초록이 된다(리뷰 C4). 포매터 한 번에 눈머는
 *    가드는 가드가 아니다.
 *
 * 속성값의 따옴표와 중괄호 깊이를 세므로 `onClick={() => …}` 의 `>` 를 태그 끝으로 오인하지 않는다
 * (화살표는 항상 중괄호 안이라 깊이가 0 이 아니다).
 */
function jsxOpeningTagContaining(source: string, needle: RegExp): string | undefined {
  const clean = withoutComments(source)
  const hit = clean.search(needle)
  if (hit < 0) return undefined
  const start = clean.lastIndexOf('<', hit)
  if (start < 0) return undefined

  let depth = 0
  let quote: string | undefined
  for (let i = start; i < clean.length; i += 1) {
    const c = clean[i]
    if (quote !== undefined) {
      if (c === quote) quote = undefined
    } else if (c === '"' || c === "'" || c === '`') {
      quote = c
    } else if (c === '{') {
      depth += 1
    } else if (c === '}') {
      depth -= 1
    } else if (c === '>' && depth === 0) {
      return clean.slice(start, i + 1)
    }
  }
  return undefined
}

/** 상단바의 `/settings` 링크가 좁은 폭에서 감춰져 있는지 — `max-md:hidden` 은 display:none 이다 */
function topBarGearHiddenOnMobile(): boolean {
  const tag = jsxOpeningTagContaining(read('components/layout/TopBar.tsx'), SETTINGS_ROOT_LINK)
  if (tag === undefined) return true // 링크 자체가 없으면 모바일에서도 없는 것과 같다
  return tag.includes('max-md:hidden')
}

describe('설정 도달성 — 허브의 모든 항목이 모바일에서 UI 경로로 닿는다', () => {
  it('상단바 톱니가 좁은 폭에서 감춰져 있다면 계정 메뉴가 `/settings` 허브 진입을 제공한다', () => {
    // 🛑 이 판별식이 존재하는 이유(실측). `/settings` **루트**로 가는 UI 링크는 한때
    //    `TopBar.tsx` 하나뿐이었고, 거기에 `max-md:hidden` 을 걸자 허브에만 있던
    //    `password`·`sessions`·`notifications`·`account-links` 4개가 모바일에서 통째로
    //    도달 불가가 됐다. 비밀번호 변경과 세션 종료는 보안 기능이다.
    //    사이드바는 대체가 못 된다 — 관리 메뉴는 `isSystemAdmin` 전용이다.
    if (!topBarGearHiddenOnMobile()) return // 톱니가 모바일에서도 보이면 이 조건은 무의미하다

    const accountMenuLinks = settingsLinksIn(read('components/layout/AccountMenu.tsx'))
    expect(
      accountMenuLinks.has('/settings'),
      '상단바 설정 톱니가 `max-md:hidden` 인데 계정 메뉴에 `/settings` 허브 링크가 없다. ' +
        '이 조합에서는 허브에만 있는 항목이 모바일에서 도달 불가가 된다 — ' +
        '계정 메뉴에 「모든 설정」 항목을 두거나 톱니의 `max-md:hidden` 을 없애라.',
    ).toBe(true)
  })

  it('허브 항목 전량이 모바일 도달 가능 집합에 들어간다 (차집합 0)', () => {
    const hubDestinations = SETTINGS_HUB_LINKS.map((l) => l.to)
    const accountMenuLinks = settingsLinksIn(read('components/layout/AccountMenu.tsx'))

    // 모바일에서 닿는 설정 경로 집합.
    // ① 톱니가 보이면 허브를 거쳐 전량에 닿는다
    // ② 계정 메뉴가 `/settings` 를 주면 역시 허브를 거쳐 전량에 닿는다
    // ③ 그 밖에는 계정 메뉴가 **직접 링크한 것만** 닿는다
    const reachesHub = !topBarGearHiddenOnMobile() || accountMenuLinks.has('/settings')
    const reachable = new Set<string>(reachesHub ? hubDestinations : [])
    for (const link of accountMenuLinks) reachable.add(link)

    const unreachable = hubDestinations.filter((to) => !reachable.has(to))
    expect(
      unreachable,
      `설정 허브 항목 ${unreachable.length}개가 모바일에서 도달 불가다:\n` +
        `${unreachable.join('\n')}\n` +
        '허브 목록은 `@/lib/settings-hub-links` 의 SETTINGS_HUB_LINKS 가 정본이다 ' +
        '(`routes/settings.index.tsx` 는 그것을 import 하는 소비처다).',
    ).toEqual([])
  })

  it('허브 목록이 비어 있지 않다 (판별식이 공허하게 통과하는 것을 막는다)', () => {
    // 허브 목록이 0건이면 위 차집합은 항상 빈 배열이라 무엇을 지워도 초록이 된다.
    expect(SETTINGS_HUB_LINKS.length).toBeGreaterThan(0)
  })

  it('계정 메뉴 파싱이 설정 링크를 실제로 찾는다 (파서가 눈머는 것을 막는다)', () => {
    // ★정규식이 표기를 못 따라가면 `settingsLinksIn` 이 조용히 빈 집합을 내고, 그러면 위
    //   두 단언이 「톱니가 보인다」 분기로 빠지거나 차집합을 잘못 계산한다. 파서가 살아 있는지
    //   여기서 먼저 잰다 — 링크를 상수로 빼는 리팩터가 오면 이 단언이 먼저 red 가 된다.
    const links = settingsLinksIn(read('components/layout/AccountMenu.tsx'))
    expect(links.size).toBeGreaterThan(0)
    expect(links.has('/settings')).toBe(true)
  })

  it('톱니 판정이 prettier 리플로우에 눈멀지 않는다 (줄 단위 파싱 금지)', () => {
    // ★이 판별식이 존재하는 이유(실측). `topBarGearHiddenOnMobile` 은 한때 `to="/settings"` 가
    //   **든 줄 하나**만 보고 그 줄에 `max-md:hidden` 이 있는지 물었다. 그런데 `TopBar.tsx` 의
    //   그 줄은 106자이고 `.prettierrc` 의 printWidth 는 100 이다 — 포매터를 한 번 돌리면
    //   속성이 갈라져 판정이 false 로 뒤집히고, 위 도달성 단언 2개가 통째로 공허해졌다.
    //   백스톱이 우연이면 백스톱이 아니다. 갈라진 형태를 여기 못박는다.
    const reflowed = [
      '      <Link',
      '        to="/settings"',
      '        className="rounded-md p-1.5 hover:bg-accent max-md:hidden"',
      '        aria-label="설정"',
      '      >',
    ].join('\n')

    const tag = jsxOpeningTagContaining(reflowed, SETTINGS_ROOT_LINK)
    expect(tag, '여러 줄로 갈라진 `<Link>` 여는 태그를 찾지 못했다 — 파서가 줄 단위로 돌아갔다.').toBeDefined()
    expect(
      tag?.includes('max-md:hidden'),
      '갈라진 태그에서 `max-md:hidden` 을 놓쳤다. 이 상태에서는 톱니가 모바일에 보인다고 ' +
        '오판해 도달성 단언 2개가 조용히 통과한다.',
    ).toBe(true)
  })

  it('상단바 톱니 태그를 실제로 찾는다 (파서 비-공허)', () => {
    // 갈라진 합성 소스만 재면 「실제 파일에서는 못 찾는다」를 놓친다. 실물도 함께 잰다.
    const tag = jsxOpeningTagContaining(read('components/layout/TopBar.tsx'), SETTINGS_ROOT_LINK)
    expect(
      tag,
      '`TopBar.tsx` 에서 `/settings` 링크 태그를 찾지 못했다. 톱니를 없앴다면 이 파일의 ' +
        '도달성 계약을 다시 읽어라 — 계정 메뉴가 유일한 허브 진입로가 된다.',
    ).toBeDefined()
  })
})
