<!-- chore. apps/web frontend logging 정책 ADR (PR #11 CONCERNS-1 후속) -->
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
- **pre-commit hook (husky + lint-staged) 도입 완료** (후속 PR #15, 2026-05-22). root `package.json` 에 `husky@^9.0.0` + `lint-staged@^17.0.0` + `prepare` script 추가. `.husky/pre-commit` 가 `pnpm exec lint-staged` 호출 → staged `apps/web/**/*.{ts,tsx,js,jsx}` 만 eslint no-console 검증. 본 ADR 의 **client-side mitigation 1차 방어선**.

### ~~Maxi 후속 액션~~ (정정됨, 2026-05-22 — PR #15)

**본 ADR 의 server-side 머지 차단 전제는 불성립**. BTS repo (`maxihan1/BTS`) 는 GitHub free tier private repo. `gh api repos/maxihan1/BTS/branches/main/protection` 과 `gh api repos/maxihan1/BTS/rulesets` 모두 `403 Upgrade to GitHub Pro or make this repository public` 반환. GitHub Settings UI 에서도 동일하게 등록 불가. Branch protection / Repository Rulesets 모두 차단됨.

**재정의된 강제 분담**.
- **1차 방어선 (client-side, 본 ADR §정정 이력 PR #15 도입)**. husky + lint-staged 가 `git commit` 시점에 staged frontend 파일을 자동 검증 + 차단. 의도적 우회는 `git commit --no-verify` (PR review 단계에서 사유 확인).
- **2차 방어선 (server-side, GH Actions CI)**. PR 시 `frontend-ci / lint` workflow 가 fail check 빨강 표시. 머지 차단은 안 됨 (branch protection 미지원). 사용자/리뷰어가 적색 표시를 인지하고 머지 안 하는 책임.
- **3차 (사회적)**. PR review 단계에서 `--no-verify` 우회 commit 의 사유 확인.

자세한 사유와 정정 전후 비교. §정정 이력 참고.

## 미래 정정 트리거
다음 중 1건 충족 시 본 ADR 재검토 + Pino / 외부 수집기 도입 결정.

1. **prod 사용자 100명 초과** — 1K BTS 의 10% 도달. 사용자 incident 발생 시 frontend 로그 추적 부재가 BLOCKER 가 되는 임계점. **단, 본 트리거는 사용자 수 측정 인프라 도입 시점부터 발효** (현재 Phase 0 진입 직전, prod 사용자 수 측정 인프라 부재). 측정 인프라 도입 PR 시 본 ADR 함께 갱신.
2. **첫 prod incident 발생 (frontend 원인 의심)** — 사용자가 보고한 incident (장애 / 오동작 / 사고) 중 root cause 가 **frontend 사용 중 발생한 것으로 의심**되는 사건이 1회 이상. 객관 기준 — 보고된 사용자 행위가 frontend 페이지 / 컴포넌트 / 클라이언트 사이드 로직 동작 중 발생, 그리고 재현 시도 시 console 로그만으로 root cause 식별 불가.
3. **외부 로그 수집기 도입 결정** — Sentry / Datadog / Loki 등이 다른 결정 (예. backend) 으로 들어오면 frontend 도 그 transport 에 붙어야 함. 도입 결정 시 본 ADR 즉시 재검토.
4. **GitHub Pro 도입 또는 repo public 전환** — Branch Protection / Repository Rulesets 사용 가능 → server-side 머지 차단 재시도 가능. 본 ADR §Consequences 의 client-side / server-side 분담 재검토 + §정정 이력 추가.

### 정정 이력

| 날짜 | PR | 사유 | 정정 전 | 정정 후 |
|---|---|---|---|---|
| 2026-05-22 | #15 | GitHub free tier private repo 의 branch protection 차단 발견 (`gh api repos/maxihan1/BTS/branches/main/protection → 403 Upgrade to GitHub Pro or make this repository public`). server-side 머지 차단 전제 불성립. | "Maxi 후속 액션. GitHub Settings → Branches → Branch protection rules → main → Required status checks → `frontend-ci / lint` 추가 → Save changes" + "미설정 시 silent failure 위험" | client-side mitigation (husky + lint-staged) 1차 방어선 + GH Actions CI 2차 (PR check 적색 표시만, 차단 안 됨) + 미래 정정 트리거 4번째 신규 (GitHub Pro 도입 / public 전환 시점) |

## 참조
- PR #11 (FR-AU-09 D6 로그인 폼 UI) CONCERNS-1.
- `DEVELOPMENT.md §1 절대 규칙 #15` (= NEVER-15).
- spec. `docs/specs/2026-05-22-eslint-no-console-frontend-logging-adr.md`.
- plan. `docs/plans/2026-05-22-eslint-no-console-frontend-logging-adr.md`.
- plan-eng-review + plan-devex-review 결과. plan §리뷰 결과.
