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

## 도메인 정리

> grill-with-docs 스킵 (Maxi 승인 2026-05-22). 사유. 도메인 모델 영향 0.

### 영향 범위

| 항목 | 값 | 근거 |
|---|---|---|
| 바운디드 컨텍스트 | **해당 없음** | frontend 인프라 / 문서 작업. `/bts-domain` SKILL 의 BC 키워드 매핑 표 어디에도 안 걸림 |
| 영향 엔티티 | **없음** | 도메인 엔티티 (Issue / User / Workflow 등) 변경 없음 |
| 신규 용어 | **없음** | Pino, console, no-console 모두 인프라 용어. glossary.md 대상 아님 |
| 기존 ADR 충돌 | **없음** | `docs/decisions/` 13건 중 frontend logging 관련 0건 (`grep -i "log\|console\|pino\|monitoring\|observ"` 결과는 lockout / jwt-issuer / session-pat 만 — keyword 우연 일치) |
| 새 ADR | **신규** (BTS frontend 첫 logging 정책 ADR) | `docs/decisions/2026-05-22-frontend-logging-policy.md` 신설 예정 |

### 현재 위반 사례 (cleanup scope)

`apps/web/src/` 안 `console.*` 호출 grep 결과 **1 건**.

- `apps/web/src/auth/useLogoutMutation.ts:21` — `console.error` ("[logout] 서버 요청 실패 ...")
- PR #11 code-reviewer agent 가 지목한 CONCERNS-1 그 한 줄.
- 토큰/PII 미노출 → 보안 위반 아님. `DEVELOPMENT.md §1 절대 규칙 #15` (`console.log`/`println` 금지) 의 **문자 위반**.

### glossary / domain 노트 갱신

- `Maxi_wiki/BTS/glossary.md` 갱신. **없음** (인프라 용어라 BC glossary 대상 아님)
- `Maxi_wiki/BTS/domain/<bc>.md` 갱신. **없음** (BC 미해당)

## 스펙

전체 스펙. [docs/specs/2026-05-22-eslint-no-console-frontend-logging-adr.md](../specs/2026-05-22-eslint-no-console-frontend-logging-adr.md)

### 핵심 시나리오 3줄 요약

- frontend 개발자가 새 파일에 `console.log` 작성 → CI lint 단계에서 fail check → PR 머지 차단 (BTS 첫 자동 차단)
- 기존 위반 1건 (`useLogoutMutation.ts:21` console.error) — D1-a (warn/error allow) 추천 시 그대로 통과 (NEVER-15 의 `console.log`/`println` 문자와 정확히 일치하지 않음)
- `docs/decisions/2026-05-22-frontend-logging-policy.md` 신규 ADR — Pino / 외부 수집기 미래 도입 트리거 조건 3건 명시 (사용자 100명 / 첫 prod incident / 외부 수집기 결정)

### 게이트 1 검토 대상 — 결정 사항 5건

| ID | 결정 | 추천 옵션 | 사유 |
|---|---|---|---|
| D1 | no-console 룰 allow list | **D1-a** `{ allow: ['warn', 'error'] }` | NEVER-15 문자와 정확 일치, cleanup 부담 0 |
| D2 | dev 디버깅 패턴 | **D2-a** `import.meta.env.DEV` 가드 + allow list | Vite dead-code-elimination 활용, logger 없이 단순 |
| D3 | test 파일 처리 | **D3-a** 동일 룰 (overrides 없음) | 현재 test 위반 0건, 완화 사유 없음 |
| D4 | prod 로그 인프라 / Pino 도입 시점 | **D4-a** 보류 + 트리거 3건 명시 | scope 일치, 인프라 부재 인정 |
| D5 | CI workflow 실행 항목 | **D5-c** lint + typecheck + test | PR #11 의 77개 vitest 회귀 가드, 4분 NFR 안 |

## Brainstorming Check

✅ 통과 (2회 iteration, gap 5건 발견).

| Gap | 분류 | 처리 |
|---|---|---|
| G1 CI / pre-commit 통합 미존재 | Maxi 결정 필요 | GitHub Actions workflow 본 PR scope 포함 (D5 신설) |
| G2 FR-LOG-FE-03 grep 기준 모호 | spec 본문 보강 | D1-a / D1-c 선택지별 정확화 inline |
| G3 D4 트리거 조건 미명시 | Maxi 결정 필요 | 트리거 3건 명문화 (사용자 100명 / 첫 incident / 외부 수집기 결정) |
| G4 NFR-LOG-FE-04 페르소나 약함 | spec 본문 보강 | "Maxi 본인 6개월 후 재독" 페르소나 inline |
| G5 EC-5 error boundary 처리 모호 | ADR 본문 항목 | console.error allow list 통과 정책 명시 (ADR 작성 시 한 줄) |

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
