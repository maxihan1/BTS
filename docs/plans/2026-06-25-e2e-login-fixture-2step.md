# workflow-scheme/already-authed E2E 회귀 17건 — 로그인 fixture 2단계 통일

> slug: e2e-login-fixture-2step
> type: qa
> agent: qa-engineer
> 생성: 2026-06-25

## Brief

FR-AU-07(identifier-first 2단계 로그인) 도입 후, 일부 E2E 로그인 헬퍼가 옛 1단계 방식으로 잔존해
전체 E2E 실행 시 17건 실패. 회귀 수정(구현 코드 src/ 무수정, E2E fixture/spec만).

- 실패 원인: 새 로그인 첫 화면엔 '사용자명' 필드가 없음(이메일+계속만) → 옛 1단계 헬퍼가 `getByLabel('사용자명')` 30초 timeout.
- 실패 17건: workflow-scheme-* 16건(`workflow-scheme-fixtures.ts`의 옛 loginAsAlice 공유) + already-authed.spec.ts 1건(인라인 1단계 직접 작성).
- 정상: inbox 등은 `issue-fixtures.ts`의 2단계 loginAsAlice 사용(이미 수정됨) → 통과.
- 메모리 [[e2e-loginasalice-fixture-fr-au-07-regression]] 미해결 항목.

## fast-track 사유

type=qa + 회귀 수정(도메인 모델 변경 0, 스펙=옛 동작 복원으로 자명) → domain/spec/review-plan 스킵, plan 직행.

## 현황 (Explore 확정)

loginAsAlice 정의가 **3벌 중복**. 근본 원인 = 복사된 헬퍼 정의.

| 정의 | 형태 | 사용 spec | 상태 |
|---|---|---|---|
| `issue-fixtures.ts:29` | 2단계(이메일→계속→provider Local→username/pw) + loginStrings | 52 | ✅ 정본 |
| `session-fixtures.ts:59` | 2단계 동일 + 하드코딩 문자열 | 14 | ✅ (i18n 미흡) |
| `workflow-scheme-fixtures.ts:28` | **1단계**(첫 화면서 username 바로) | 5 | ❌ 실패 |
| `already-authed.spec.ts:6-9` | 인라인 1단계 | 1 | ❌ 실패 |

loginStrings 2단계 키 완비(emailLabel='이메일'/continueButton='계속'/providerLabel='로그인 방식'/providerLocal='Local'/usernameLabel/passwordLabel/submitButton).

## Plan

> 근본 예방 = 공유 헬퍼 1벌 + re-export(기존 import 경로 변경 0, 정의는 한 곳). 회귀 수정이라 기존 17 실패가 RED.

### Task 1. 로그인 헬퍼 공유 통합 + 옛 1단계 제거

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/fixtures/auth-fixtures.ts`(신규), `apps/web/e2e/fixtures/issue-fixtures.ts`, `apps/web/e2e/fixtures/session-fixtures.ts`, `apps/web/e2e/fixtures/workflow-scheme-fixtures.ts`, `apps/web/e2e/already-authed.spec.ts`]
- depends-on: []

**RED**: 현재 전체 E2E에서 `workflow-scheme-*` 16 + `already-authed` 1 = 17건이 `getByLabel('사용자명')` timeout으로 실패(옛 1단계). 이 기존 실패가 RED 상태(별도 테스트 작성 불필요 — 회귀 수정).

**GREEN**:
1. `apps/web/e2e/fixtures/auth-fixtures.ts` 신규 — `loginAsAlice(page)` 2단계 정본(issue-fixtures 형태 그대로: 이메일 `alice@example.com`→"계속"→provider combobox Local 선택→username `alice`/password→로그인→`waitForURL('**/dashboard')`). **loginStrings 정본 참조**(하드코딩 금지). 첫 줄 한국어 헤더 주석.
2. `issue-fixtures.ts`: 옛 loginAsAlice 정의 삭제 → `export { loginAsAlice } from './auth-fixtures'`.
3. `session-fixtures.ts`: 옛 loginAsAlice(하드코딩 2단계) 삭제 → re-export. (하드코딩→정본 i18n으로 자연 통일)
4. `workflow-scheme-fixtures.ts`: 옛 loginAsAlice(1단계) 삭제 → re-export. (i18nLabels/navigate* 등 다른 export 보존)
5. `already-authed.spec.ts`: 인라인 1단계 삭제 → `import { loginAsAlice }` + 호출. 잔여 하드코딩 셀렉터(환영 heading)는 가능하면 정본 참조.

**REFACTOR**: loginAsAlice 정의가 auth-fixtures 1곳만 남았는지 grep 확인(중복 0).

**검증**: 전체 E2E 재실행 — (a) workflow-scheme 16 + already-authed 1 = 17건 통과, (b) 기존 통과 spec(52+14) 회귀 0. `pnpm exec playwright test`.

## Plan 메타

- task 수: 1 (단일, qa-engineer)
- TDD: 회귀 수정 — 기존 17 실패가 RED, fixture 수정이 GREEN
- 검증: 전체 E2E ground-truth(17 해소 + 회귀 0)

## 리뷰 결과 (← /bts-codereview 채움)
