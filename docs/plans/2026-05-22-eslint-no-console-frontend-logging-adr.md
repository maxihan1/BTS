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
2. 임시 위반 fixture 를 **prod + test 2 위치** 에 작성 → 차단 확인 (D3-a, test 파일도 동일 룰 검증).
   ```bash
   # 위치 1. prod 코드
   echo 'console.log("violation prod")' > apps/web/src/__lint-fixture.ts
   # 위치 2. test 파일 (D3-a, overrides 없음 검증)
   echo 'console.log("violation test")' > apps/web/src/__lint-fixture.test.ts
   pnpm --filter @bts/web lint
   # 예상. exit 1. **두 위치 모두 위반 메시지** "Unexpected console statement (no-console)" 출력.
   rm apps/web/src/__lint-fixture.ts apps/web/src/__lint-fixture.test.ts
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
- **NEVER-15** = `DEVELOPMENT.md §1 절대 규칙 #15` (`console.log` / `println` 사용 금지). 본 ADR 는 NEVER-15 를 frontend 영역에서 도구로 강제 + 정책 명문화.
- PR #11 (FR-AU-09 D6 로그인 폼 UI) code-reviewer agent CONCERNS-1. `apps/web/src/auth/useLogoutMutation.ts:21` 의 `console.error` 1줄 — 토큰/PII 미노출, NEVER-15 의 문자 (`console.log` / `println`) 와 정확 일치 안 함, 광의 해석 결과.
- 현재 prod 로그 수집 인프라 부재 (Pino / Sentry / Datadog / Loki 미도입). frontend 사용자 보고 incident root cause 추적은 사용자 브라우저 console 만 가능.
- BTS frontend 첫 logging 정책 ADR + 첫 GitHub Actions CI workflow 도입.

## Decision
### D1. eslint no-console 룰 = `{ allow: ['warn', 'error'] }`
- 사유. NEVER-15 의 문자 (`console.log` / `println`) 와 정확 일치. warn/error 는 사고 디버깅 + error boundary 의 자연스러운 출력. cleanup 부담 0.
- 대안. D1-b `['error']` 만 / D1-c 전체 차단. (spec §7 D1 참고)

### D2. dev 디버깅 = `import.meta.env.DEV` 가드 + allow list 호출
- 사유. Vite 의 prod 빌드 시 dead-code-elimination 으로 dev 가드 코드 제거. logger 없이 단순.

### D3. test 파일도 동일 룰 (overrides 없음)
- 사유. 현재 test 파일 안 console.* 호출 0건 (`grep` 결과). 완화 사유 없음. D1-a 와 동일 allow list 적용.

### D4. Pino / 외부 수집기 도입 보류
- 사유. 인프라 부재. 본 ADR 는 정책 명시 + 도구화만. 미래 정정 트리거 3건 (§미래 정정 트리거) 충족 시 즉시 재검토.

### D5. CI workflow 실행 항목 = lint + typecheck + test
- 사유. BTS 첫 GitHub Actions workflow (`.github/workflows/frontend-ci.yml`). PR #11 의 77개 vitest 회귀 가드 자동. build / E2E (Playwright) 는 후속 PR.
- **확장성**. 본 workflow 패턴은 후속 도입 시 복제 가능. naming `<영역>-ci` (예. `backend-ci`, `e2e-ci`) + path filter (`<영역>/**` + 의존성 + 자기 자신) + concurrency `cancel-in-progress` 패턴 일관 유지.
- **path filter 제한**. 현재 `apps/web/**` + `package.json` + `pnpm-lock.yaml` + 자기 자신. `docs/decisions/**` 미포함 (ADR 만 변경 PR 는 frontend CI 안 트리거, 의도된 동작). 향후 `apps/admin/`, `packages/ui/` 등 frontend 영역 추가 시 path filter 갱신 필요 — 미갱신 시 silent skip 위험.

## Consequences

