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

## Plan

> 작성 기준. spec §7 결정 사항 D1~D5 의 **추천 옵션 (D1-a / D2-a / D3-a / D4-a / D5-c)** 이 게이트 1 에서 그대로 승인된다고 가정. Maxi 가 다른 옵션 선택 시 plan 갱신 필요.
>
> TDD 변형. 본 PR 의 task 는 모두 인프라 파일 (eslint config, GitHub Actions workflow yml) + 문서 (ADR). 단위 테스트 부자연스러움. learnings.md 의 "2026-05-21 — E2E TDD 변형" 패턴 적용 — RED 대신 spec §3 FR 의 grep 검증 + 실제 명령 실행으로 대체.

### Task 1. eslint no-console 룰 추가 (D1-a 반영)

**메타**.
- agent. `frontend-engineer`
- files. [`apps/web/eslint.config.js`]
- depends-on. []

**RED (변형. grep 검증 부재)**.
- 사전 grep. `grep -n "no-console" apps/web/eslint.config.js` → 결과 **0 줄** (현재 룰 미적용).
- 부재 확인 후 GREEN 으로 진행.

**GREEN**.
- 파일. `apps/web/eslint.config.js`
- 변경. 메인 config 블록의 `rules` 안에 한 줄 추가.

```javascript
// 메인 config 블록 (files: ['**/*.{ts,tsx}']) 의 rules 블록 안.
rules: {
  ...reactHooks.configs.recommended.rules,
  'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
  // D1-a. 'console.log' 등은 NEVER-15 (DEVELOPMENT.md §1 절대 규칙 #15) 문자 위반.
  // warn/error 만 허용 (디버깅 + error boundary). ADR. docs/decisions/2026-05-22-frontend-logging-policy.md
  'no-console': ['error', { allow: ['warn', 'error'] }],
},
```

- shadcn/ui overrides 블록 (`files: ['src/components/ui/**/*.{ts,tsx}']`) 은 그대로 둠 (D3-a 따라 test 파일 overrides 없음).

**REFACTOR**.
- 없음 (한 줄 추가).

**검증**.
1. `grep -n "no-console" apps/web/eslint.config.js` → 결과 1 줄 이상.
2. 임시 위반 fixture 작성 → 차단 확인.
   ```bash
   # apps/web/src/__lint-fixture.ts 임시 생성
   echo 'console.log("violation")' > apps/web/src/__lint-fixture.ts
   pnpm --filter @bts/web lint
   # 예상. exit 1. 위반 메시지 "Unexpected console statement (no-console)"
   rm apps/web/src/__lint-fixture.ts
   ```
