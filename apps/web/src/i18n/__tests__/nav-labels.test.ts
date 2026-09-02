// 사이드바/상단바 nav 라벨 상수 단위 테스트 — e2e 계약 문자열 4종 고정 + S3 제외 항목 회귀 가드

import { describe, expect, it } from 'vitest'
import { navLabels } from '../nav-labels'

describe('navLabels', () => {
  describe('🔒 e2e 계약 문자열 (글자 변경 금지)', () => {
    it('mainNav은 정확히 "메인 메뉴"이다', () => {
      expect(navLabels.mainNav).toBe('메인 메뉴')
    })

    it('adminNav은 정확히 "관리 메뉴"이다', () => {
      expect(navLabels.adminNav).toBe('관리 메뉴')
    })

    it('projectViewNav은 정확히 "프로젝트 뷰 전환"이다', () => {
      expect(navLabels.projectViewNav).toBe('프로젝트 뷰 전환')
    })

    it('search는 정확히 "검색"이다', () => {
      expect(navLabels.search).toBe('검색')
    })

    it('F13 — globalSearch 는 상단바 입력창 전용 이름이고 search 와 다르다', () => {
      expect(navLabels.globalSearch).toBe('전역 검색')
      expect(navLabels.search).toBe('검색')
      expect(navLabels.globalSearch).not.toBe(navLabels.search)
    })

    // 아래 2건은 FR15-b 통합 과정에서 발견한 갭이다 — JSDoc이 🔒 e2e 계약으로 표시한
    // 6종 중 projectNav·breadcrumb만 값 고정 단언이 없었다(옛 `i18n/nav-labels.test.ts`는
    // breadcrumb 값만 보고 projectNav는 안 봤다).
    it('projectNav는 정확히 "프로젝트"이다 (FR-UX-06 PR12 FR1 계약)', () => {
      expect(navLabels.projectNav).toBe('프로젝트')
    })

    it('breadcrumb는 정확히 "탐색 경로"이다 (FR-UX-06 PR13 PL-3 계약, 흡수)', () => {
      expect(navLabels.breadcrumb).toBe('탐색 경로')
    })
  })

  describe('사이드바/상단바 라벨', () => {
    it('issues 라벨이 존재한다', () => {
      expect(navLabels.issues).toBeTruthy()
    })

    it('dashboards 라벨이 존재한다', () => {
      expect(navLabels.dashboards).toBeTruthy()
    })

    it('calendar 라벨이 존재한다', () => {
      expect(navLabels.calendar).toBeTruthy()
    })

    it('create 라벨이 존재한다', () => {
      expect(navLabels.create).toBeTruthy()
    })

    it('admin 라벨이 제거됐다 (J9 — 사이드바 관리 nav 이관으로 소비처 0)', () => {
      // ★부재를 단언한다. 그냥 지우기만 하면 누군가 「관리」 라벨을 다시 넣어도 아무도 안 본다 —
      //   그러면 `admin`('관리') ⊂ `adminNav`('관리 메뉴') 면제를 되살려야 하는데,
      //   면제 없이 넣으면 FR15 substring 판별식이 red 를 낸다(그건 잡힌다).
      //   여기서 잡는 것은 **면제까지 함께 되살아나는 경우**다.
      expect('admin' in navLabels).toBe(false)
    })

    it('collapseSidebar 라벨이 존재한다', () => {
      expect(navLabels.collapseSidebar).toBeTruthy()
    })

    it('expandSidebar 라벨이 존재한다', () => {
      expect(navLabels.expandSidebar).toBeTruthy()
    })
  })

  describe('S3 — 백킹 유무에 따른 항목 게이팅 (FR-UX-08 PR-B 에서 2건 반전)', () => {
    // ⚠️ 이 블록을 통째로 지우지 말 것 (FR14-b).
    // S3 원 규칙은 "백킹 라우트·기능이 없는 항목(내 작업·최근·필터)은 포함하지 않는다.
    // 각 항목은 해당 기능 FR 에서 추가한다" 였다. FR-UX-08 PR-B 가 앞의 **둘에만**
    // 실 라우트를 부여했으므로 2건은 존재 단언으로 반전하고 2건은 부재 단언을 유지한다.
    // 블록째 삭제하면 filters·projects 가 가드를 잃는다 — "봉인은 절반만 닫힌다" 양식.

    it('myWork 키가 존재하고 "내 작업"이다 (FR-UX-08 PR-B 에서 추가 — /issues?assignee= 백킹)', () => {
      expect(Object.keys(navLabels)).toContain('myWork')
      expect(navLabels.myWork).toBe('내 작업')
    })

    it('recent 키가 존재하고 "최근 항목"이다 (FR-UX-08 PR-B 에서 추가 — /issues/$key 백킹)', () => {
      expect(Object.keys(navLabels)).toContain('recent')
      expect(navLabels.recent).toBe('최근 항목')
    })

    it('filters 키가 없다 (백킹 라우트 없음 — 가드 유지)', () => {
      expect(Object.keys(navLabels)).not.toContain('filters')
    })

    it('projects 키가 없다 (백킹 라우트 없음 — 가드 유지)', () => {
      expect(Object.keys(navLabels)).not.toContain('projects')
    })
  })

  // ───────────────────────────────────────────────────────────────────────────
  // FR15 — 전수 판별식 (목록 제거형)
  //
  // 옛 `i18n/nav-labels.test.ts`(FR-UX-06 PR13)를 흡수해 여기로 통합했다(FR15-b).
  // 그 테스트는 계약 문자열 5개를 **손으로 나열**하고 대상은 `breadcrumb` 하나로
  // 하드코딩돼 있어, 새 라벨을 추가해도 아무것도 검사하지 않았다(`breadcrumb`
  // 자신조차 목록에 없었다). 목록을 없애면 다음 라벨 추가가 자동으로 검사 대상이 된다.
  // ───────────────────────────────────────────────────────────────────────────
  describe('FR15 — 라벨 쌍 substring 전수 판별식', () => {
    /**
     * substring 관계가 **허용된** 쌍. 실재하는 예외 2쌍뿐이며 둘 다 근거가 다르다.
     *
     * 1. `projectNav`('프로젝트') ⊂ `projectViewNav`('프로젝트 뷰 전환') —
     *    **둘 다 nav `aria-label`이라 실제 위험이 있다.** Playwright `getByRole`은 기본이
     *    substring 매칭이므로 e2e에서 `exact: true`가 필수이며, 그 경고가 `nav-labels.ts`
     *    JSDoc에 이미 있다. 위험을 없앤 게 아니라 **명시적으로 관리**하는 쌍이다.
     *
     * 2. ~~`admin`('관리') ⊂ `adminNav`('관리 메뉴')~~ — **면제가 사라졌다(Jira 패리티 J9).**
     *    `admin` 은 사이드바 관리 nav 안 `<p>` 표시 텍스트 전용이었는데, 그 nav 가 상단바
     *    관리 허브 링크로 옮겨가면서 소비처가 0이 되어 키 자체를 제거했다. 면제를 지운 것이
     *    아니라 **쌍의 한쪽이 없어진 것**이라 이 자리는 비어 있는 게 맞다.
     *
     * 3. `search`('검색') ⊂ `globalSearch`('전역 검색') — **둘 다 `aria-label`이라 실제
     *    위험이 있다.** FR-UX-12 F13이 **의도적으로** 만든 쌍이다(Jira 패리티 계약 §2
     *    「`검색` 이름 분리」, Maxi 확정 2026-07-28 결정 4). 상단바 입력창과 AQL 검색 페이지
     *    제출 버튼은 한 화면에 공존하므로 이름을 **합치면** strict mode 위반이고,
     *    분리하면 substring 관계가 남는다 — 둘 중 후자를 택한 결과다.
     *    관리 근거 2겹. ① role이 다르다(`searchbox` vs `button`/`option`)
     *    ② `'검색'`으로 조회하는 e2e **5개 지점 전량**이 이미 `exact: true`다
     *    (2026-08-05 `grep -rn "name: '검색'" e2e/` 실측 — 재실측 없이 대조하도록 전수 열거).
     *       · `search.spec.ts:146`        `getByRole('button', { name: '검색', exact: true })`
     *       · `search.spec.ts:148`        동 컨테이너 한정 클릭
     *       · `saved-filters.spec.ts:280` `getByRole('button', { name: '검색', exact: true })`
     *       · `saved-filters.spec.ts:282` 동 컨테이너 한정 클릭
     *       · `command-palette.spec.ts:293` `getByRole('option', { name: '검색', exact: true })`
     *    Testing Library의 `name`은 기본이 완전일치라 유닛은 선재 안전.
     *
     * 4. `search`('검색')·`issues`('이슈') ⊂ `globalSearchPlaceholder`('이슈 검색') —
     *    **placeholder는 접근성 이름이 아니다.** 입력창에 `aria-label`(`globalSearch`)이
     *    있으므로 accname 계산에서 placeholder는 이름이 될 수 없고 `getByRole` 계열은 이
     *    값을 보지 못한다. 남는 표면은 `getByPlaceholder` 부분일치 하나뿐인데
     *    `'검색'`·`'이슈'`로 placeholder를 조회하는 지점은 0건이다(2026-08-05 전수 실측).
     *    ⚠️ **placeholder를 `aria-label`로 승격하면 이 면제 2건을 제거해야 한다.**
     */
    const ALLOWED_SUBSTRING_PAIRS: ReadonlyArray<readonly [keyof typeof navLabels, keyof typeof navLabels]> =
      [
        ['projectNav', 'projectViewNav'],
        ['search', 'globalSearch'],
        ['search', 'globalSearchPlaceholder'],
        ['issues', 'globalSearchPlaceholder'],
      ]

    /** 런타임으로 훑은 모든 (a, b) 순서쌍 — 손으로 나열하지 않는다 */
    const allOrderedPairs = Object.entries(navLabels).flatMap(([keyA, valueA]) =>
      Object.entries(navLabels)
        .filter(([keyB]) => keyB !== keyA)
        .map(([keyB, valueB]) => ({ keyA, valueA, keyB, valueB })),
    )

    const isAllowed = (keyA: string, keyB: string): boolean =>
      ALLOWED_SUBSTRING_PAIRS.some(
        ([allowedInner, allowedOuter]) => allowedInner === keyA && allowedOuter === keyB,
      )

    it('비-공허: 검사 대상 쌍이 0건이 아니다', () => {
      // 라벨이 사라지거나 훑기가 고장 나면 0쌍이 되어 아래 단언들이 조용히 통과한다.
      // 키 N개면 순서쌍은 N*(N-1)개다.
      const keyCount = Object.keys(navLabels).length
      expect(keyCount).toBeGreaterThan(1)
      expect(allOrderedPairs).toHaveLength(keyCount * (keyCount - 1))
    })

    it('화이트리스트 짝 검사: 허용 쌍은 실제로 substring 관계여야 한다', () => {
      // 짝을 잘못 적으면 파생이 틀린 쌍을 면제하며 통과한다(#323 M9 동형 결함).
      for (const [inner, outer] of ALLOWED_SUBSTRING_PAIRS) {
        expect(navLabels[outer].includes(navLabels[inner])).toBe(true)
        expect(inner).not.toBe(outer)
      }
    })

    it('화이트리스트에 없는 어떤 쌍도 substring 관계가 아니다', () => {
      const violations = allOrderedPairs
        .filter(({ keyA, valueA, keyB, valueB }) => valueB.includes(valueA) && !isAllowed(keyA, keyB))
        .map(({ keyA, valueA, keyB, valueB }) => `${keyA}('${valueA}') ⊂ ${keyB}('${valueB}')`)

      expect(violations).toEqual([])
    })

    it('모든 라벨 값은 비어 있지 않다 (빈 문자열은 모든 문자열의 substring)', () => {
      // 빈 문자열을 허용하면 위 단언이 전부 무의미해진다.
      for (const [key, value] of Object.entries(navLabels)) {
        expect(value, `${key} 라벨이 비어 있다`).not.toBe('')
      }
    })

    it('라벨 값에 중복이 없다 (같은 값 두 키는 서로의 substring)', () => {
      const values = Object.values(navLabels)
      expect(new Set(values).size).toBe(values.length)
    })
  })
})