### 코드 영향
- `useLogoutMutation.ts:21` 의 `console.error` 는 D1-a allow list 통과 → 별도 cleanup 액션 없음 (정책상 정상).
- React `error boundary` 의 `console.error` 도 동일 정책상 통과. prod 빌드에서 사용자 브라우저 console 에 stack trace 노출 — PII 없음 가정.

### 개발 가이드 (dev 시점)
- **dev 디버깅 시 `console.log` 사용 금지** → `import.meta.env.DEV && console.warn(...)` 패턴 권장.
- **dev 시점 즉시 검증**. 코드 작성 후 `pnpm --filter @bts/web lint` 실행 권장. VS Code 의 `dbaeumer.vscode-eslint` extension 가 ESLint flat config 를 자동 인식 → 작성 시점 즉시 빨간 줄 표시 (IDE 사용자). Claude Code subagent 는 dispatch 직전 lint 자체 실행.
- pre-commit hook (husky + lint-staged) 부재 (본 ADR 비스코프). 본 PR 머지 후 별도 후속 PR 도입 가능.

### 🔔 Maxi 후속 액션 (본 PR 머지 후 즉시, 1건)
- **GitHub Settings → Branches → Branch protection rules → main → Required status checks → `frontend-ci / lint` 추가 → Save changes**.
- 미설정 시 CI fail check 표시는 되나 머지 차단 안 됨 (silent failure). 본 ADR 의 "자동 차단" 의도 무력화 위험.

## 미래 정정 트리거
다음 중 1건 충족 시 본 ADR 재검토 + Pino / 외부 수집기 도입 결정.

1. **prod 사용자 100명 초과** — 1K BTS 의 10% 도달. 사용자 incident 발생 시 frontend 로그 추적 부재가 BLOCKER 가 되는 임계점. **단, 본 트리거는 사용자 수 측정 인프라 도입 시점부터 발효** (현재 Phase 0 진입 직전, prod 사용자 수 측정 인프라 부재). 측정 인프라 도입 PR 시 본 ADR 함께 갱신.
2. **첫 prod incident 발생 (frontend 원인 의심)** — 사용자가 보고한 incident (장애 / 오동작 / 사고) 중 root cause 가 **frontend 사용 중 발생한 것으로 의심**되는 사건이 1회 이상. 객관 기준 — 보고된 사용자 행위가 frontend 페이지 / 컴포넌트 / 클라이언트 사이드 로직 동작 중 발생, 그리고 재현 시도 시 console 로그만으로 root cause 식별 불가.
3. **외부 로그 수집기 도입 결정** — Sentry / Datadog / Loki 등이 다른 결정 (예. backend) 으로 들어오면 frontend 도 그 transport 에 붙어야 함. 도입 결정 시 본 ADR 즉시 재검토.

## 참조
- PR #11 (FR-AU-09 D6 로그인 폼 UI) CONCERNS-1.
- `DEVELOPMENT.md §1 절대 규칙 #15` (= NEVER-15).
- spec. `docs/specs/2026-05-22-eslint-no-console-frontend-logging-adr.md`.
- plan. `docs/plans/2026-05-22-eslint-no-console-frontend-logging-adr.md`.
- plan-eng-review + plan-devex-review 결과. plan §리뷰 결과.
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
      - name: Checkout
        uses: actions/checkout@v4
      - name: Setup pnpm
        uses: pnpm/action-setup@v4
        with:
          run_install: false
      - name: Setup Node 22 (with pnpm cache)
        uses: actions/setup-node@v4
        with:
          node-version: 22
          cache: pnpm
      - name: Install dependencies (frozen lockfile)
        run: pnpm install --frozen-lockfile
      - name: Lint (no-console + react-hooks + tseslint)
        run: pnpm --filter @bts/web lint
      - name: Typecheck (TS strict)
        run: pnpm --filter @bts/web typecheck
      - name: Test (vitest unit + msw)
        run: pnpm --filter @bts/web test
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

## 리뷰 결과

### plan-eng-review (2026-05-22)

**전체 판정. ✅ PASS (BLOCKER 0건, 주의 5건, 정보 2건, 권장 보완 1건)**

