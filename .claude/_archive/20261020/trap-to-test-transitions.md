<!-- 메모리에 글로 적힌 함정을 회귀 테스트·판별식으로 바꾸는 전환 목록 (Phase 3 산출물 #12) -->

# 함정 → 회귀 테스트 전환 목록

> 확정일 2026-08-13 · 정본 = Phase 2 설계 `D6-history.md §4` · 상위 = `W-phase2-design.md §B-9`
> 이 파일은 **목록**이다. 테스트 실작성은 아래 §3 경계에 따라 별도 PR 로 간다.

## 1. 왜 전환하나 — 순증이 아니다

메모리에 글로 남은 함정은 **읽는 사람이 있어야만** 작동한다. 판별식으로 바꾸면 읽지 않아도 작동한다.
그래서 전환은 자산을 하나 더 만드는 일이 아니라 **자리를 옮기는 일**이다. 각 건의 **대체 대상**은 둘이다.

1. 해당 서브에이전트 파일(`.claude/agents/<role>-engineer.md`)의 「회귀방지」 절 해당 불릿
2. `MEMORY.md` 의 ★ 항목 — 전환이 끝난 건은 개별 메모리의 `metadata.priority` 를 `normal` 로 강등

전환이 끝나기 **전까지만** ★ 를 유지한다(★ 정원 상한의 (a) 전환 대기분).

## 2. 삭제 조건은 시간이 아니라 전환이다

「90일 경과 후 삭제」는 폐기했다. birthtime 90일 초과가 0/430 이고, 첫 발동 대상 2건이
`bts-naming` · `bts-workflow` — **가장 오래된 것이 가장 기초 규약**이라 시간축이 가치와 역상관이었다.
삭제(=★ 강등) 조건은 이것으로 바꾼다.

> **그 함정을 지키는 테스트·판별식이 머지됐고, 일부러 끊어 red 가 나는 것(비-공허 확인)까지 끝났을 때.**

## 3. 실행 경계 — 이 목록이 곧 착수는 아니다

- `scripts/` 아래 판별식은 앱 소스가 아니다 → **바로 쓸 수 있다.**
- 앱 소스(백엔드 Kotlin · `apps/web/src`)를 건드려야 하는 건은 재설계 범위의 「앱 소스 수정 금지」에 걸린다
  → **`TODOS.md` 에 부채 1행으로 등재한 뒤 별도 PR**로 간다. 아래 표의 「부채 경유」 표기가 그것이다.
- 부채 등재 시 주의. `TODOS.md` 에 `## ⬜` 섹션을 새로 만들면
  `docs/plans/2026-08-12-debt24-master.md` 의 전수 매핑 표에 **같은 제목의 행**을 같은 PR 에서 넣어야 한다.
  안 넣으면 `scripts/workflow/debt-ledger-mapping.test.ts` 의 양방향 차집합이 red 다.

## 4. 선별 결과

| 후보(메모리 slug) | 판정 | (a) 테스트 형태 | (b) 배치 | (c) 원본 메모리 |
|---|---|---|---|---|
| `archunit-vacuous-rule-silent-pass` | **전환 1순위** | 정적 판별식 — `allowEmptyShould(true)` 를 쓴 룰은 같은 파일에 **대상 수 ≥1 단언**을 동반해야 한다 | `scripts/workflow/archunit-nonvacuous.test.ts` (Node · 의존 0 · `pnpm test:workflow`) | 전환 후 `normal` 강등 |
| `two-lists-never-check-each-other` | **전환 1순위** | 차집합 판별식 + **양성 대조군**(일부러 끊어 red 확인) | `scripts/workflow/` 기존 차집합 판별식에 확장 (룰 L 은 실재한 적 없다 — `docs/rules/doc-index.md` 참조) | ★ 유지 (지배 결함 양식 · 전환 후에도 원리 설명이 필요하다) |
| `permission-assert-before-existence-makes-403-lie` | 전환 | 컨트롤러 공통 계약 테스트 — 미존재 404 / 무권한 403 | 각 BC `web/` 통합 테스트 공통 베이스 (**앱 소스 → 부채 경유**) | 전환 후 강등 |
| `shared-dev-db-preexisting-rows-fake-green` | 전환 | 시딩 검증 공통 베이스에 「선재 행 0」 선단언 | 해당 BC 테스트 베이스 (**부채 경유**) | 전환 후 강등 |
| `enum-add-breaks-crossmodule-count-guard` | 전환 | 정적 grep — `entries.hasSize(N)` 하드코딩 금지 | `scripts/workflow/` 판별식 | 전환 후 강등 |
| `squash-body-skip-ci-suppresses-main-push-ci` | 전환 | 머지 커밋 본문 `[skip ci]` 차단 가드 | `bts-merge` 절차 + `scripts/workflow/` 판별식 | ★ 유지 (실패 결과가 「CI 0회」다) |
| `catch-all-exceptionhandler-swallows-responsestatusexception` | 전환 (1순위에 얹음) | ArchUnit 룰 — catch-all advice 는 `ResponseStatusException` 전용 핸들러를 동반해야 한다 | archunit 메타와 같은 PR (**부채 경유**) | 전환 후 강등 |
| `unreachable-state-fixture-is-fake-green` | **형태 변경 후 전환** | 정적 열거 **금지**(원리적 불가가 실증됐다). **런타임 가드**만 — 픽스처가 prod 팩토리를 경유하도록 | 부채 경유 · 착수 후순위 | ★ 유지 |
| `global-afterEach-assertion-holds-later-hooks-hostage` | **전환 안 함** | 표면이 전역 훅 1~2파일이라 룰 유지비 > 재발 확률 | — | `CLAUDE.md` 함정 1줄로 흡수 후 강등 |
| `mock-swallowed-prop-is-invisible-to-unit-tests` | **전환 안 함** | prop 마다 수동이라 일반 판별식이 원리적으로 불가. 답은 공통 목 유틸의 **코드 수정** | 부채 1행 | ★ 유지 |
| 보조 — `worktree-silently-disables-husky-hooks` | **프리플라이트 최우선** | 테스트가 아니다 — worktree 진입 시 훅 실재 확인 | `bts-start` + `worktree-hook-wiring.test.ts` 확장 | ★ 유지 |
| 보조 — `worktree-pnpm-verify-deps-symlink` | 프리플라이트 | `node_modules` 링크 실재 확인 | 프리플라이트 스크립트 | 흡수 후 강등 |
| 보조 — `lint-fails-first-leaves-stale-test-xml` | 프리플라이트 | 테스트 결과 XML 의 **신선도** 확인 | 프리플라이트 스크립트 | 흡수 후 강등 |
| 보조 — `button-user-select-auto-is-none` | 시각 회귀 | 스냅샷 (시각 회귀 트랙) | `visual` 잡 | 흡수 후 강등 |

## 5. 착수 순서

1. archunit 비-공허 판별식
2. two-lists 차집합 확장
3. worktree 프리플라이트
4. 403/404 계약
5. 선재 행 0 · enum 카운트 · skip-ci

## 6. ①의 근거 — 실측 (2026-08-13 재확인)

`allowEmptyShould(true)` 는 **18곳 / 4파일**에 있다.

```
backend/modules/search-export-import/src/test/kotlin/com/bts/search/architecture/SearchBcArchTest.kt
backend/modules/notification/src/test/kotlin/com/bts/notification/architecture/NotificationBcArchTest.kt
backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/architecture/AgilePlanningBcArchTest.kt
backend/modules/automation/src/test/kotlin/com/bts/automation/architecture/AutomationBcArchTest.kt
```

주석이 든 사유는 「초기 부트스트랩 단계처럼 모듈에 프로덕션 클래스가 없을 때」다.
**Phase 1 D 단계가 전량 종료돼 그 사유는 소멸했다** — 지금 이 18곳은 정확히
`archunit-vacuous-rule-silent-pass` 를 재생산하는 상태다.
