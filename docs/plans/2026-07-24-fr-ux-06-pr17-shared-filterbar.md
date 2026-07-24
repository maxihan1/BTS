# FR-UX-06 Phase 5 PR17 — IssueFilterBar + BoardFilterBar → 공유 FilterBar 통합

> slug: fr-ux-06-pr17-shared-filterbar
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-24
> 마스터 플랜: docs/plans/2026-07-17-fr-ux-06-jira-redesign/plan.md (PR17)
> 허브 메모리: fr-ux-06-jira-redesign-plan

## Brief

FR-UX-06 Jira 재개편 Phase 5(화면)의 첫 PR. 거의 클론된 두 필터바 컴포넌트
`components/issues/IssueFilterBar.tsx` · `components/board/BoardFilterBar.tsx`
(+ 각 test)를 공유 `FilterBar` 하나로 통합한다. i18n 라벨 이원화 제거,
약 -350 LOC 순감 목표.

- 소비처: `routes/issues.index.tsx`(IssueFilterBar) · `routes/projects.$projectKey.board.tsx`(BoardFilterBar)
- 순수 프론트(apps/web). 백엔드/마이그레이션 0. FR 총수 불변 129.
- FR-UX-06 D3~D7 진척 마킹은 소비 화면 PR에서 (허브 메모리 규칙).

**classify 정정**. classify-task가 backend/backend-engineer/issue-tracking으로 오판
→ controller가 ui/frontend-engineer로 정정(순수 apps/web 컴포넌트 리팩터). #295·#298·#300 선례 동형.

## 도메인 정리

- **BC**: issue-tracking(IssueFilterBar, 이슈 검색 필터) + agile-planning(BoardFilterBar, 보드 퀵필터). 프론트 UI 레이어 통합이라 백엔드 BC 격리와 무관 — 공유 컴포넌트는 UI 레이어에 위치.
- **신규 도메인 용어**: 없음. glossary 기존 용어만 관련 — `퀵 필터`(FR-UX-01, `BoardFilterQueryParser` x-www-form-urlencoded 계약·`activeQuickFilterId` id 추적)·`즐겨찾기`(FILTER 타깃, FR-SR-03 후속).
- **신규 엔티티/관계**: 없음(순수 UI 컴포넌트 통합, 백엔드/마이그레이션 0).
- **기존 결정 충돌**: 없음. 상위 FR-UX-06 ADR D1~D8 노선 계승. ADR 140행이 "`FilterBar` 통합 −350"을 명시 → PR17은 이미 로드맵에 있음.
- **관련 ADR**: [docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md](../../decisions/2026-07-17-fr-ux-06-jira-redesign.md) (기존, 신규 생성 없음).
- **right-size 근거**: 완전히 스펙된 도메인 소비 리팩터(신규 용어 0·ADR 0) → 대화형 grill-with-docs 생략. #290·#300 선례 동형.
- **★보존 계약**(스펙 단계로 인계): 공유 FilterBar가 (1) 이슈 검색 필터 동작 (2) 보드 퀵필터 계약(`BoardFilterQueryParser` 양방향 serialize/deserialize·`activeQuickFilterId` 문자열 id 추적·보드당 20건 상한)을 **양쪽 다 보존**해야 함. 완전 동일 클론 아님(391 vs 211 LOC).

## 스펙

전체 스펙. [docs/specs/2026-07-24-fr-ux-06-pr17-shared-filterbar.md](../specs/2026-07-24-fr-ux-06-pr17-shared-filterbar.md)

핵심 설계 4결정.
- **D-1** 공유 `components/filters/FilterBar.tsx` 코어 추출 + `IssueFilterBar`/`BoardFilterBar`를 위임 얇은 래퍼로 축소 → **소비처·소비처 테스트 0 변경**(최소 폭발 반경).
- **D-2** 상태 섹션은 옵션 슬롯(`statusSection`/`statusChips`/`extraActiveCount`). `useWorkflows`·`StatusMultiSelect`는 `IssueFilterBar` 래퍼에 캡슐화(조건부 훅·보드 불필요쿼리 회피).
- **D-3** i18n 라벨 단일화(`i18n/filter-bar-labels.ts`) — 두 파일이 `statusLabel` 한 키 빼고 완전 동일, **표시 문자열 byte 불변**(시각 회귀 0).
- **D-4** `idPrefix` prop으로 element id(`issue-filter-*`/`board-filter-*`) verbatim 보존(셀렉터 계약 불변).

**right-size 근거**. office-hours(제품 아이디어용)·design-shotgun(새 화면용)·design-consultation(DESIGN.md 이미 존재) 전부 스킵 — 동작 보존 기술 리팩터라 부적합([[bts-spec-office-hours-mismatch]]).

## Brainstorming Check

✅ 통과 (self, 리팩터 — 1회). gap 2건 반영: 조건부 훅 회피 위해 useWorkflows 래퍼 캡슐화 · i18n 병합 시 표시 문자열 byte 불변 명시(시각 회귀 방지). Maxi 결정 필요 항목 없음.

## Plan