#### Step 0. Scope Challenge

- 복잡도 임계 (8 file 또는 2 신규 class/service) 미만 — 4 task / 3 신규 file + 1 수정.
- scope reduction 옵션 (GH Actions 별도 PR 분리) 은 sanity check G1 단계에서 이미 Maxi 검토 완료, 본 PR 포함 결정.
- 결과. **scope 적정, reduction 불필요.**

#### Section 1. Architecture

| ID | 분류 | 항목 |
|---|---|---|
| A-1 | ✅ PASS | D5-c CI workflow 설계 — Node 22 / pnpm action-setup@v4 / cache pnpm / cancel-in-progress / path filter 모두 GitHub Actions best practice (2026-05 기준). timeout-minutes 10 분 여유 적정 |
| A-2 | ✅ PASS | Wave 1 3-병렬 dispatch — T1/T2/T3 file 겹침 0 (서로 다른 디렉토리), 코드 의존성 0 (구현 시 spec 보고 작성), eslint --fix / pnpm install 같은 자동 정리 도구 사용 없음. PR #7 "가장 빠른 케이스" 패턴 일치 |
| A-3 | ⚠️ 주의 | workflow yml path filter 에 `docs/decisions/` 미포함 — ADR 만 변경 PR 는 frontend CI 안 트리거. 의도된 동작이지만 plan 본문 또는 ADR Consequences 한 줄 명시 권장 |
| A-4 | ⚠️ 주의 | 향후 `apps/web/` 외 frontend 영역 (예. `apps/admin/`, `packages/ui/`) 추가 시 path filter 갱신 필요. silent skip 위험 — 본 plan 의 D5 본문 또는 ADR 본문에 "frontend 영역 확장 시 path filter 갱신" 한 줄 권장 |

#### Section 2. Code Quality

- 0 항목. eslint config 한 줄 추가 / ADR 양식 일관 / workflow yml 표준 패턴. DRY / 에러 핸들링 / over-under engineering 모두 적정.

#### Section 3. Test (E2E TDD 변형 정당성 + 누락 검증)

| ID | 분류 | 항목 |
|---|---|---|
| T-1 | ✅ PASS | E2E TDD 변형 정당성 — T1 fixture 임시 작성 → lint fail 확인 → 제거 패턴은 정상 RED→GREEN 과 동등 검증력. T2 ADR grep / T3 yaml syntax + 실제 push 후 GH Actions trigger 결과 모두 충분 |
| T-2 | 권장 보완 | **D3-a (test 파일도 동일 룰) 검증 누락**. T1 의 fixture 검증을 prod 코드 위반 1 케이스만 수행. test 파일 (`*.test.ts`) 안 console.log 도 차단되는지 확인 필요. → T1 검증 단계에 fixture 를 prod + test 2 위치에 작성하는 항목 추가 권장 |

#### Section 4. Performance

| ID | 분류 | 항목 |
|---|---|---|
| P-1 | ✅ PASS | NFR-LOG-FE-05 시간 목표 적정 — cache hit ≈ 80s ≤ 90s / cold start ≈ 125~185s ≤ 4분. lint/typecheck/test 합산 추정 ≈ 50s 안. D5-c scope 와 일치 |
| P-2 | ℹ️ 정보 | cold start dominant 요인이 pnpm install fresh — 현재 frontend 의존성 ~50건 추정. 향후 100건 초과 시 4분 NFR 위협 가능. ADR D4-a 트리거 4번째 후보로 "CI cold start 4분 NFR 위반 시" 검토 가능 (본 PR 비스코프) |

#### Section 5. ADR D4-a 트리거 운영 가능성

