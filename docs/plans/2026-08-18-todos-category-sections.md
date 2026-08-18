# TODOS.md 를 카테고리 절로 물리 재배열한다

> 티어: T2
> slug: todos-category-sections
> type: chore
> agent: (인라인 판정 — 게이트 1에서 확정)
> 생성: 2026-08-18

## Brief

**Maxi 원문.** 「todos.md와 대시보드 개선 작업 진행중이었는데 그거 이어서 하자」

**정확한 범위.** `docs/plans/2026-08-18-debt-dashboard-plain-language.md` 가 PR #389 에서
**후속으로 이월한 W7·W10**, 그리고 그 계획서가 「#389 가 만든다」고 적었으나 **만들어지지 않은
안전망**이다.

| # | 항목 | 출처 |
|---|---|---|
| 1 | 재배열 무손실을 기계로 확인하는 안전망 | 같은 계획서 `:221`(Maxi 결정 ③ 표) · `:245`(근거 ㄱ) · NFR N5 |
| 2 | W7 — `TODOS.md` 를 카테고리 절로 물리 재배열 | 같은 계획서 `:97` (2026-08-18 Maxi 추가 요청) |
| 3 | W10 — 영역 접두 없는 항목 1건에 접두 부여 | 같은 계획서 `:100` |

**★착수 전 실측 — 안전망은 만들어지지 않았다.**
`docs/plans/2026-08-18-debt-dashboard-plain-language.md:221` 은 「후속 PR 이 쓸 안전망(본문 해시
불변 판별식)은 이 PR 이 만든다」고 적고, `:245` 근거 ㄱ 는 「그 판별식이 (나)에서 나온다. 순서가
뒤집히면 맨손으로 5,000줄을 옮기는 것이다」라고 적는다. 실측 결과 —

- `scripts/` 전체에 `해시`/`hash` **0건**
- `todos-plain-language-contract.test.ts` 12종 · `build-dashboard.test.mjs` 36종 어디에도
  **재배열 전후 비교 단언 없음**
- 같은 계획서 `:630` **Implementation Tasks 이행 상태(T1~T5)에 이 항목 자체가 없다**

즉 약속 목록과 이행 목록이 서로를 대조하지 않았다 — 저장소가 이름 붙인
`two-lists-never-check-each-other` 의 재발이다. **이 PR 의 첫 task 가 그 안전망 신설이다.**

**Maxi 결정 2건 (착수 전 선결).**

| # | 결정 | 근거 |
|---|---|---|
| A | **재배열 구조 A안** — 미해결 28건만 카테고리 5절 H1 로. 해소 76건은 맨 뒤 「해소」 절에 현재 순서 그대로 | 화면 렌더(`renderByCategory`)가 이미 미착수·보류만 카테고리로 묶는다(NFR N2). 파일 구조가 같은 규칙 하나를 따르게 해 두 벌이 갈라질 여지를 없앤다. B(✅까지 분류)는 N2 를 뒤집는 재결정 + 이동량 4배, C(재배열 취소)는 Maxi 추가 요청을 무기한 연기 |
| B | **티어 T2 로 승격** | 안전망이 `parseTodos` 에 소속 절 정보를 요구하고, `scripts/build-*.mjs` 는 `GUARD_CI` 표면(T2). 판별식이 자체 fence 스캔을 쓰는 우회는 `every-new-heading-scanner-forgets-code-fences` 가 경고한 **4벌째 펜스 맹목 스캐너**를 만드는 길이라 기각 |

**classify 결과와 오버라이드.**

| 항목 | classify 출력 | 실제 | 근거 |
|---|---|---|---|
| type | `backend` | **`chore`** | Kotlin **0줄** 작업이다. 신호 0 → `backend` 기본값으로 떨어진 것. 부채 `52` 의 5번째 자기 실연 |
| agent | `backend-engineer` | 미정 | 백엔드 무관. 게이트 1에서 확정 |
| slug | `todos-md` | **`todos-category-sections`** | 파일명만 남고 작업 내용이 사라졌다 |
| tier | `T1` | **`T2`** | 표면 실측 — Maxi 결정 B |

**티어 근거 (표면 실측).**

| 경로 | 표면 | 티어 |
|---|---|---|
| `TODOS.md` | `DOC` (`*.md`) | T0 |
| `scripts/workflow/*.test.ts` (신설) | `TEST` (`scripts/**/*.test.{ts,mjs}`) | T1 |
| `scripts/build-dashboard.mjs` | **`GUARD_CI`** (`scripts/build-*.mjs`) | **T2** |

혼합 → 최고 티어 **T2**. `UNMAPPED` 0.

**★게이트 2 요약에 실을 것.** 신설 판별식은 실질 `GUARD_CI` 성격인데 `SURFACE_PRECEDENCE` 의
`TEST` 우선 때문에 단독으로는 T1 로 떨어진다 — 부채 `42`(「티어 표면이 강제 장치를 T1 로
떨어뜨린다」)의 실연이다. 이번엔 `build-dashboard.mjs` 동반 수정이 T2 를 끌어올렸을 뿐,
**판별식만 신설하는 PR 이었다면 T1 로 통과했다.**

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