> 동작 보존 리팩터 — 기존 `IssueFilterBar.test`/`BoardFilterBar.test`가 **회귀 하네스**.
> 공유 `FilterBar`는 신규 코드라 TDD red→green. 소비처(issues.index·board 라우트·projects.board.test) **무변경**.

### Task 1. i18n 라벨 단일 출처 (`filter-bar-labels.ts`) — 파생 shim

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/filter-bar-labels.ts`, `apps/web/src/i18n/issue-filter-labels.ts`, `apps/web/src/i18n/board-filter-labels.ts`, `apps/web/src/i18n/filter-bar-labels.test.ts`]
- depends-on: []

**RED**: `filter-bar-labels.test.ts` — `filterBarLabels`가 공통 필터 라벨(assignee/label/component/reset/unassigned·chip.removeAriaLabel·count.applied·search)을 노출, `issueFilterLabels.filter.statusLabel==='상태'`, `boardFilterLabels`엔 statusLabel 부재(공개 shape 보존). 실패: `filterBarLabels` 없음.

**GREEN**: `filter-bar-labels.ts` 신설(공통 문자열 단일 출처). `issue-filter-labels.ts`→`{ statusLabel + ...filterBarLabels.filter }` 파생(공개 shape·문자열 byte 불변). `board-filter-labels.ts`→`filterBarLabels` 재export. **외부 importer(board 라우트 251·quick-filter-labels·ko.test) 무변경**.

**REFACTOR**: `as const` 타입 보존 확인, JSDoc.

**검증**: `pnpm --filter web test -- filter-bar-labels ko.test` green (문자열 회귀 0).

### Task 2. 공유 `FilterBar` 코어 + 서브컴포넌트 (신규, TDD)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/filters/FilterBar.tsx`, `apps/web/src/components/filters/FilterBar.test.tsx`]
- depends-on: [1]

**API 계약**(제네릭 `<T extends BoardCardFilterParams>`):
```
projectKey: string
value: T
onChange: (next: T) => void
idPrefix: string                 // 'issue-filter' | 'board-filter'
statusSection?: ReactNode        // 담당자 앞 슬롯(이슈 StatusMultiSelect)
statusChips?: ReactNode          // 활성 칩 맨 앞 슬롯(이슈 상태 칩)
extraActiveCount?: number        // activeCount 가산(이슈 statusKeys.length)
```
내부 소유: `AssigneeSection`·`ActiveFilterChips`·`Chip`·`handleAssigneeSelect/LabelCommit/Reset`·wrapper·count. 공통 라벨은 `filterBarLabels` 직접 import.

**RED**: `FilterBar.test.tsx` — 제어형 동작 전수(담당자 typeahead 선택/중복무시/제거·라벨 commit/제거·컴포넌트 선택·초기화·activeCount(+extraActiveCount)·칩 null·`{idPrefix}-assignee-input`/`-label-input` id·chip aria `{name} 제거`·statusSection/statusChips 슬롯 렌더·useUsersByIds 이름 안정). 실패: `FilterBar` 없음.

**GREEN**: `FilterBar.tsx` 구현. 담당자/라벨/컴포넌트/칩/초기화/count 코어 + 슬롯.

**REFACTOR**: 서브컴포넌트 분리·KDoc·`readonly` props.

**검증**: `pnpm --filter web test -- FilterBar.test`.

### Task 3. `IssueFilterBar` → 얇은 위임 래퍼 (status 캡슐화)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issues/IssueFilterBar.tsx`, `apps/web/src/components/issues/IssueFilterBar.test.tsx`]
- depends-on: [2]

**GREEN**: `IssueFilterBar` 내부를 `<FilterBar idPrefix="issue-filter" status 슬롯 주입 …>`로 위임. **래퍼가 소유**: `useWorkflows`·`extractStatusOptions`·EC7 fail-safe·`StatusMultiSelect`(이관)·statusNameMap → `statusSection`/`statusChips`/`extraActiveCount=statusKeys.length` prop으로 주입. **props 시그니처(`projectKey/value:IssueFilterParams/onChange`) 불변** → 소비처 issues.index 무변경.

**RED→GREEN 순서**: 기존 `IssueFilterBar.test.tsx`가 회귀 하네스(무수정 green 우선). 공통 동작 어서션은 Task 2 `FilterBar.test`로 이관됐으므로 **래퍼 테스트는 래퍼 고유(상태 섹션 렌더·상태 칩·statusKeys activeCount·EC7)만 남기고 슬림화**(커버리지 손실 0 — 이관 대조).

**검증**: `pnpm --filter web test -- IssueFilterBar` + `issues.index` 관련 green.

### Task 4. `BoardFilterBar` → 얇은 위임 래퍼 (status 없음)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/BoardFilterBar.tsx`, `apps/web/src/components/board/BoardFilterBar.test.tsx`]
- depends-on: [2]

**GREEN**: `BoardFilterBar` 내부를 `<FilterBar idPrefix="board-filter" value:BoardCardFilterParams …>`(status 슬롯 없음)로 위임. **props 시그니처 불변** → 소비처 board 라우트·`projects.board.test` 무변경.