| ID | 분류 | 항목 |
|---|---|---|
| D-1 | ⚠️ 주의 | 트리거 1 "prod 사용자 100명 초과" — BTS 가 Phase 0 진입 직전 → prod 사용자 수 측정 인프라 부재. ADR 본문에 "측정 인프라 도입 시점부터 발효" 한 줄 권장 |
| D-2 | ⚠️ 주의 | 트리거 2 "첫 prod incident (frontend 원인 의심)" — "frontend 원인 의심" 정의 주관적. "사용자 보고 incident 중 root cause 가 frontend 사용 중 발생으로 의심" 식으로 객관화 권장 |
| D-3 | ✅ PASS | 트리거 3 "외부 로그 수집기 도입 결정" — 다른 결정 (별도 ADR) 으로 발효, 측정 인프라 무관, 적합 |

#### Section 6. Spec coverage self-review 정합성

- plan 끝 표 (FR/NFR/EC/완료기준 27 행) 전수 검증 완료. 매핑 완전.
- ℹ️ 정보. 완료 기준 #8 (PR 라벨 `learning:logging-policy` 부착) 은 "workflow 외 PR 메타" 로 표시. 실제 부착은 게이트 2 직전 controller (메인 워크플로우) 가 처리. 본 plan task 매핑 무관.

#### Failure modes

- T1 fail. eslint config 변경 후 lint 위반 발견 → PR check fail → silent failure 아님. ✓
- T2 fail. ADR 본문 누락 — T2 검증 grep 으로 발견. silent failure 아님. ✓
- T3 fail. workflow yml syntax error → GH Actions UI "invalid workflow" 표시. silent failure 아님. ✓
- T4 fail. 회귀 발견 → PR 차단. silent failure 아님. ✓
- **critical gaps. 0건**.

#### Worktree parallelization

이미 plan §Plan 메타 에 명시. Wave 1 (T1/T2/T3 3-병렬, 파일 겹침 0) / Wave 2 (T4, depends-on [1,2,3]). ✓

#### NOT in scope

plan §9 비스코프 7건 명시 완료. ✓

#### What already exists

