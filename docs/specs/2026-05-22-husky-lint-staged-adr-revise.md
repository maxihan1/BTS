<!-- chore. apps/web husky + lint-staged 도입 + frontend-logging-policy ADR §Consequences 정정 (PR #12 후속) -->

# husky + lint-staged 도입 + frontend-logging-policy ADR §Consequences 정정 — 스펙

> slug. `husky-lint-staged-adr-revise`
> type. `chore`
> agent. `frontend-engineer`
> 트리거. PR #12 (`docs/decisions/2026-05-22-frontend-logging-policy.md`) §Consequences 의 잘못된 전제 발견. **GitHub free tier private repo 에서는 Branch Protection 및 Repository Rulesets 모두 차단** (`403 Upgrade to GitHub Pro or make this repository public`). server-side 머지 차단 ADR 전제 불성립.
> 도메인 정리. plan §도메인 정리 참고 (BC 영향 0, frontend tooling + ADR 정정).

## 1. 배경

- **NEVER-15** = `DEVELOPMENT.md §1 절대 규칙 #15` (`console.log` / `println` 금지). 본 PR 는 PR #12 가 frontend (apps/web) 에 도입한 NEVER-15 강제 룰 (`no-console`) 을 **client-side 에서 추가로 강제**.
- PR #12 가 도입한 `apps/web/eslint.config.js` 의 `no-console` 룰은 두 경로로 강제 가능.
  - **server-side** (CI fail check + branch protection required check). PR #12 가 `frontend-ci.yml` workflow 로 lint 자동 트리거 도입. ADR 가 머지 직후 Maxi 가 GitHub Settings → Required check 등록 후속 액션 명시.
  - **client-side** (pre-commit hook). PR #12 비스코프 (`docs/decisions/2026-05-22-frontend-logging-policy.md` 본문 41행. "본 PR 머지 후 별도 후속 PR 도입 가능").
- **PR #12 머지 직후 검증 시점에 발견**. BTS repo (`maxihan1/BTS`) 는 private + free tier. `gh api repos/maxihan1/BTS/branches/main/protection` 과 `gh api repos/maxihan1/BTS/rulesets` 모두 `403 Upgrade to GitHub Pro or make this repository public`. 즉 ADR §Consequences 의 server-side 머지 차단 전제 자체가 불성립.
- 본 PR 는 세 가지를 함께 처리.
  1. **client-side 강제 도입** — husky (pre-commit Git hook 매니저) + lint-staged (staged 파일만 lint) 추가. `git commit` 시점에 staged frontend 파일에 lint 자동 실행. 위반 시 commit 차단.
  2. **ADR §Consequences 정정** — server-side 머지 차단 전제 무력화 명시 + client-side mitigation (husky + lint-staged) 을 1차 방어선으로 재정의 + GitHub Actions CI 가 2차 (PR check 적색 표시만, 차단 안 됨).
  3. **미래 정정 트리거 추가** — GitHub Pro 도입 시 또는 repo public 전환 시 server-side protection 재시도.

## 2. 사용자 시나리오 (Given-When-Then)

### S1. pre-commit 차단 (golden path)

> **Given** frontend 개발자 (Claude Code subagent / Maxi 본인) 가 `apps/web/src/foo.ts` 에 `console.log("debug")` 줄을 추가하고 `git add apps/web/src/foo.ts`.
>
> **When** `git commit -m "..."` 실행.
>
> **Then** husky pre-commit hook 가 lint-staged 호출 → staged `apps/web/src/foo.ts` 만 `pnpm exec eslint --max-warnings 0` 실행 → no-console 위반 발견 → commit 차단. 에러 메시지에 위반 파일 + 라인 + 룰 명 (`no-console`) 표시.

### S2. lint 위반 없는 정상 커밋 통과

> **Given** frontend 개발자가 `apps/web/src/foo.ts` 에 정상 코드만 작성하고 staged.
>
> **When** `git commit -m "..."` 실행.
>
> **Then** husky pre-commit → lint-staged → eslint 통과 → commit 정상 완료. 사용자 체감 지연 ≤ NFR-HUSKY-02 목표.

### S3. staged 외 파일은 검사 안 함

> **Given** frontend 개발자가 `apps/web/src/foo.ts` 만 staged. `apps/web/src/bar.ts` 에는 NEVER-15 위반 (`console.log`) 이 미리 존재 (unstaged).
>
> **When** `git commit -m "..."` 실행.
>
> **Then** lint-staged 가 `bar.ts` 는 검사 안 함 (staged 가 아니므로). commit 통과. `bar.ts` 의 위반은 다음 push 시 CI 또는 별도 lint 호출에서 발견되도록 위임.

### S4. backend / 문서 영역 staged 시 frontend lint 미실행

> **Given** Maxi 가 `docs/decisions/foo.md` 와 `backend/modules/.../Bar.kt` 만 staged.
>
> **When** `git commit -m "..."` 실행.
>
> **Then** lint-staged 가 frontend lint 룰 (apps/web 영역) 매칭하지 않음 → 빠르게 통과. backend / 문서 영역은 본 PR 비스코프.

### S5. `--no-verify` 우회

> **Given** Maxi 가 의도적으로 hook 을 우회하고 싶을 때 (예. WIP 커밋 임시 저장).
>
> **When** `git commit -m "..." --no-verify` 실행.
>
> **Then** husky hook 미실행 → commit 통과. 본 정책상 허용 (hook 은 실수성 위반 차단용, 의도적 우회는 PR review / CI 적색 표시로 잡힘).

### S6. ADR §Consequences 정정 (문서 변경 시나리오)

> **Given** `docs/decisions/2026-05-22-frontend-logging-policy.md` 의 §Consequences 본문이 server-side 머지 차단 전제로 작성된 상태.
>
> **When** 본 PR 가 ADR §Consequences 를 정정 (client-side mitigation 우선, server-side 는 GitHub Pro 도입 시점 trigger).
>
> **Then** ADR 본문에 (1) free tier private repo 의 protection 차단 명시 + (2) client-side mitigation (husky + lint-staged) 1차 방어선 + (3) GH Actions CI 2차 (적색 표시만, 차단 안 됨) + (4) 미래 정정 트리거 추가 (GitHub Pro 도입 또는 public 전환).

## 3. 기능 요구사항 (FR)

| ID | 요구사항 | 검증 |
|---|---|---|
| **FR-HUSKY-01** | repo root 에 husky 설치 + `.husky/` 디렉토리 생성 + `.husky/pre-commit` git tracked (다른 worktree / 신규 clone 적용 보장) | `ls .husky/pre-commit` 존재. `cat .husky/pre-commit` 가 lint-staged 호출. `git ls-files .husky/pre-commit` 결과 1줄 (tracked 확인). `.husky/_/` (husky v9 shim 디렉토리) 는 `.gitignore` 등록. |
| **FR-HUSKY-02** | repo root 또는 apps/web 에 lint-staged 설치 + 설정 | `cat .lintstagedrc.json` 또는 `package.json` 의 `lint-staged` 필드 존재. apps/web 영역 패턴 + eslint 명령 매핑. |
| **FR-HUSKY-03** | apps/web staged `.ts` / `.tsx` 파일에 eslint 실행 | S1 시나리오의 위반이 commit 차단. S2 의 정상 커밋이 통과. |
| **FR-HUSKY-04** | 본 PR 가 도입한 husky 인프라가 `pnpm install` 후 자동 활성화 + idempotent + workspace 별 `prepare` 와 충돌 없음 | root `package.json` 의 `prepare` 스크립트가 `husky` 호출 (v9 단일 명령). 신규 clone 후 첫 `pnpm install` 시 hook 자동 등록. 이미 활성화된 상태에서 재실행 시 정상 idempotent (husky v9 default). apps/web `package.json` 에 `prepare` script 없음 (현재 미존재) → 충돌 없음. |
| **FR-HUSKY-05** | ADR §Consequences 정정 — server-side 차단 전제 무효화 + client-side mitigation 1차 방어선 명시 + 미래 정정 트리거 추가 | `docs/decisions/2026-05-22-frontend-logging-policy.md` §Consequences 본문에 (1) free tier private repo 차단 사실 + (2) 1차 husky / 2차 GH Actions CI 분리 + (3) "Maxi 후속 액션" 단락 정정 (GitHub Pro 또는 public 전환 시점만 등록) + (4) 미래 정정 트리거 §추가 (GitHub Pro 도입 / public 전환) |
| **FR-HUSKY-06** | dev 가이드 — `--no-verify` 우회 가능 + 의도된 우회는 PR review 에서 사유 확인 | ADR §Consequences 본문 또는 별도 dev guide 1줄 명시. |
| **FR-HUSKY-07** | CONTRIBUTING.md 업데이트 — 신규 clone / 신규 worktree 후 `pnpm install` 절차 안내 (sanity check G8) | `grep -n "husky\|pnpm install" CONTRIBUTING.md` 결과 ≥ 1줄. `pnpm install` 이 husky 활성화 단계임을 한 줄 명시 + 신규 clone 후 hook 작동 확인 절차. |

## 4. 비기능 요구사항 (NFR)

| ID | 요구사항 | 목표값 |
|---|---|---|
| **NFR-HUSKY-01** | pre-commit hook 추가 후 정상 커밋 (5 파일 이하 staged) 의 체감 지연 | ≤ 2초 (M1 MacBook 기준). lint-staged 가 staged 파일만 검사 + eslint 캐시 (`--cache`) 활용. |
| **NFR-HUSKY-02** | 위반 차단 (1 파일 staged) 의 출력 명확성 | 위반 파일 경로 + 라인 + 룰 명 + 에러 메시지가 사용자에게 5초 안에 보임. |
| **NFR-HUSKY-03** | 기존 `pnpm install` 워크플로우 영향 | 추가 install 시간 ≤ 5초 (M1 + warm cache). |
| **NFR-HUSKY-04** | ADR 가독성 정정 후도 유지 | 페르소나 = Maxi 본인. 정정 후 ADR 본문 분량 ≤ 2배. 6개월 후 재독 시 5분 내 결정 사유 이해. |
| **NFR-HUSKY-05** | `apps/web` 외 staged 파일만 있는 commit 의 lint-staged 통과 시간 | ≤ 500ms (lint-staged 가 매칭 패턴 미일치 → 빠른 skip). |

## 5. 엣지 케이스

| ID | 케이스 | 처리 방침 |
|---|---|---|
| **EC-1** | 신규 clone 후 첫 `pnpm install` 까지 hook 미설치 — 그 사이 커밋은 차단 안 됨 | 정책상 허용. `prepare` script 가 install 시점에 hook 설치. README 또는 ADR 에 "clone 후 첫 install 필수" 1줄 명시. |
| **EC-2** | CI 환경 (`CI=true`) 에서 husky 가 hook 설치 실패 | husky v9 는 환경 변수 `CI=true` 일 때 자동 skip (정상 동작). GitHub Actions runner 는 `CI=true` 와 `GITHUB_ACTIONS=true` 둘 다 default 주입 (`https://docs.github.com/en/actions/learn-github-actions/variables`). frontend-ci.yml 의 `pnpm install` 시 husky prepare 가 silent skip — 본 PR T4 검증 단계에서 GH Actions run log 의 `prepare` 단계 출력 확인. |
| **EC-3** | Git 버전 ≥ 2.9 미만 (core.hooksPath 미지원) | BTS 표준은 zsh + 최신 Git. CI/CD 도 GitHub-hosted runner 의 최신 Git. 본 PR 비스코프. |
| **EC-4** | lint-staged 가 ESLint flat config 미인식 | ESLint 9.x + flat config 는 lint-staged 가 자동 인식 (ESLint CLI 가 root 의 `eslint.config.js` 탐지). `pnpm exec eslint --max-warnings 0` 호출 시 정상 동작 확인 필요 (T4 검증). |
| **EC-5** | monorepo 의 apps/web 외 영역 (backend / docs / scripts) 만 staged 시 lint-staged 가 frontend 룰 매칭 | 매칭 패턴 `apps/web/**/*.{ts,tsx,js,jsx}` 로 명시적 prefix → 매칭 안 함 → skip. S4 시나리오. |
| **EC-6** | husky pre-commit 가 lint-staged 호출 시 stdin 미지원 환경 (예. 일부 GUI Git 도구) | husky v9 는 stdin 의존 없음 (script 호출만). GUI Git 도구도 `core.hooksPath` 표준 따르면 정상 동작. |
| **EC-7** | `eslint --cache` 가 stale cache 로 false negative 발생 위험 | lint-staged 가 staged 파일만 전달 + `--cache` 사용 시 staged 파일은 항상 dirty (cache miss) → 검사 보장. cache 는 unstaged 영역의 성능 가속만. |
| **EC-8** | `prepare` script 가 `pnpm install --frozen-lockfile` (CI) 에서 husky 명령 실패 | husky v9 의 `CI=true` 환경 skip (EC-2). 또는 `prepare` script 가 `husky || true` 패턴으로 graceful fallback. |
| **EC-9** | 향후 `apps/admin/`, `packages/ui/` 등 frontend 영역 추가 시 lint-staged 매칭 패턴 갱신 누락 | ADR §Consequences 의 "path filter docs 미포함 의도" 와 동일 패턴. 영역 확장 PR 시 lint-staged 패턴도 함께 갱신 필요 명시. |
| **EC-10** | BTS 의 `worktree per 작업` 패턴 환경 — `.worktrees/<slug>/` 각각이 별도 working tree | `.git/` 는 monorepo root 1개 → `.git/hooks/` 도 1개 → git config `core.hooksPath` 가 root `.husky/` 가리킴. 모든 worktree 가 같은 hook 공유. 신규 worktree 생성 후 별도 `pnpm install` 불필요. T4 검증 — `.worktrees/husky-lint-staged-adr-revise/` 안에서 fixture commit 시도 시 hook 정상 작동 확인. |
| **EC-11** | `.husky/pre-commit` 의 executable bit 처리 (T7 실측 후 정정, 2026-05-22 PR #15) | **husky v9 의 정상 동작 — `.husky/_/pre-commit` shim 만 100755, 사용자의 `.husky/pre-commit` 본체는 100644**. `core.hooksPath=.husky/_` 가 shim 가리킴 → shim 이 `.husky/pre-commit` 의 sh content 를 읽어서 실행 (본체 exec bit 불필요). T7 실측에서 husky v9 가 `prepare` 시점에 본체 mode 를 자동으로 100644 로 reset 함을 확인. 본 PR T1 시점에 `git update-index --chmod=+x` 100755 적용했으나 `pnpm install` 후 100644 로 정상 변경됨 (정합). git index 의 100755 강제는 불필요 — 향후 회귀 검증 기준은 본체 100644 + shim 100755. |

## 6. 제약 조건

| ID | 제약 | 사유 |
|---|---|---|
| **C-1** | 본 정책은 `apps/web` 에만 적용 | backend (`backend/**/*.kt`) 는 ktlint / detekt 가 Gradle 단계에서 별도 검증. PR 분리. |
| **C-2** | husky 는 root install (monorepo 전체 .git hook 공유) | `.git/` 는 monorepo 1개 → husky 도 root 1개. apps/web 별 install 시 hook 중복/충돌. |
| **C-3** | lint-staged 도 root install 추천 (향후 backend Gradle hook 도 같은 lint-staged 패턴 확장 가능) | 일관성 + 후속 확장 용이. 단 apps/web install 도 기능상 OK (D1 결정). |
| **C-4** | `--no-verify` 우회 허용 | client-side hook 의 본질적 한계. 의도적 우회는 PR review / CI 적색 표시로 발견. ADR 본문 1줄 명시. |
| **C-5** | ADR 정정 시 기존 §Status (Accepted 2026-05-22) 는 유지. §Consequences 만 정정. 정정 사유는 별도 단락 (§정정 이력) 으로 추가 | ADR 표준 — Status 변경은 의사결정 자체 무효화 시에만. 본 PR 는 §Consequences 의 implementation 가정만 정정. |
| **C-6** | 기존 위반 cleanup 없음 | scope 최소화. lint 룰은 PR #12 가 이미 활성화. 본 PR 는 hook 추가 + ADR 정정만. |

## 7. 결정 사항 (Decisions) — Maxi 게이트 1 검토 대상

### D1. husky / lint-staged install 위치

| 옵션 | 위치 | 트레이드 오프 |
|---|---|---|
| **D1-a** | root + root | husky / lint-staged 모두 root `package.json`. monorepo 전체 hook 공유. 후속 backend 영역 추가 시 lint-staged 패턴 한 곳 갱신. (**추천**) |
| D1-b | root + apps/web | husky 만 root, lint-staged 는 apps/web. 영역별 lint-staged 분리 가능하지만 hook script 가 `pnpm --filter @bts/web exec lint-staged` 호출 — 한 단계 더. backend 확장 시 또 추가 분기. |
| D1-c | apps/web + apps/web | 가장 격리. 그러나 husky 가 `.git/` 위치 (monorepo root) 와 다른 디렉토리에서 init 시 path 설정 추가 필요. |

추천 **D1-a**. monorepo 가 backend (Kotlin/Gradle) + frontend (TS/pnpm) + docs / scripts 혼재. root 단일 hook 이 자연스러움. lint-staged 의 매칭 패턴이 영역별 명령 분기 (`apps/web/**/*.{ts,tsx}` → eslint, `backend/**/*.kt` → ktlintCheck 등) 를 지원 → 후속 backend 확장 시 같은 root config 에서 분기 추가 가능.

### D2. lint-staged 가 호출할 명령 형태

| 옵션 | 명령 | 트레이드 오프 |
|---|---|---|
| **D2-a** | `pnpm --filter @bts/web exec eslint --max-warnings 0 --cache` | apps/web workspace 에서 eslint 실행. PR #12 의 lint script (`eslint src`) 와 동일 룰. `--cache` 로 staged 외 파일 가속. `--max-warnings 0` 으로 warn 도 차단. (**추천**) |
| D2-b | `pnpm --filter @bts/web run lint` | npm script 호출. 단순. 그러나 staged 파일 path 를 명령에 전달할 수 없음 (script 가 `eslint src` 고정 → 전체 src 검사) → lint-staged 의 의미 (staged 만 검사) 무효화 → 느림. |
| D2-c | `eslint --max-warnings 0 --cache` (workspace filter 없이) | root 에서 직접 호출. monorepo 의 다른 영역 (backend / scripts) 에서도 eslint 가 작동하면 호출 가능. 그러나 BTS root 에 eslint 설치 안 됨 (apps/web 만). path resolution 실패 가능. |

추천 **D2-a**. lint-staged 가 staged 파일 path 를 D2-a 명령 끝에 자동 append → eslint 가 staged 파일만 검사. `--cache` 는 ESLint 의 cache 파일 (`.eslintcache`) 사용 — staged 는 항상 miss, unstaged 는 hit (EC-7 참고).

### D3. ADR 정정 방식 — §Consequences 의 잘못된 전제 처리

| 옵션 | 정정 형태 | 트레이드 오프 |
|---|---|---|
| **D3-a** | §Consequences 의 잘못된 단락만 정정 + §정정 이력 단락 추가 | 외과적. 기존 ADR 의 D1~D5 결정 + 미래 정정 트리거 3건 모두 유지. §Consequences 본문에서 server-side 차단 전제 단락만 (1) 사실 발견 명시 + (2) client-side mitigation 1차 방어선 + (3) GH Actions CI 2차 (적색 표시만) + (4) 미래 트리거 추가. (**추천**) |
| D3-b | ADR 의 Status 를 `Superseded` 로 바꾸고 신규 ADR 작성 | 큰 변경. 본 PR 는 ADR 의 의사결정 자체를 무효화하지 않음 (`no-console` 룰 / `allow: ['warn', 'error']` / Pino 보류 / D5 CI 같은 결정 그대로). §Consequences 의 implementation 가정만 잘못. Superseded 는 과잉. |
| D3-c | §Consequences 만 통째로 rewrite. 정정 이력 명시 안 함 | 가독성 깨끗. 그러나 추후 ADR 변경 이력 추적 불가. learnings 와 단절. |

추천 **D3-a**. ADR §Consequences 의 잘못된 단락 (라인 41 "pre-commit hook 부재" + 라인 43~45 "🔔 Maxi 후속 액션") 만 정정 + §정정 이력 단락 신설. 기존 결정 본문 유지 + 변경 이력 추적 가능.

**§정정 이력 형식** (sanity check G2, Maxi 확정 2026-05-22 — BTS ADR 최초 정정 사례 표준). §Consequences 끝에 `### 정정 이력` 서브섹션 + 표.

```
### 정정 이력

| 날짜 | PR | 사유 | 정정 전 | 정정 후 |
|---|---|---|---|---|
| 2026-05-22 | #N | GitHub free tier private repo 의 branch protection 차단 발견 (`gh api ... → 403 Upgrade to GitHub Pro or make this repository public`). server-side 머지 차단 전제 불성립. | "Maxi 후속 액션. GitHub Settings → ... Required check `frontend-ci / lint` 추가" + "silent failure 위험" | client-side mitigation (husky + lint-staged) 1차 방어선 + GH Actions CI 2차 (PR check 적색 표시만, 차단 안 됨) + 미래 정정 트리거 추가 (GitHub Pro 도입 / public 전환 시점) |
```

향후 ADR 정정 시 같은 표에 줄 추가 (누적). 다중 정정 추적 가능.

### D4. TDD 적용 방식 — E2E TDD 변형 (PR #12 패턴)

PR #12 가 확립한 패턴. eslint config / workflow yml / ADR 같은 인프라/문서 task 는 RED phase (실패 테스트 commit) 명시적 생략 OK. 대신 fixture 임시 작성 → lint fail → 제거 패턴이 RED→GREEN 등동 검증력.

| 옵션 | TDD 변형 | 트레이드 오프 |
|---|---|---|
| **D4-a** | E2E TDD 변형 적용 — 인프라 task (husky 설치, hook script, lint-staged 설정) 은 fixture 검증 패턴. ADR 정정 task 는 grep 기반 검증 (정정 후 문구 grep 통과). | PR #12 와 동일 패턴. plan §Plan 머리말에 명시. learning #1 (E2E TDD 변형이 인프라/문서 task 에 자연스럽게 적용) 누적. (**추천**) |
| D4-b | 표준 TDD (RED phase 명시 commit 필수) | RED → "husky 미설치 시 commit 차단 안 됨" 같은 negative test 가 부자연스러움. fixture 작성 → 차단 확인 → 제거가 자연. |

추천 **D4-a**. PR #12 의 검증된 패턴.

### D6. husky 버전 (sanity check G1, Maxi 확정 2026-05-22)

| 옵션 | 버전 | 트레이드 오프 |
|---|---|---|
| **D6-a** | husky v9 (최신) | `husky` 단일 명령. `husky install` deprecated. `.husky/_/` shim 디렉토리 자동 생성 + gitignore 권장. CI 환경 자동 skip (`CI=true`). v9 가 default 권장. (**추천**) |
| D6-b | husky v8 (legacy) | `husky install` 명령 사용. eco-system 호환성 (일부 온라인 자료 v8 기준). v9 대비 이점 없음. |
| D6-c | husky v9 + `.husky/_/` 수동 관리 | 임의 세분화. 특별한 이유 없음. v9 default 로 충분. |

추천 **D6-a**. v9 가 현 시점 (2026-05-22) default. 시작 프로젝트 (BTS) 는 최신 버전 채택이 자연.

**구현 세부** (D6-a).

- `package.json` devDependencies. `husky@^9.0.0`, `lint-staged@^17.0.0` (plan-eng-review A1 결정 — husky v9 와 동일 원칙으로 최신 major 채택, 2026-05-22 시점 최신 17.0.5).
- root `package.json` 의 `prepare` script. `"prepare": "husky"` (v9 의 install 명령).
- `.husky/_/` 디렉토리는 `.gitignore` 등록 (husky v9 의 sample/transition shim).
- `.husky/pre-commit` git tracked. mode 100644 (husky v9 정상 — shim 만 100755. T7 실측 후 EC-11 정정 적용, 2026-05-22 PR #15).

### D5. pre-commit hook 외 다른 hook (commit-msg / pre-push 등) 추가 여부

| 옵션 | 추가 hook | 트레이드 오프 |
|---|---|---|
| **D5-a** | pre-commit 만 도입. 다른 hook (commit-msg / pre-push 등) 후속 PR 위임. | 본 PR scope 와 일치. NEVER-15 강제만 목적. 후속 (commit-msg conventional commits 강제 등) 별도 PR 가 자연. (**추천**) |
| D5-b | pre-commit + commit-msg (conventional commits) 함께 도입 | 한 번에 두 가지. 그러나 본 PR 범위 폭증. conventional commits 정책 자체가 BTS 에 아직 미정 (history.md 의 머지 커밋 메시지 표준은 있지만 ADR 미존재). |
| D5-c | pre-commit + pre-push (전체 lint + typecheck + test) | pre-push 가 매번 1~2분 → DX 저하. CI 가 같은 작업 자동 처리하므로 중복. |

추천 **D5-a**. scope 최소화. 후속 (commit-msg / pre-push) 별도 결정.

## 8. 측정 가능한 완료 기준

- [ ] root `package.json` 에 husky `^9.0.0` / lint-staged 최신 설치 (devDependencies) + `prepare` script (`"husky"`) — D6-a
- [ ] root `.lintstagedrc.json` (또는 `package.json` 의 `lint-staged` 필드) 에 매칭 패턴 (`apps/web/**/*.{ts,tsx,js,jsx}`) + D2-a 명령
- [ ] `.husky/pre-commit` 생성 + `pnpm exec lint-staged` 호출 + git tracked (FR-HUSKY-01)
- [ ] `.husky/_/` 디렉토리는 `.gitignore` 등록 (D6-a 구현 세부)
- [ ] `.husky/pre-commit` mode 100644 (husky v9 정상 — shim `.husky/_/pre-commit` 만 100755. T7 실측 후 EC-11 정정 적용, PR #15)
- [ ] `pnpm install` 후 hook 자동 활성화 확인 (`git config core.hooksPath` 가 `.husky` 가리킴, FR-HUSKY-04)
- [ ] idempotency 검증 — `pnpm install` 재실행 시 정상 (FR-HUSKY-04, G5)
- [ ] S1 fixture 검증 — `apps/web/src/` 안 임시 파일에 `console.log` 작성 → `git add` → `git commit` 시도 → 차단 확인 → fixture 제거
- [ ] S2 검증 — 정상 staged 파일의 commit 정상 통과
- [ ] S4 검증 — backend / docs 영역만 staged 시 husky 통과 (frontend lint 미실행) 시간 NFR-HUSKY-05 범위
- [ ] EC-10 검증 — `.worktrees/husky-lint-staged-adr-revise/` worktree 안에서 fixture commit 시도 시 hook 정상 작동 (worktree per 작업 환경 호환)
- [ ] `docs/decisions/2026-05-22-frontend-logging-policy.md` §Consequences 정정 + §정정 이력 표 추가 (D3-a + G2)
- [ ] CONTRIBUTING.md 에 husky 활성화 안내 1줄 추가 (FR-HUSKY-07)
- [ ] `pnpm --filter @bts/web lint` `typecheck` `test` `build` 통과 (회귀 0)
- [ ] `frontend-ci.yml` workflow 재실행 시 통과 + cold start NFR-LOG-FE-05 (≤ 4분) 회귀 없음 + husky prepare 가 GH Actions 에서 silent skip 확인 (EC-2 + G6)
- [ ] PR 라벨 `learning:husky-mitigation` 부착 (post-merge sync-obsidian 시 자동 learning append)

## 9. 비스코프 (Out of Scope)

- **GitHub Pro 업그레이드 또는 repo public 전환**. Maxi 의 비용 + 정책 결정. ADR 미래 정정 트리거에 명문화만.
- **commit-msg hook (conventional commits 강제)**. 별도 PR / 정책. D5-b 옵션.
- **pre-push hook (lint + typecheck + test)**. CI 와 중복 + DX 저하. D5-c 옵션.
- **backend ktlint / detekt 의 pre-commit hook 통합**. backend Gradle 의 ktlintCheck / detekt 는 CI 단계 (별도 backend-ci.yml 미도입) 와 IDE 통합으로 충분. 후속 결정.
- **husky / lint-staged 의 ESLint 외 도구 (prettier / stylelint 등) 추가**. apps/web 현재 prettier 사용 중 (`devDependencies` 에 `prettier ^3.5.3`) 이지만 format 자동화는 별도 결정.
- **CI workflow 변경**. PR #12 의 `frontend-ci.yml` 그대로 유지. 본 PR 의 hook 추가는 CI 와 독립.
- **`apps/web/eslint.config.js` 의 룰 변경**. PR #12 결정 (`{ allow: ['warn', 'error'] }`) 유지.

## 10. Brainstorming Check

✅ 통과 (1회 iteration. brainstorming Phase B 에서 gap 8건 발견 → Maxi 결정 2건 (G1 husky v9 / G2 §정정 이력 형식 = §Consequences 끝 표) + spec inline 보강 6건 (G3 worktree per 작업 EC-10 / G4 .husky/pre-commit git tracked / G5 prepare idempotency / G6 GH Actions silent skip / G7 executable bit EC-11 / G8 CONTRIBUTING.md FR-HUSKY-07) 반영. office-hours skip 사유 정당 확인 (chore + 도구 도입 자명, forcing questions 추가 발견 가능성 낮음).

**브렌인스토밍 추가 검증** (가설 5건 결과).

1. office-hours skip 정당. ✅ 작업 범위 자명, builder mode forcing questions 추가 발견 가능성 낮음.
2. D1~D5 trade-off 누락 옵션 없음. ✅ D6 (husky 버전) 신설 누락 발견 → 보강.
3. monorepo + worktree per 작업 환경 hook 동작. ✅ EC-10 추가. `.git/` 단일 + `.husky/` 단일이라 모든 worktree 공유.
4. ADR §정정 이력 형식. ✅ BTS 최초 정정 사례 — §Consequences 끝 표 형식 표준 확립 (D3-a 보강).
5. husky 함정 사전 검증. ✅ executable bit (EC-11), CI silent skip (EC-2 보강), prepare idempotency (FR-HUSKY-04 보강) 3건 사전 보강.

**learning 후보** (별도 기록 — Maxi_wiki/BTS/learnings.md 머지 시 append).
- L1. classify-task 의 frontend tooling 오분류 패턴 누적 (PR #12 + 본 PR 둘 다 manual_override). frontend tooling 키워드 (`husky`, `lint-staged`, `eslint`, `prettier`, `vitest`, `playwright` 등) 보강 후보. 본 PR scope 외 — 후속 PR.

자세한 sanity check 결과는 본 PR conversation log 참고.