**RED→GREEN**: 기존 `BoardFilterBar.test.tsx` 회귀 하네스. 공통 어서션 이관 후 래퍼 고유(상태 섹션 부재·위임)만 슬림 유지.

**검증**: `pnpm --filter web test -- BoardFilterBar projects.board`.

## Plan 메타

- task 수: 4 (각 TDD 사이클)
- wave: [T1] → [T2] → [T3 ∥ T4] (T3/T4 파일 disjoint: issues/* vs board/*). **단, 단일 worktree 병렬 git 레이스 회피 위해 직렬 우선 권장**([[worktree-lint-staged-shared-git-stash-collision]]·[[parallel-dispatch-precommit-hook-race]]).
- TDD 강제: yes (신규 FilterBar). 래퍼는 회귀 하네스 무수정 우선 후 슬림.
- 추가 검증(controller): `pnpm --filter web verify`(lint+typecheck+test+build) + 관련 e2e(issues 필터·board 필터) 로컬. **소비처 3파일 diff 0 직접 확인**(git show 대조). FR 총수 129 불변.
- ★리뷰 포커스: (1) 소비처 무변경 실증 (2) 슬림화가 커버리지 회귀 아님(이관 어서션 대조) (3) i18n 파생 shim이 ko.test/quick-filter 무영향 (4) idPrefix element id verbatim.

## 구현 결과 (2026-07-24 — bts-impl)

- **TDD 5태스크 완료**: T1 test`82e78857`→feat`e61385a5` · T2 test`0fceac67`→feat`191319b6` · T3 test`005ef4ba`→feat`31b47c9e` · T4 refactor`6021d2b1`(순수 위임, 기존 테스트=판별자) · T5 refactor`779f1ac8`(래퍼 테스트 슬림화).
- **동작 보존 증명**: IssueFilterBar.test·BoardFilterBar.test **무수정 green**(byte-identical) → 이후 T5에서 공통 어서션만 FilterBar.test 대조 후 슬림(커버리지 순손실 0). 소비처 issues.index·board 라우트·projects.board.test **diff 0**.
- **컴포넌트 축소**: IssueFilterBar 391→155 · BoardFilterBar 200→4 · 공유 FilterBar 332 신설. i18n 단일출처(filter-bar-labels)+파생 shim(외부 importer 4곳 무접촉).
- **LOC**: 프로덕션 −129, 신규 공유 FilterBar 종합 테스트(+516)·i18n 테스트(+108)로 **전체 순 +176**. −350 추정 미달(엄밀 목표 아님·스펙 명시) — 중복 제거는 달성, 증가분은 신규 공유 컴포넌트 고품질 테스트.
- **검증 전항 통과**: typecheck 0 · lint 0(PR17 파일) · 전수 유닛 **490파일/7620 green** · build 0 · 필터 e2e(issue-filter·board-filter·quick-filter) **22 passed**. FR 총수 **129 불변**.

## 리뷰 결과

### right-size 판정 (2026-07-24)
- TYPE=ui의 지정 리뷰는 `plan-design-review`(대화형 디자이너 눈). 그러나 본 PR은 **시각 변화 0 동작 보존 리팩터**(byte-identical 렌더가 요구사항) → 평가할 새 디자인 없음. 인터랙티브 디자인 리뷰 스킵, **엔지니어링 self plan-review**로 대체([[bts-review-plan-autoplan-overkill]]). #290·#300 right-size 선례.

### 엔지니어링 self plan-review
- ✅ **동작 보존**: 래퍼(IssueFilterBar/BoardFilterBar) props 시그니처 불변 → 소비처 무변경. 기존 래퍼 테스트가 회귀 하네스. FilterBar 코어가 classNames/DOM/aria verbatim 복제해야 함(검증=기존 테스트+FilterBar.test+게이트2 git diff -w 소비처 대조).
- ✅ **blast radius**: `boardFilterLabels` 외부 importer 4곳(board 라우트 251·quick-filter-labels·ko.test·BoardFilterBar.test)을 파생 shim으로 무접촉. `issueFilterLabels`는 IssueFilterBar 전용.
- ✅ **idPrefix**: element id(`issue-filter-*`/`board-filter-*`) verbatim → 셀렉터 계약 불변.
- ✅ **status 캡슐화**: useWorkflows/StatusMultiSelect를 IssueFilterBar 래퍼로 이관(조건부 훅·보드 불필요쿼리 회피).
- ⚠️ **커버리지 회귀 리스크(리뷰 포커스)**: T3/T4가 래퍼 테스트를 슬림화하며 공통 어서션을 FilterBar.test로 이관 → **이관 누락 시 커버리지 손실**. 완화=이관 어서션 1:1 대조(게이트2 어드버서리얼). 보수적 대안=기존 래퍼 테스트 무수정 유지(순감 축소 감수).
- ⚠️ **i18n 파생 shim 타이핑(리뷰 포커스)**: `as const` 위젯닝으로 ko.test 타입 어서션 깨질 가능 → T1 RED에 ko.test green 포함. board shim은 statusLabel 부재 shape 보존 필수.
- **BLOCKER: 없음.** taste decision: 없음(시각 불변).
