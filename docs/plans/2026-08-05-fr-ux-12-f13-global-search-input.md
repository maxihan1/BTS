# FR-UX-12 F13 — 상단바 전역 검색 입력창 + 자연어 폴백

> slug: fr-ux-12-f13-global-search-input
> type: ui
> agent: frontend-engineer
> primary_bc: personalization
> FR: FR-UX-12 (D6/D7 을 닫는다)
> 생성: 2026-08-05

## Brief

**사용자 원문.** `/bts FR-UX-12 F13`

**정본 근거.**
- `docs/plan/product/personalization.md:415` — *"F13 — 상단바 전역 검색 입력창 + 자연어 폴백. 지금 전역 검색은 입력창이 아니라 아이콘 버튼이다. `TopBar.tsx:57-66` · `routes/search.tsx` · 신규 `lib/aql-natural.ts`."*
- `docs/plan/product/personalization.md:420` — **`전역 검색` 이름표는 F13 소관** (F4 #340 이 범위 밖으로 명시하고 넘긴 것).
- `docs/plan/product/personalization.md:424` — D6 책임 `designer → frontend-engineer`, **F13 미착수라 `[ ]` 유지**.
- `docs/design/jira-parity-roadmap.md:64` — 의존 `(F1 ✅)` 뿐. 독립 착수 가능.

**classify 정정 (controller).** `backend`/`backend-engineer`/`search-export-import`
→ **`ui`/`frontend-engineer`/`personalization`**. 정본 파일 목록이 전부 `apps/web/` 이고
D6 책임이 frontend-engineer. "자연어 검색" 문자열이 BC 를 오인 견인했다. 선례 PR #289.

**착수 전 실측으로 확인된 최대 걸림돌 — `navLabels.search` 봉인 4곳.**
`apps/web/src/i18n/nav-labels.ts:42` 의 `search: '검색'` 이 e2e 계약 문자열로 봉인(`:6`)돼 있고,
아이콘 버튼을 입력창으로 바꾸면 아래가 동시에 깨진다.

| 파일 | 줄 | 단언 |
|---|---|---|
| `components/layout/__tests__/navigation-contract.test.tsx` | `115` | `'검색'` 버튼 정확히 1개 |
| `components/layout/__tests__/navigation-contract.test.tsx` | `165` | `'검색'` 버튼 정확히 1개 |
| `components/layout/__tests__/ShellLayout.test.tsx` | `149` | `navLabels.search` 버튼 정확히 1개 |
| `components/layout/__tests__/TopBar.test.tsx` | `116` | `'검색'` 버튼 1개 **+ 클릭 시 `/search` 이동** |

정본 §4.10 은 이 단언 4건을 언급하지 않는다 — spec 단계에서 문서 동기화 대상.
`TopBar.test.tsx:116` 은 개수뿐 아니라 **동작(클릭→이동)**까지 단언하므로 개수 조정만으로는 부족하다.

**선행 교훈 (learnings 라우팅 결과).**
- `2026-05-26` E2E 셀렉터는 i18n 정본 import — `navLabels.search` 봉인이 이 처방의 산물.
- `2026-05-31` **UI 변경 PR 은 같은 화면의 기존 E2E 를 반드시 함께 돌려라** — 새 요소가 기존
  전역 셀렉터를 strict mode violation 으로 깬다. F13 이 정확히 이 형상.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