- `apps/web/eslint.config.js` (PR #11 도입) — 한 줄 추가 재사용.
- `docs/decisions/` 13건 — ADR 양식 일관 재사용.
- pnpm workspace + Vite + Vitest + Playwright (PR #11) — T4 검증 환경 재사용.
- `.github/workflows/` — 없음. 본 PR 가 신규 (의도).
- `.husky/` — 없음. 비스코프 (의도).

재구축 없음. ✓

#### 권장 보완 사항 (Maxi 게이트 1 검토 시 반영 가능)

본 review 의 발견 중 plan / spec / ADR 본문 보완 후보 4건. **모두 BLOCKER 아니라 Maxi 가 선택적 반영 결정**.

1. **A-3 + A-4**. workflow yml path filter 의 docs/decisions/ 미포함 + 향후 frontend 영역 확장 시 갱신 필요 → ADR Consequences 또는 plan §Plan 메타에 한 줄 명시.
2. **T-2**. T1 검증 단계의 fixture 를 prod + test 2 위치 작성으로 확장 → D3-a (test 파일도 동일 룰) 검증 완전성 보장.
3. **D-1**. ADR D4-a 트리거 1 "사용자 100명 초과" 에 "측정 인프라 도입 시점부터 발효" 한 줄 추가.
4. **D-2**. ADR D4-a 트리거 2 "첫 prod incident" 정의 객관화 — "사용자 보고 incident 중 root cause 가 frontend 사용 중 발생으로 의심" 식.

#### Completion Summary (plan-eng-review)

- Step 0. scope 적정 (수용).
- Architecture. 4 항목 (2 PASS + 2 주의).
- Code Quality. 0 항목.
- Test. 2 항목 (1 PASS + 1 권장 보완).
- Performance. 2 항목 (1 PASS + 1 정보).
- ADR D4-a 트리거. 3 항목 (1 PASS + 2 주의).
- Spec coverage. 정합 (1 정보).
- Failure modes. 0 critical gaps.
- NOT in scope / What already exists. 모두 명시.
- Parallelization. 2 lanes (3-병렬 + 1-순차).
- **BLOCKER 0건**.

---

### plan-devex-review (2026-05-22)

**전체 판정. ✅ PASS (BLOCKER 0건, 권장 보완 5건)**

#### 격식 적용 수준 (Step 0 명시적 결정)

본 plan 의 developer-facing surface 는 "외부 개발자 onboarding" 시나리오가 아니라 **Maxi 1인 + Claude Code subagent (다중 worktree) 의 내부 도구화**. 향후 1K 사용자 운영 시 추가 frontend / backend 개발자 합류 가능성은 있으나 현 시점 직접 사용자는 1인.

- **Step 0A 페르소나**. (a) Maxi 본인 (BTS 헌법 친숙) + (b) Claude Code subagent (CLI 환경, IDE 없음). 외부 onboarding 페르소나 무관.
- **Step 0B 엠퍼시 narrative**. Maxi 가 6개월 후 본 PR 의 결정 재독 시 / Claude subagent 가 wave 1 dispatch 시 plan 만으로 작업 가능한지 — 둘 다 본 plan 의 한국어 본문 + 양식 일관성으로 충족 (PR #6 ~ #11 의 plan 양식 재사용).
- **Step 0C 경쟁 benchmark**. internal tooling 이라 외부 경쟁 부재. skip.
- **Step 0D 마법의 순간**. CI 가 첫 PR check 트리거하는 순간 (T4 검증) = 마법의 순간 자체.
- **Step 0E 모드**. **DX POLISH** 적용 (touchpoint 다 짚기, scope 확장 안 함).
- **Step 0F~G 저니 / 혼란 로그**. 본 PR 의 직접 시나리오 = (1) 본 PR 머지 (2) 다음 PR 작성 시 console.log 실수 (3) CI fail → fix → 머지. 본 review 의 발견 항목으로 통합.
- **Pass 1~8 격식**. 본 plan scope 대비 overkill. 호출자 명시 5건 검증으로 압축 — 각 검증이 Pass 1 (Getting Started) / Pass 2 (API/CLI) / Pass 3 (Error Messages) / Pass 4 (Docs) / Pass 6 (Dev Environment) 의 핵심을 다룸.

#### 검증 1. CI 피드백 품질 (Pass 3 핵심)

- 현재 workflow yml 의 step 이 raw 명령 (`pnpm --filter @bts/web lint`) — GH Actions UI 의 step 이름이 그 명령 그대로 표시. 적합.
- fail 시 메시지 = ESLint / tsc / vitest stdout 의 `file:line:column` 표준. PR check UI raw log 만 확인 가능 → CI fail 추적 가능하지만 친숙도 약함.
- **권장 보완 #1**. workflow step 에 명시적 `name:` 추가. 예. `name: Lint (no-console + react-hooks)` / `name: Typecheck (TS strict)` / `name: Test (vitest unit)`. GH Actions UI 가독성 + 후속 PR 의 CI fail debugging UX 개선. plan T3 의 yml 양식에 반영.

#### 검증 2. 신규 console.log 작성 시 lint 에러 UX (Pass 1 핵심)

- pre-commit hook 부재 (비스코프) → 개발자 (Claude subagent / Maxi) 가 PR push 후 GH Actions wait (~1~3분) → fail → 수정 → push → wait. 1 cycle ≈ 5분 friction.
- dev 시점 lint 실행 가능 명령 = `pnpm --filter @bts/web lint`. spec / ADR 어디에도 dev 가이드 명시 없음.
- **권장 보완 #2**. ADR §Consequences 또는 plan §Plan 메타에 "dev 시점 즉시 검증. `pnpm --filter @bts/web lint` 실행 권장. VS Code eslint extension 자동 인식" 한 줄 명시. Claude subagent prompt 에도 dispatch 직전 lint 자체 검증 가이드 추가 가능 (본 PR 비스코프, 후속 SKILL 갱신).

#### 검증 3. ADR 가독성 (NFR-LOG-FE-04, Pass 4 핵심)

- ADR 양식 (Status / Context / Decision D1~D5 / Consequences / 트리거 / 참조) 격식 적합. ✓
- D1~D5 각각 "추천 + 대안 + 사유" 구조 → 6개월 후 재독 시 결정 사유 추적 가능. ✓
- **권장 보완 #3**. ADR §1 Context 의 "NEVER-15" 가 약어. 첫 등장 시 풀이 한 줄 권장. 예. "NEVER-15 = `DEVELOPMENT.md §1 절대 규칙 #15` (console.log / println 사용 금지)". 외부 frontend 개발자 (향후 합류) 가 ADR 단독으로 결정 사유 추적 가능.

#### 검증 4. workflow yml 향후 확장성 (Pass 6 핵심)

- naming. `frontend-ci` — 영역 명시 명확. 향후 `backend-ci`, `e2e-ci`, `infra-ci` 자연스러운 복제. ✓
- path filter 패턴. (`apps/web/**` + 의존성 + 자기 자신) — backend (예. `backend/**` + `build.gradle.kts` + `gradle/**` + 자기 자신) 와 동일 패턴 복제 가능. ✓
- concurrency group. 다른 workflow 와 독립. ✓
- **권장 보완 #4**. ADR §D5 본문에 "본 workflow 패턴은 backend-ci / e2e-ci 후속 도입 시 복제 가능. naming `<영역>-ci` + path filter + concurrency 패턴 일관 유지" 한 줄 명시. 향후 backend-ci 도입 PR 가 본 ADR 만 보고 복제 가능.

#### 검증 5. branch protection 누락의 Maxi onboarding 가이드 (Pass 1 핵심)

- spec §9 비스코프 + EC-8 에 명시되어 있음. plan-eng-review 의 ⚠️ 주의로도 발견 (D-1 측정 인프라와 다른 항목).
- 그러나 Maxi 가 머지 후 GitHub Settings 액션을 잊으면 CI 가 실제로는 "fail check 표시만 + 머지 차단 안 됨" 상태. 의도와 다른 silent failure.
- **권장 보완 #5**. PR #12 body 또는 ADR §Consequences 마지막에 별도 강조 섹션. 예.
  > **🔔 Maxi 후속 액션 (1건, 본 PR 머지 후 즉시)**.
  > GitHub Settings → Branches → Branch protection rules → main → Required status checks → `frontend-ci / lint` 추가 → Save changes.
  > 미설정 시 CI fail check 표시는 되나 머지 차단 안 됨 (silent failure).

#### Required Outputs (BTS 컨텍스트 적응)

- **Developer Persona Card**. Maxi 1인 + Claude Code subagent. 외부 onboarding 무관 (현 시점).
- **Empathy narrative**. plan §리뷰 결과 의 plan-eng-review + plan-devex-review 본문으로 갈음.
- **경쟁 benchmark**. internal tooling, skip.
- **마법의 순간**. T4 검증의 GH Actions trigger 자체.
- **저니 맵**. 1 단계 (PR push → CI trigger → check 결과 확인). 트리거 자체가 발견.
- **NOT in scope**. plan §9 비스코프 7건 (재인용 생략).
- **What already exists**. plan-eng-review 섹션 참조.

#### DX Scorecard (BTS 적응. 8 차원 중 적용 차원만)

```
+====================================================================+
|              DX PLAN REVIEW — SCORECARD (BTS 적응)                    |
+====================================================================+
| Dimension            | Score  | Note                                |
|----------------------|--------|--------------------------------------|
| Getting Started      |  8/10  | CI 가 첫 PR 자동 트리거 = 마법의 순간. dev 단계 lint 실행 가이드 부재 (-2) |
| API/CLI/SDK          |  N/A   | API 아님, lint 룰만                       |
| Error Messages       |  7/10  | stdout 표준 file:line:column. step name 명시 없음 (-3) |
| Documentation        |  8/10  | ADR 양식 격식 적합. NEVER-15 약어 풀이 부재 (-2) |
| Upgrade Path         |  9/10  | D4-a 트리거 3건 + 정정 시 ADR 재검토 절차 명시. 본 PR 자체 upgrade 없음 |
| Dev Environment      |  7/10  | `pnpm --filter @bts/web lint` 단순. dev 가이드 명문화 부재 (-3) |
| Community            |  N/A   | internal tooling, 외부 community 무관          |
| DX Measurement       |  8/10  | NFR-LOG-FE-05 (CI 시간) + ADR D4-a 트리거 발효 측정 명시. 측정 인프라 부재 (-2) |
+--------------------------------------------------------------------+
| TTHW                 | < 1 min (CI 자동 트리거, 사용자 액션 0)         |
| 경쟁 tier             | internal tooling N/A                       |
| 마법의 순간            | 디자인됨 (T4 trigger 자체)                    |
| Product Type         | internal CI infra + 정책 ADR                   |
| Mode                 | DX POLISH                                  |
| Overall DX           |  8/10                                      |
+====================================================================+
| DX 원칙 커버리지                                                       |
| Zero Friction      | covered (CI 자동, 사용자 액션 0)                    |
| Learn by Doing     | partial (dev lint 가이드 부재)                    |
| Fight Uncertainty  | partial (step name 명시 부재)                     |
| Opinionated + Escape Hatches | covered (D1~D5 추천 + 대안 명시)         |
| Code in Context    | covered (plan T1~T4 의 코드 + 검증 명령 완전)         |
| Magical Moments    | covered (T4 GH Actions trigger)              |
+====================================================================+
```

#### Completion Summary (plan-devex-review)

- Step 0. internal tooling 컨텍스트 → 격식 명시적 압축 (Step 0A~G + Pass 1~8 → 호출자 5건 검증).
- 검증 1 (CI 피드백). 권장 보완 1건 — step name 명시.
- 검증 2 (lint UX). 권장 보완 1건 — dev 가이드 명문화.
- 검증 3 (ADR 가독성). 권장 보완 1건 — NEVER-15 풀이.
- 검증 4 (workflow 확장성). 권장 보완 1건 — D5 본문에 복제 패턴 명시.
- 검증 5 (branch protection). 권장 보완 1건 — PR body 또는 ADR Consequences 에 후속 액션 강조.
- Overall DX. **8/10**. internal tooling 기준 좋은 점수.
- **BLOCKER 0건**.

---

### PR-codereview (2026-05-22) — bts-codereview 단계

**전체 판정. ✅ PASS (BLOCKER 0건, CONCERNS 0건, informational 3건)**

#### superpowers:code-reviewer agent

PASS. 절대 규칙 위반 0건 / learnings 회귀 0건 / ADR 정합 / workflow 안전.

검증 매트릭스 (모두 PASS).
- DEVELOPMENT.md §1 절대 규칙 18개. NEVER-15 (no-console 룰 자체가 강제) / NEVER-16 (PoC 단어 검출 0건) / NEVER-11~13 (any/!!/빈 catch 0건) / NEVER-17 (localStorage 토큰 무관). 나머지 14건 무관.
- learnings.md 회귀 가드. PoC 표현 (2026-05-21) / wave 병렬 dispatch ktlintFormat 부수 변경 (2026-05-20) / 기타 무관 항목 모두 회귀 0건.
- ADR 본문 정합성. 섹션 6 / D1~D5 5건 / 트리거 3건 / Maxi 후속 액션 / D5 확장성 / NEVER-15 풀이 / PR #11 참조 모두 grep 통과.
- workflow yml 안전성. secrets 0건 / action 버전 안정 / timeout / cancel-in-progress / frozen-lockfile 모두 적정.
- 실제 명령 재실행. lint/typecheck/test 77/build 모두 exit 0. apps/web/src 안 console.* 1건 (`useLogoutMutation.ts:21` D1-a 통과). test 파일 안 console.* 0건 (D3-a 정합).

#### /review (gstack) skill

PASS. CRITICAL 0 / CONCERNS 0 / informational 3건.

informational 3건 (머지 차단 아님).
1. Node.js 20 deprecation (2026-06-02 부터 Node 24 기본). 본 PR 무관, 별도 후속 PR 후보.
2. workflow yml path filter `docs/decisions/**` 미포함 — 의도된 동작. ADR Consequences 에 명문화됨. silent failure 가드 적정.
3. frontend 영역 (`apps/admin/`, `packages/ui/`) 확장 시 path filter 갱신 필요. ADR D5 확장성 섹션에 명시.

#### /plan-ceo-review

스킵 (type=chore, classify.type ∈ {auth, migration} 조건 미충족).

#### 종합

본 PR 의 구조적 이슈 0건. informational 3건 모두 의도된 동작 또는 후속 PR 후보. **머지 가능** — 게이트 2 진입 대기.

---

### bts-impl 실행 결과 (2026-05-22)

**전체 판정. ✅ PASS (Wave 1 3-병렬 + Wave 2 1-순차, 모두 verifier PASS)**

#### Wave 1 (T1+T2+T3 3-병렬 dispatch, 파일 겹침 0)

| Task | implementer | verifier | 커밋 |
|---|---|---|---|
| T1. eslint config (D1-a) | DONE — fixture (prod + test 2 위치) 검증 모두 lint fail, 회귀 0 | PASS | `857f960` |
| T2. ADR 신규 | DONE — 59줄, 6 grep 검증 모두 통과, 권장 보완 4건 본문 반영 | PASS | `eb656e4` |
| T3. frontend-ci.yml (BTS 첫 GH Actions) | DONE — 47줄, yaml 구조 + step name 명시, 권장 보완 #5 반영 | PASS | `6d4833c` |

#### Wave 2 (T4 검증, depends-on [1,2,3])

| 검증 항목 | 결과 |
|---|---|
| 로컬 lint | exit 0, 위반 0 (useLogoutMutation.ts:21 D1-a allow 통과) |
| 로컬 typecheck | exit 0 |
| 로컬 test | exit 0, **77 passed** (PR #11 baseline 동일) |
| 로컬 build | exit 0, 2099 modules transformed |
| GH Actions trigger | success — run id `26256139867` |
| **NFR-LOG-FE-05 cold start** | **59초 (목표 ≤ 4분, 75% 여유)** ✅ |
| 정보 항목 | actions/checkout / setup-node / pnpm-action-setup 의 Node.js 20 deprecation 경고 (2026-06-02 부터 Node 24 기본). 본 PR 무관, 후속 PR 후보 |

#### qa-engineer 호출

스킵 (type=chore, classify.type ∈ {feature, auth} 조건 미충족).

#### verification-before-completion

T4 안에서 이미 lint / typecheck / test / build 4건 + GH Actions 실제 trigger 모두 검증 완료. 별도 호출 스킵.

---

### 게이트 1 진입 준비

plan-eng-review (PASS, 권장 보완 4건) + plan-devex-review (PASS, 권장 보완 5건). **BLOCKER 0건, 진행 가능**.

권장 보완 사항 통합 (중복 제거 후 6건):

| # | 출처 | 보완 위치 | 내용 |
|---|---|---|---|
| 1 | eng A-3 + eng A-4 + devex 검증 4 | ADR §D5 본문 또는 §Consequences | workflow path filter 의 docs/decisions/ 미포함 + 향후 frontend 영역 확장 시 갱신 + backend-ci / e2e-ci 복제 패턴 일관 명시 |
| 2 | eng T-2 | plan T1 검증 단계 | fixture 를 prod + test 2 위치 작성으로 확장. D3-a 검증 완전성 |
| 3 | eng D-1 | ADR §미래 정정 트리거 1 | "측정 인프라 도입 시점부터 발효" 한 줄 추가 |
| 4 | eng D-2 | ADR §미래 정정 트리거 2 | "사용자 보고 incident 중 root cause 가 frontend 사용 중 발생으로 의심" 식 객관화 |
| 5 | devex 검증 1 | plan T3 workflow yml | step name 명시 (Lint / Typecheck / Test 각각) |
| 6 | devex 검증 2 + 3 + 5 | ADR §Consequences | dev 시점 lint 실행 가이드 + NEVER-15 풀이 + Maxi 후속 액션 (branch protection) 강조 |
