# FR-UX-06 Phase 5 PR22 — 원시 button 정리 + EmptyState/Skeleton 중복 제거 + --chart-* 실소비

> slug: fr-ux-06-pr22-ui-cleanup
> type: ui (classify backend→ui 실측 정정)
> agent: frontend-engineer
> BC: personalization (물리 identity-access, 화면은 apps/web)
> 생성: 2026-07-25

## Brief

FR-UX-06(BTS UI/UX를 Jira Cloud 2025 방식으로 개편) **22 PR 체인의 마지막 PR**.
Phase 5 화면 PR(PR17~21b)이 끝난 뒤 남은 **정리 3덩어리**를 처리해 개편을 종료한다.

1. **원시 `<button>` 정리** — 공용 `components/ui/button`을 우회한 raw `<button>`을 프리미티브로 흡수.
2. **`FilteredEmptyState` / `Skeleton` 중복 제거** — 화면별로 복붙된 빈 상태·로딩 스켈레톤을 공용 컴포넌트로 통합.
3. **`--chart-1~5` 실소비 정의** — PR3에서 "소비자 0인 토큰을 미리 채우면 그게 PoC"라며 동결한 차트 색 5종을,
   PR4가 이연한 **범주색**(이슈타입 점·진행바·캘린더 스와치 등)에 실제로 소비하면서 값을 확정.

**classify 정정**. classify-task가 `type=backend / agent=backend-engineer`로 오분류.
실측 = 순수 프론트(`apps/web/**`), 백엔드/마이그레이션 변경 0 예상. FR-UX-06 주 BC=personalization.
PR9·PR10·PR12·PR21b 동일 오분류 선례.

**착수 시점 실측(bts-start, main `f2beb806c` 기준)**.

| 항목 | 실측 |
|---|---|
| 원시 `<button>` | **60파일 / 124발생** (`grep -rno '<button' apps/web/src --include='*.tsx'`) |
| `FilteredEmptyState` | **동일 이름 2중 정의** — `routes/issues.index.tsx:155·166`, `routes/projects.$projectKey.board.tsx:241·246` |
| `EmptyState` 계열 | 공용 `components/ui/empty-state.tsx` 존재 + 19파일이 참조 |
| `Skeleton` | 공용 `components/ui/skeleton.tsx` 존재 + 21파일이 참조 |
| `--chart-1~5` | `index.css` 라이트 170~178 / 다크 307~315에 **회색조 placeholder**, 소비처 0, `state-tokens.test.ts:222`가 "동결" 가드 중 |

★ 위 숫자는 **착수 시점 원시 grep**이며 IN-SCOPE 확정치가 아니다. 정당하게 남아야 할 raw `<button>`
(프리미티브 내부 구현, Radix `asChild` 트리거 등)이 섞여 있으므로 **spec 단계에서 전수 분류**해 IN/OUT을 가른다.
[[spec-stated-count-becomes-blindfold]] · [[orchestrator-instruction-counts-are-blindfolds]] — 이 표의 숫자를
믿지 말고 각 단계에서 직접 재계수할 것.

**참조 메모리**. [[fr-ux-06-jira-redesign-plan]] (허브·22 PR 체인·§함정) · [[fr-ux-06-pr21b-swimlane-field-change-done]] (직전 PR21b)
**착수 전 필독**. [[frontend-nav-aria-label-e2e-contract]] · [[playwright-getbyrole-exact-strict-mode]] · [[e2e-playwright-filter-arg-drop]]
**CI에 e2e 잡 없음** ([[frontend-ci-10min-timeout-nonrequired]]) → 로컬 e2e 필수.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
