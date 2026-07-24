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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
