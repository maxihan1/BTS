# FR-UX-06 Phase 5 PR20 — split view (이슈 목록+상세 2분할)

> slug: fr-ux-06-pr20-split-view
> type: ui
> agent: frontend-engineer
> primary_bc: issue-tracking
> 생성: 2026-07-24

## Brief

**사용자 원문**: FR-UX-06 Phase 5 PR20 — split view (이슈 목록+상세 2분할 화면). 새 라우트/레이아웃, 목록↔상세 URL 동기화. PR19에서 의도적으로 이연한 후속 화면.

**classify 결과**: type=ui · agent=frontend-engineer · primary_bc=issue-tracking · slug=fr-ux-06-pr20-split-view

**맥락**: FR-UX-06(BTS UI/UX를 Jira Cloud 방식으로 전면 개편) Phase 5(화면) 네 번째 PR. 선행 PR17(공유 FilterBar)·PR18(이슈 목록 카드→ui/table·서버정렬)·PR19(이슈 상세 탭화)에서 split view를 명시적으로 이연. 이번 PR에서 이슈 목록+상세를 좌우 2분할로 보는 화면을 신설.

## 도메인 정리

- **BC**: issue-tracking (물리 `apps/web`) / 논리 소속 personalization (FR-UX-06 ADR §D5 "논리 ≠ 물리")
- **영향 엔티티**: Issue, IssueKey (전부 기존 — 신설 0). 이 PR은 순수 view/routing 재구성, 도메인 모델 변경 없음
- **새 용어**: 없음 (glossary "split/2분할" grep 0건 → 신설 불요). "split view(2분할 화면)"은 UI 레이아웃 표현일 뿐 유비쿼터스 언어 대상 아님
- **기존 결정 충돌**: 없음. 이 PR은 FR-UX-06 ADR([2026-07-17-fr-ux-06-jira-redesign.md](../decisions/2026-07-17-fr-ux-06-jira-redesign.md))의 Phase 5 실행 PR. 신규 ADR 불요
- **관련 ADR**: [docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md](../decisions/2026-07-17-fr-ux-06-jira-redesign.md) (기존)

### ADR이 이 화면에 강제하는 제약 (spec으로 인계)

1. **★D4 규칙**: "라우트가 바뀌면 nav+Link, 같은 라우트에서 패널만 바뀌면 Radix Tabs." split view에서 목록 행을 골라 상세를 여는 것은 **URL이 바뀌는 라우트 이동**(체크포인트 "목록↔상세 URL 동기화")이다 → **URL 기반**으로 상세를 결정하고 목록 행은 `<Link>`. Radix Tabs 아님.
2. **★h1 단독 계약 (34 e2e)**: 문서당 `<h1>` 1개(WCAG 1.3.1). 현재 `issues.index`(목록·PageHeader h1)와 `issues.$key`(상세·이슈키 h1)는 **각각 별도 문서**라 h1 1개씩. split view는 둘을 **한 화면에 합성** → h1 충돌 위험. spec에서 상세 페인 h1 강등(→h2/section) 또는 목록/상세 h1 소유 규칙을 반드시 확정.
3. **role="navigation" aria-label 단일성 (18 e2e·4 문자열)**: 새 nav 랜드마크 추가 시 기존 라벨(`메인 메뉴`·`관리 메뉴`·`프로젝트 뷰 전환`·workflow sidebar)과 충돌 금지.
4. **role="dialog" 147 e2e·검색 aria-label 단일**: 이 PR 무접촉이어야 안전.

### 기존 라우트 지형 (실측)

- `routes/issues.index.tsx` — 목록(IssueListPage + IssueListRouteAdapter, PR18에서 ui/table화). `useSearch`로 필터/정렬/컬럼 URL 상태 관리
- `routes/issues.$key.tsx` — 상세(좌 본문 / 우 메타패널, PR19에서 활동 3탭화·IssueMetaPanel 분해)
- 둘 다 code-based 라우팅(router.ts에 adapter 등록, PR#11 컨벤션). `_shell` pathless layout 하위(PR10 재부모화)

### 미해결 설계 질문 (→ spec Phase A에서 결정)

- **라우트 구조**: (a) `issues.index`에 `?selected=KEY` 검색 파라미터로 우측 상세 페인 인라인 로드 vs (b) 별도 split 라우트 신설 vs (c) `issues.$key`를 목록 페인과 나란히. → D4상 "URL이 상세를 결정"이 핵심 계약. spec에서 구체 결정.
- **기존 전체화면 상세(`issues.$key`) 유지 여부** — split view가 기본이 되면 전체화면 상세 라우트를 남길지/리다이렉트할지.
- **반응형** — 좁은 폭(<900px)에서 2분할 붕괴 처리(PR11 사이드바 반응형 관례 참조).

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
