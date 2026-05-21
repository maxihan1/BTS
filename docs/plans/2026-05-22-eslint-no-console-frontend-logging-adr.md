<!-- chore. apps/web eslint no-console 룰 추가 + frontend logging 정책 ADR (PR #11 CONCERNS-1 후속) -->

# eslint no-console 룰 + frontend logging 정책 ADR

> slug. `eslint-no-console-frontend-logging-adr`
> type. `chore`
> agent. `frontend-engineer`
> 생성. 2026-05-22
> 트리거. PR #11 (FR-AU-09 D6 로그인 폼 UI) 머지 시 위임된 CONCERNS-1 후속 작업
> 절대 규칙 기준. `DEVELOPMENT.md §1 절대 규칙 #15` (`console.log`/`println` 금지)

## Brief

### 사용자 원문

apps/web 에 eslint no-console 룰 추가 + frontend logging 정책 ADR 작성. PR #11 머지 시 위임된 CONCERNS-1 후속 작업. useLogoutMutation.ts:21 의 console.error 와 같은 NEVER-15 (console.log/println 금지) 문자 위반을 도구로 차단하고, Pino 도입 시점 / dev-only console / prod 로그 수집 정책을 ADR 로 기록.

### classify 결과 (manual_override 적용)

| 항목 | 자동 분류 | 최종 (Maxi 승인) |
|---|---|---|
| type | `feature` | `chore` |
| agent | `backend-engineer` | `frontend-engineer` |
| primary_bc | `automation` | (없음) |
| slug | `apps-web-eslint-no-console-frontend-logging-adr-pr` | `eslint-no-console-frontend-logging-adr` |
| 경로 | (default) | 정규 경로 (domain/spec/review-plan 격식 유지) |

오분류 사유. classify-task 가 `console.error`, `로깅` 키워드를 backend logging 으로 잡고 `frontend` 키워드는 약하게 본 듯. 직전 PR #11 도 같은 함정 (`API` 키워드 → backend).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