3. `pnpm --filter @bts/web lint` (fixture 제거 후) → exit 0. 기존 `useLogoutMutation.ts:21` 의 `console.error` 는 D1-a allow list 통과.
4. `pnpm --filter @bts/web typecheck` → 회귀 0.
5. `pnpm --filter @bts/web test` → 회귀 0 (PR #11 baseline 77 unit).
6. `pnpm --filter @bts/web build` → 회귀 0.

**커밋**.
- 메시지. `chore(web). eslint no-console 룰 추가 (D1-a, warn/error allow)`
- 본문. NEVER-15 + ADR 참조.

---

### Task 2. frontend logging 정책 ADR 신규 (D1~D5 본문 + 트리거 3건)

**메타**.
- agent. `frontend-engineer`
- files. [`docs/decisions/2026-05-22-frontend-logging-policy.md`]
- depends-on. []

**RED**. 없음 (문서. TDD 무관).

**GREEN**.
- 파일. `docs/decisions/2026-05-22-frontend-logging-policy.md`
- 구조. 기존 13건 ADR 양식 따름. `## Status` / `## Context` / `## Decision` / `## Consequences` / `## 미래 정정 트리거` / `## 참조`.

본문 핵심 항목 (검증용).

```markdown
<!-- 1줄 한국어 헤더 (CLAUDE.md §6) -->
# frontend logging 정책 — eslint no-console + Pino 도입 시점

## Status
Accepted (2026-05-22).

## Context
- DEVELOPMENT.md §1 절대 규칙 #15. console.log / println 금지.
- PR #11 (FR-AU-09 D6 로그인 폼 UI) code-reviewer CONCERNS-1. useLogoutMutation.ts:21 의 console.error.
- 현재 prod 로그 수집 인프라 부재 (Pino / Sentry / Datadog / Loki 미도입).
- BTS frontend 첫 logging 정책 ADR.

## Decision
### D1. eslint no-console 룰 = `{ allow: ['warn', 'error'] }`
- 사유. NEVER-15 의 문자 (console.log / println) 와 정확 일치. warn/error 는 사고 디버깅 + error boundary 의 자연스러운 출력.
- 대안. D1-b ['error'] 만 / D1-c 전체 차단. (spec §7 D1 참고)

### D2. dev 디버깅 = `import.meta.env.DEV` 가드 + allow list 호출
- 사유. Vite 의 prod 빌드 시 dead-code-elimination 으로 dev 가드 코드 제거. logger 없이 단순.

### D3. test 파일도 동일 룰 (overrides 없음)
- 사유. 현재 위반 0건. 완화 사유 없음.

### D4. Pino / 외부 수집기 도입 보류
- 사유. 인프라 부재. 본 ADR 는 정책 명시 + 도구화만.

### D5. CI workflow 실행 항목 = lint + typecheck + test
- 사유. BTS 첫 GitHub Actions workflow (.github/workflows/frontend-ci.yml). PR #11 의 77개 vitest 회귀 가드 자동.

## Consequences
- useLogoutMutation.ts:21 의 console.error 는 D1-a allow list 통과 → 별도 cleanup 액션 없음 (정책상 정상).
- React error boundary 의 console.error 도 동일 정책상 통과. prod 빌드에서 사용자 브라우저 console 에 stack trace 노출 — PII 없음 가정.
- dev 디버깅 시 console.log 사용 금지 → `import.meta.env.DEV && console.warn(...)` 패턴 권장.
- branch protection 의 required check 등록은 Maxi 가 GitHub Settings 에서 직접 (본 ADR 비스코프).

## 미래 정정 트리거
다음 중 1건 충족 시 본 ADR 재검토 + Pino / 외부 수집기 도입 결정.
1. **prod 사용자 100명 초과** — 1K BTS 의 10% 도달.
2. **첫 prod incident 발생 (frontend 원인 의심)** — 사용자 보고 incident root cause 가 frontend 로 의심되고 console 로그만으로 재현 불가.
3. **외부 로그 수집기 도입 결정** — Sentry / Datadog / Loki 등이 다른 결정 (예. backend) 으로 들어오면 frontend 도 그 transport 에 붙어야 함.

## 참조
- PR #11 (FR-AU-09 D6 로그인 폼 UI) CONCERNS-1.
- DEVELOPMENT.md §1 절대 규칙 #15.
- spec. docs/specs/2026-05-22-eslint-no-console-frontend-logging-adr.md.
- plan. docs/plans/2026-05-22-eslint-no-console-frontend-logging-adr.md.
```

**REFACTOR**. 없음.

**검증**.
1. `grep -cE "^## " docs/decisions/2026-05-22-frontend-logging-policy.md` ≥ **6** (Status / Context / Decision / Consequences / 미래 정정 트리거 / 참조).
2. `grep -cE "### D[1-5]\." docs/decisions/2026-05-22-frontend-logging-policy.md` = **5** (D1~D5 본문).
3. 트리거 3건 본문 grep — "100명" / "incident" / "수집기" 각 1 회 이상.
4. NEVER-15 / DEVELOPMENT.md 참조 1 회 이상.
5. PR #11 참조 1 회 이상.
6. PoC / prototype / 임시 단어 grep 결과 **0 건** (CLAUDE.md §작업 기준 기준 / DEVELOPMENT.md §1 절대 규칙 #16).

**커밋**.
- 메시지. `chore(docs). frontend logging 정책 ADR 신규 (D1~D5 + 트리거 3건)`
- 본문. PR #11 CONCERNS-1 후속 + spec 참조.

---

### Task 3. frontend-ci GitHub Actions workflow 신규 (BTS 첫 CI)

**메타**.
- agent. `frontend-engineer`
- files. [`.github/workflows/frontend-ci.yml`]
- depends-on. []

**RED (변형. 파일 부재 확인)**.
- 사전 확인. `ls .github/workflows/` → 디렉토리 자체가 없음. BTS 첫 CI workflow.

**GREEN**.
- 파일. `.github/workflows/frontend-ci.yml`
- 본문 (D5-c. lint + typecheck + test).

```yaml
# .github/workflows/frontend-ci.yml — BTS 첫 GitHub Actions CI workflow
# Scope. apps/web (frontend) — lint / typecheck / test. ADR. docs/decisions/2026-05-22-frontend-logging-policy.md (D5-c)
name: frontend-ci

on:
  pull_request:
    paths:
      - 'apps/web/**'
      - 'package.json'
      - 'pnpm-lock.yaml'
      - '.github/workflows/frontend-ci.yml'
  push:
    branches: [main]
    paths:
      - 'apps/web/**'
      - 'package.json'
      - 'pnpm-lock.yaml'
      - '.github/workflows/frontend-ci.yml'

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  lint:
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@v4
      - uses: pnpm/action-setup@v4
        with:
          run_install: false
      - uses: actions/setup-node@v4
        with:
          node-version: 22
          cache: pnpm
      - run: pnpm install --frozen-lockfile
      - run: pnpm --filter @bts/web lint
      - run: pnpm --filter @bts/web typecheck
      - run: pnpm --filter @bts/web test
```

**REFACTOR**. 없음.

**검증**.
1. yaml syntax. `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/frontend-ci.yml'))"` → 예외 없음.
2. `grep -c "paths:" .github/workflows/frontend-ci.yml` ≥ 2 (pull_request + push 각각).
3. `grep -c "cancel-in-progress: true" .github/workflows/frontend-ci.yml` = 1.
4. `grep -c "node-version: 22" .github/workflows/frontend-ci.yml` = 1.
5. lint / typecheck / test 3개 명령 모두 grep 가능.
6. 실제 실행 검증은 Task 4 에서 (push 후).

**커밋**.
- 메시지. `chore(ci). frontend-ci workflow 신규 — BTS 첫 GitHub Actions (D5-c)`
- 본문. lint + typecheck + test + cancel-in-progress + path filter. ADR 참조.

---

### Task 4. 회귀 검증 + GH Actions 실제 실행 검증 (NFR-LOG-FE-02 / NFR-LOG-FE-05)

**메타**.
- agent. `frontend-engineer`
- files. [] (신규 파일 없음, 명령 실행 + 결과 보고만)
- depends-on. [1, 2, 3]

**RED**. 없음 (검증 task).

**GREEN**. 명령 실행 + 결과 기록.

1. **로컬 회귀 검증** (NFR-LOG-FE-02).
   ```bash
   cd .worktrees/eslint-no-console-frontend-logging-adr
   pnpm --filter @bts/web lint        # exit 0, 위반 0
   pnpm --filter @bts/web typecheck   # exit 0
   pnpm --filter @bts/web test        # PR #11 baseline 77 unit pass, 회귀 0
   pnpm --filter @bts/web build       # exit 0
   ```
2. **T1+T2+T3 푸시 후 GH Actions 트리거 확인**.
   ```bash
   # 본 PR (#12) 의 push 이후 자동 트리거. 
   gh pr checks 12
   # 예상. frontend-ci / lint 가 "in_progress" → "success".
   # 또는 GH PR 페이지 https://github.com/maxihan1/BTS/pull/12 의 Checks 탭에서 확인.
   ```
3. **NFR-LOG-FE-05 실행 시간 측정**.
   - 1차 run (cache miss). 시간 측정. 4분 이내 목표.
   - 2차 run (재 push 후 cache hit). 시간 측정. 90초 이내 목표.
   - 두 측정값을 PR comment 또는 plan 의 § 리뷰 결과 에 기록.

**REFACTOR**. 없음.

**검증** (= 본 task 자체가 검증).
- 위 4건 명령 모두 exit 0.
- `gh pr checks 12` 결과 frontend-ci / lint = success.
- NFR-LOG-FE-05 측정값 (cache hit / cold start) 모두 spec NFR 목표 안.
- 만약 GH Actions 가 fail 하면 → fail 원인 분석 후 T1 / T2 / T3 중 해당 task 재진입 (BLOCKED 보고).

**커밋**. 없음 (검증만, 새 파일 / 코드 변경 0). 결과는 plan 파일 § 리뷰 결과 에 기록.

---

## Plan 메타

- task 수. **4**
- wave 구조.
  - **Wave 1** (3-병렬). T1 (eslint config) / T2 (ADR) / T3 (workflow yml). 파일 겹침 0, 코드 의존성 0. PR #7 의 "가장 빠른 케이스" 패턴.
  - **Wave 2** (1-순차). T4 (회귀 검증 + GH Actions 실제 트리거). depends-on. [1, 2, 3].
- 예상 시간. T1 ~10분 / T2 ~20분 / T3 ~15분 / T4 ~10분 (GH Actions cold start 1~3분 포함). 직렬 ~55분 / wave dispatch 시 ~30분.
- TDD 강제. E2E TDD 변형 (RED 대신 grep / yaml syntax / 명령 실행 검증).
- 추가 검증. typecheck / lint / vitest (PR #11 baseline 77 unit) / `pnpm --filter @bts/web build` / `python3 -c "import yaml; ..."` (workflow yml syntax) / `gh pr checks` (GH Actions trigger 결과).
- 게이트 1 검토 후 Maxi 가 D1~D5 중 다른 옵션 선택 시. plan 갱신 + writing-plans 재호출.

### Spec coverage 자체 검증 (writing-plans Self-Review)

| spec 항목 | 매핑 task | 비고 |
|---|---|---|
| FR-LOG-FE-01 (no-console 룰) | T1 | grep 검증 + lint 실행 |
| FR-LOG-FE-02 (D1 옵션) | T1 | D1-a allow list 본문 |
| FR-LOG-FE-03 (cleanup) | T2 (ADR Consequences 명시) + T4 검증 | D1-a 면 코드 변경 0 |
| FR-LOG-FE-04 (ADR) | T2 | 5 결정 + 트리거 3건 grep |
| FR-LOG-FE-05 (test / 외부 lib 예외) | T1 (D3-a, overrides 없음) + T2 (ADR D3) | spec §5 EC 매핑 |
| FR-LOG-FE-06 (workflow yml) | T3 | yaml syntax + path filter grep |
| NFR-LOG-FE-01 (lint 시간 ≤ 100ms diff) | T1 검증 | (큰 차 아니므로 단순 sanity) |
| NFR-LOG-FE-02 (회귀 0) | T4 | lint/typecheck/test/build 4건 |
| NFR-LOG-FE-03 (DX 1가지 출력) | T2 (ADR D2-a) | dev 가드 + allow list |
| NFR-LOG-FE-04 (ADR 가독성) | T2 자체 작성 시 페르소나 명시 | 5분 reread 기준 |
| NFR-LOG-FE-05 (CI 시간) | T4 cache hit / cold start 측정 | NFR 목표 안 검증 |
| EC-1 (test 파일) | T1 (D3-a) + T2 (ADR D3) | overrides 없음 |
| EC-2 (외부 라이브 console) | T1 (사용자 코드 외 무관) | spec 본문 한 줄 |
| EC-3 (apps/web/scripts) | spec 본문 (현재 없음) | T3 path filter 무관 |
| EC-4 (eslint-disable 우회) | spec 본문 + PR review | (정책상 허용, 사유 확인) |
| EC-5 (error boundary) | T2 (ADR Consequences) | console.error allow 통과 |
| EC-6 (import.meta.env.DEV 조합) | T2 (ADR D2-a) | dev 가드 + allow |
| EC-7 (cold start) | T4 측정 | NFR-LOG-FE-05 |
| EC-8 (branch protection) | spec §9 비스코프 (Maxi 직접) | T 매핑 없음 (의도적) |
| 완료 기준 #1 (eslint config) | T1 | |
| 완료 기준 #2 (cleanup) | T2 (Consequences) | D1-a 정책상 통과 |
| 완료 기준 #3 (lint 통과) | T4 | |
| 완료 기준 #4 (typecheck/test/build) | T4 | |
| 완료 기준 #5 (ADR) | T2 | |
| 완료 기준 #6 (workflow yml) | T3 | |
| 완료 기준 #7 (GH Actions 실행) | T4 | `gh pr checks` |
| 완료 기준 #8 (PR 라벨) | (workflow 외 PR 메타) | 게이트 2 직전 부착 |

placeholder scan. TBD / TODO 없음. ✓
type consistency. D1-a / D2-a / D3-a / D4-a / D5-c 일관 ✓ / 파일 경로 일관 ✓.

## 리뷰 결과 (← /bts-review-plan 채움)
