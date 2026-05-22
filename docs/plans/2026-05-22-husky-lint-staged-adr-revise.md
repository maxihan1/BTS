# husky + lint-staged 도입 + frontend-logging-policy ADR §Consequences 정정

> slug: husky-lint-staged-adr-revise
> type: chore (manual_override from backend)
> agent: frontend-engineer
> 생성: 2026-05-22

## Brief

**사용자 원문**. apps/web 에 husky + lint-staged 도입으로 pre-commit lint 강제 + ADR 2026-05-22-frontend-logging-policy.md §Consequences 정정 (GitHub free tier private repo 의 branch protection 차단 발견 → client-side mitigation 으로 전환).

**classify 결과** (manual_override).

- from. `type=backend / agent=backend-engineer / slug=apps-web-husky-lint-staged-pre-commit-lint-adr-202` (50자컷 + 의미 잘림).
- to. `type=chore / agent=frontend-engineer / slug=husky-lint-staged-adr-revise`.
- 사유. apps/web tooling + ADR 문서 정정. PR #12 와 동일 오분류 패턴 (frontend tooling 작업을 classify 가 backend 로 오인). frontend-engineer 가 적절한 책임.

**컨텍스트** (왜 본 PR 가 필요한가).

PR #12 (commit `4dd7bd8`) 가 `apps/web/eslint.config.js` 에 `no-console` 룰 (`{ allow: ['warn', 'error'] }`) + `.github/workflows/frontend-ci.yml` (BTS 첫 GH Actions CI) + `docs/decisions/2026-05-22-frontend-logging-policy.md` (D1~D5 + 미래 정정 트리거 3건) 를 도입. ADR §Consequences 는 머지 후 Maxi 가 GitHub Settings → Branch protection → Required check 에 `frontend-ci / lint` 등록하면 server-side 머지 차단이 작동한다고 명시.

그런데 PR #12 머지 직후 검증 시점에 발견. **BTS repo (maxihan1/BTS) 는 private + free tier → GitHub Branch Protection (classic) + Repository Rulesets 모두 403 (`Upgrade to GitHub Pro or make this repository public`)**. 즉 Maxi 가 Settings UI 에서 등록하려 해도 GitHub 가 차단. ADR §Consequences 의 전제 자체가 불성립.

**해결 방향**. 두 가지 변경을 한 PR 로 묶는다.

1. **client-side mitigation 도입** — apps/web 에 husky (pre-commit Git hook 매니저) + lint-staged (staged 파일만 lint 실행) 추가. `git commit` 시점에 `pnpm exec eslint --max-warnings 0` 자동 실행. `--no-verify` 우회 가능하지만 실수성 위반은 차단.
2. **ADR §Consequences 정정** — "GitHub Pro 가 없으면 server-side 강제는 불가, client-side mitigation (husky + lint-staged) 이 1차 방어선, GH Actions CI 가 2차 (PR 단에 적색 표시만)" 로 재작성. 미래 정정 트리거 추가 (GitHub Pro 도입 시 또는 public 전환 시 server-side protection 재시도).

## 도메인 정리

**Fast-track 스킵** (type=chore, `.claude/skills/bts-domain/SKILL.md §Fast-track 스킵 조건` 적용).

- BC. 해당 없음. apps/web tooling (husky + lint-staged) + docs/decisions/ 문서 정정. 백엔드 7개 BC 어느 것에도 속하지 않음.
- 영향 엔티티. 없음. 도메인 모델 변경 0.
- 새 용어. 없음. 도구명 (husky / lint-staged) 은 외부 OSS 도구의 고유명사로 glossary 등재 대상 아님.
- 기존 결정 충돌. 있음 — `docs/decisions/2026-05-22-frontend-logging-policy.md` §Consequences 의 "GitHub Settings → Required check" 전제가 free tier private repo 에서 불성립. 본 PR 가 그 ADR 정정으로 충돌 해소.
- 관련 ADR. 같은 ADR 정정 (신규 ADR 생성 아님, 기존 ADR §Consequences 수정).

## 스펙

전체 스펙. [docs/specs/2026-05-22-husky-lint-staged-adr-revise.md](../specs/2026-05-22-husky-lint-staged-adr-revise.md).

핵심 시나리오 3줄 요약.
- S1. apps/web 파일에 `console.log` staged → `git commit` 시 husky pre-commit hook 가 lint-staged 호출 → eslint no-console 차단 → commit 거부.
- S4. backend / docs 영역만 staged 시 lint-staged 매칭 안 함 → frontend lint 미실행 → 빠른 통과 (≤ 500ms).
- S6. ADR `2026-05-22-frontend-logging-policy.md` §Consequences 의 server-side 머지 차단 전제 정정 — client-side mitigation (husky + lint-staged) 1차 + GH Actions CI 2차 (적색 표시만) + 미래 정정 트리거 추가 (GitHub Pro 도입 / public 전환).

핵심 결정 (Maxi 게이트 1 검토 대상 6건).
- D1-a. husky + lint-staged 둘 다 root install (monorepo 일관성).
- D2-a. lint-staged 명령. `pnpm --filter @bts/web exec eslint --max-warnings 0 --cache`.
- D3-a. ADR §Consequences 정정 (외과적) + §정정 이력 표 (BTS 최초 정정 표준).
- D4-a. E2E TDD 변형 (PR #12 패턴). RED phase fixture 검증.
- D5-a. pre-commit 만 도입 (commit-msg / pre-push 별도 PR).
- D6-a. husky v9 (최신, `husky` 단일 명령).

## Brainstorming Check

✅ 통과 (1회 iteration. Phase A office-hours skip — chore + 도구 도입 자명. Phase B brainstorming 만 진행).

- gap 8건 발견 → Maxi 결정 2건 (G1 husky v9 / G2 §정정 이력 형식) + spec inline 보강 6건 (G3 worktree EC-10 / G4 .husky/pre-commit git tracked / G5 prepare idempotency / G6 GH Actions silent skip / G7 executable bit EC-11 / G8 CONTRIBUTING.md FR-HUSKY-07).
- D6 (husky 버전) 신설.
- learning 후보 1건 (L1. classify-task 의 frontend tooling 오분류 패턴 누적) — 본 PR scope 외, 머지 후 learnings.md 에 기록.

## Plan

### 머리말 — TDD 변형 적용

본 PR 는 인프라/문서 task (husky 설치 / lint-staged 설정 / git hook / `.gitignore` / ADR / CONTRIBUTING) 의 묶음. learnings.md 의 "**E2E TDD 변형 패턴**" (PR #12 가 확립, 본 PR 로 두 번째 적용) 을 따른다.

- RED phase 의 명시적 실패 테스트 커밋은 **생략 OK**. eslint config / workflow yml / ADR / hook script 모두 단위 테스트 부자연스러움.
- 대신 각 task 마다 **fixture 임시 작성 → 검증 (차단 / grep / 매칭 확인) → fixture 제거** 가 RED → GREEN 등동.
- `bts-impl` SKILL 의 "TDD 사이클 = test 커밋이 feat 커밋보다 먼저" 자동 검증은 본 PR 에 비활성. plan 머리말로 명시.

### Wave 1 — 6-병렬 (파일 겹침 0, 의존성 0)

T1~T6 모두 파일 겹침 없고 코드 의존성 없음 (단순 파일 신규 작성 / 한 줄 추가). PR #7 의 5-병렬 패턴을 한 단계 확장. learnings.md "wave 당 5~7 task max 권장" 한계 안.

### Task 1. root `package.json` — husky/lint-staged devDependencies + `prepare` script

**메타**.
- agent. `frontend-engineer`
- files. [`package.json`]
- depends-on. `[]`

**RED** (fixture 검증).
- 현재. root `package.json` 에 `husky` / `lint-staged` 없음 + `prepare` script 없음 → `pnpm install` 후 `git config core.hooksPath` 가 unset (= `.git/hooks` default).
- 검증 명령 `git config --get core.hooksPath` → 빈 출력 (현재 상태 확인).

**GREEN**.
- `package.json` 의 `devDependencies` 에 추가.
  - `"husky": "^9.0.0"`
  - `"lint-staged": "^17.0.0"` (plan-eng-review A1 결정 — 최신 major, 2026-05-22 시점 17.0.5. husky v9 와 동일 원칙 (최신 major 채택). breaking change 영향 미미 (단순 매칭 + eslint 1 명령 호출)).
- `package.json` 의 `scripts` 에 추가. `"prepare": "husky"` (v9 단일 명령, D6-a).
- `pnpm install` 실행.

**REFACTOR**.
- 없음 (devDependencies + script 2건만 추가).

**검증** (fixture).
1. `git config --get core.hooksPath` → `.husky` 가리킴 확인.
2. `pnpm install` 재실행 → idempotent 통과 (FR-HUSKY-04, G5).
3. apps/web `package.json` 에 `prepare` 없음 확인 (충돌 0).

### Task 2. root `.lintstagedrc.json` 신규 — 매칭 패턴 + D2-a 명령

**메타**.
- agent. `frontend-engineer`
- files. [`.lintstagedrc.json`]
- depends-on. `[]`

**RED** (fixture 검증).
- 현재. `.lintstagedrc.json` 없음. `pnpm exec lint-staged` 호출 시 설정 부재 에러.

**GREEN**.
- `.lintstagedrc.json` 신규 작성.

```json
{
  "apps/web/**/*.{ts,tsx,js,jsx}": [
    "pnpm --filter @bts/web exec eslint --max-warnings 0 --cache"
  ]
}
```

- 매칭 패턴 정확히 `apps/web/**/*.{ts,tsx,js,jsx}` (S4 / EC-5 의 backend / docs 영역만 staged 시 매칭 안 함 보장).
- 명령 D2-a (`pnpm --filter @bts/web exec eslint --max-warnings 0 --cache`).

**REFACTOR**.
- 없음.

**검증** (fixture).
1. `pnpm exec lint-staged --help` 또는 `--debug` 실행 → config 인식 확인 (실제 staged 검사는 Task 7 통합 검증에서).

### Task 3. `.husky/pre-commit` 신규 — lint-staged 호출 + git tracked + executable bit

**메타**.
- agent. `frontend-engineer`
- files. [`.husky/pre-commit`]
- depends-on. `[]`

**RED** (fixture 검증).
- 현재. `.husky/pre-commit` 없음 → `git commit` 시 hook 미동작.

**GREEN**.
- `.husky/pre-commit` 신규 작성. **husky v9 형식 (shebang 없음, 자체 호출)**.

```sh
pnpm exec lint-staged
```

- 파일 작성 후 즉시.
  - `git add .husky/pre-commit`
  - `git update-index --chmod=+x .husky/pre-commit` (executable bit, EC-11)
  - `git ls-files --stage .husky/pre-commit` → `100755` 확인.

**REFACTOR**.
- 없음.

**검증** (fixture, Task 7 의 통합 검증으로 위임).
- 본 task 단독 검증은 파일 존재 + 100755 + 내용 확인까지. fixture commit 차단/통과 시나리오는 T7.

### Task 4. root `.gitignore` — `.husky/_/` 추가 (D6-a 구현 세부)

**메타**.
- agent. `frontend-engineer`
- files. [`.gitignore`]
- depends-on. `[]`

**RED** (fixture 검증).
- 현재. `.gitignore` 에 `.husky/_/` 없음. husky v9 가 `.husky/_/` shim 디렉토리 자동 생성 시 git status 에 untracked 로 표시 위험.

**GREEN**.
- `.gitignore` 의 적절한 위치 (예. 기존 "보안" 섹션 위, "BTS 워크플로우 산출물" 섹션 근처) 에 1줄 추가.

```
# husky v9 shim 디렉토리 (sample/transition hooks)
.husky/_/
```

**REFACTOR**.
- 없음.

**검증** (fixture).
1. `git check-ignore -v .husky/_/foo` → `.gitignore` 의 새 라인 매칭 출력.

### Task 5. `docs/decisions/2026-05-22-frontend-logging-policy.md` — §Consequences 정정 + §정정 이력 표

**메타**.
- agent. `frontend-engineer`
- files. [`docs/decisions/2026-05-22-frontend-logging-policy.md`]
- depends-on. `[]`

**RED** (fixture 검증).
- 현재 본문 grep 으로 잘못된 전제 단락 존재 확인.
  - `grep -n "pre-commit hook (husky + lint-staged) 부재" docs/decisions/2026-05-22-frontend-logging-policy.md` → 1줄 (라인 41).
  - `grep -n "Required status checks → .frontend-ci / lint." docs/decisions/2026-05-22-frontend-logging-policy.md` → 1줄 (라인 44).
  - `grep -n "정정 이력" docs/decisions/2026-05-22-frontend-logging-policy.md` → 0줄.

**GREEN**.
- §Consequences 의 정정 대상 3개 단락 외과적 수정 (D3-a).
  1. **라인 41** "pre-commit hook (husky + lint-staged) 부재 (본 ADR 비스코프). 본 PR 머지 후 별도 후속 PR 도입 가능." → "pre-commit hook (husky + lint-staged) 도입 완료 (후속 PR #N, 2026-05-22). 본 ADR 의 client-side mitigation 1차 방어선." 으로 교체.
  2. **라인 43~45** "🔔 Maxi 후속 액션 (본 PR 머지 후 즉시, 1건). GitHub Settings → Branches → Branch protection rules → main → Required status checks → `frontend-ci / lint` 추가 → Save changes. 미설정 시 CI fail check 표시는 되나 머지 차단 안 됨 (silent failure). 본 ADR 의 '자동 차단' 의도 무력화 위험." → "**~~Maxi 후속 액션~~ (정정됨, 2026-05-22)**. BTS repo 는 GitHub free tier private — Branch Protection 및 Repository Rulesets 모두 차단됨 (`gh api ... → 403 Upgrade to GitHub Pro or make this repository public`). server-side 머지 차단 전제 불성립. **client-side mitigation 1차 방어선 (husky + lint-staged, 후속 PR #N)** + **GH Actions CI 2차 (PR check 적색 표시만, 머지 차단 안 됨)** 로 재정의. 자세한 사유는 §정정 이력 참고." 로 교체.
  3. §미래 정정 트리거 의 기존 3건 (사용자 100명 / incident / 외부 수집기) 유지 + **신규 트리거 1건 추가**. "4. **GitHub Pro 도입 또는 repo public 전환** — Branch Protection / Repository Rulesets 사용 가능 → server-side 머지 차단 재시도 가능. 본 ADR §Consequences 의 client-side / server-side 분담 재검토."
- §Consequences 끝에 `### 정정 이력` 서브섹션 신규 추가 (G2 형식).

```markdown
### 정정 이력

| 날짜 | PR | 사유 | 정정 전 | 정정 후 |
|---|---|---|---|---|
| 2026-05-22 | #N | GitHub free tier private repo 의 branch protection 차단 발견 (`gh api repos/maxihan1/BTS/branches/main/protection → 403 Upgrade to GitHub Pro or make this repository public`). server-side 머지 차단 전제 불성립. | "Maxi 후속 액션. GitHub Settings → Branches → Branch protection rules → main → Required status checks → frontend-ci / lint 추가 → Save changes" + "미설정 시 silent failure 위험" | client-side mitigation (husky + lint-staged) 1차 방어선 + GH Actions CI 2차 (PR check 적색 표시만, 차단 안 됨) + 미래 정정 트리거 4번째 신규 (GitHub Pro 도입 / public 전환 시점) |
```

- PR 번호 `#N` 부분은 본 PR (PR #15) 의 실제 번호로 교체. plan §리뷰 결과 단계에서 확인.

**REFACTOR**.
- 없음 (외과적 정정).

**검증** (fixture).
1. `grep -n "pre-commit hook (husky + lint-staged) 부재" docs/decisions/2026-05-22-frontend-logging-policy.md` → 0줄 (정정 전 문구 사라짐 확인).
2. `grep -n "client-side mitigation 1차 방어선" docs/decisions/2026-05-22-frontend-logging-policy.md` → ≥ 1줄 (정정 후 문구 존재 확인).
3. `grep -n "### 정정 이력" docs/decisions/2026-05-22-frontend-logging-policy.md` → 1줄 (서브섹션 존재 확인).
4. `grep -n "GitHub Pro" docs/decisions/2026-05-22-frontend-logging-policy.md` → ≥ 1줄 (미래 트리거 4번째 추가 확인).

### Task 6. `CONTRIBUTING.md` — husky 활성화 안내 1줄 추가 (FR-HUSKY-07)

**메타**.
- agent. `frontend-engineer`
- files. [`CONTRIBUTING.md`]
- depends-on. `[]`

**RED** (fixture 검증).
- 현재 본문 grep. `grep -n "husky\|pre-commit" CONTRIBUTING.md` → 0줄.

**GREEN** (plan-devex-review GAP-DX1+DX2 보강 — "왜" + "어디서" + "검증" 추가).
- `CONTRIBUTING.md` 의 적절한 섹션 (예. "환경 설정" 또는 "개발 시작" 근처. 없으면 신규 섹션 1개 추가) 에 husky 활성화 안내 추가.

```markdown
### pre-commit hook (husky + lint-staged)

**왜 필요한가**. `DEVELOPMENT.md §1 절대 규칙 #15` (NEVER-15. `console.log` / `println` 금지) 를 frontend (apps/web) 영역에서 client-side 로 강제. PR #12 의 `no-console` ESLint 룰을 `git commit` 시점에 자동 검증해 의도치 않은 commit 차단. server-side branch protection 은 GitHub free tier private repo 라 차단됨 (자세한 사유. [`docs/decisions/2026-05-22-frontend-logging-policy.md`](docs/decisions/2026-05-22-frontend-logging-policy.md) §정정 이력).

**활성화 방법**. **repo root 또는 worktree 어디서든** `pnpm install` 1회 실행. `.git/` 와 `.husky/` 는 monorepo 1개라 worktree 별 별도 install 불필요 (BTS `worktree per 작업` 패턴 호환). root `prepare` script 가 husky 자동 활성화.

**활성화 검증**. `git config --get core.hooksPath` 출력이 `.husky` 면 정상. 출력 0줄이면 `pnpm install` 재실행.

**작동 방식**. `git commit` 시 staged frontend 파일 (`apps/web/**/*.{ts,tsx,js,jsx}`) 에 eslint no-console 자동 검증. 위반 시 commit 차단 + ESLint 에러 메시지 (파일 경로 + 라인 + 룰 명).

**의도적 우회**. `git commit --no-verify` 가능 (PR review 단계에서 사유 확인). hook 은 실수성 위반 차단용.
```

**REFACTOR**.
- 없음.

**검증** (fixture).
1. `grep -n "husky\|pre-commit" CONTRIBUTING.md` → ≥ 1줄.
2. `grep -n "pnpm install" CONTRIBUTING.md` → ≥ 1줄.
3. `grep -n "no-verify" CONTRIBUTING.md` → ≥ 1줄.
4. `grep -n "왜 필요한가\|NEVER-15\|core.hooksPath" CONTRIBUTING.md` → ≥ 1줄 each (DX 보강 검증).

---

### Wave 2 — 통합 검증

### Task 7. 통합 검증 — fixture commit / S1·S2·S4·EC-10 / 빌드 회귀 / frontend-ci.yml + GH Actions husky silent skip

**메타**.
- agent. `frontend-engineer`
- files. (검증 only, 신규 작성 0)
- depends-on. `[1, 2, 3, 4, 5, 6]`

**RED** (fixture 검증).
- T1~T6 완료 상태 가정 시 본 task 의 실행 전 단계 검증.
  - `git config --get core.hooksPath` → `.husky` (Task 1 완료 확인).
  - `cat .lintstagedrc.json` → 매칭 패턴 + D2-a 명령 (Task 2 완료 확인).
  - `cat .husky/pre-commit` → `pnpm exec lint-staged` 1줄 + `git ls-files --stage .husky/pre-commit` → `100755` (Task 3 완료 확인).
  - `git check-ignore -v .husky/_/foo` → matched (Task 4 완료 확인).
  - `grep "client-side mitigation 1차 방어선" docs/decisions/2026-05-22-frontend-logging-policy.md` → 매치 (Task 5 완료 확인).
  - `grep "husky" CONTRIBUTING.md` → 매치 (Task 6 완료 확인).

**GREEN** (통합 fixture 시나리오).

**S1 — pre-commit 차단** (plan-eng-review Q1 보강 — git history 오염 회피).
1. `apps/web/src/__husky_fixture__.ts` 임시 파일 작성 (1줄. `export const x = (() => { console.log("fixture"); return 1; })();`).
2. `git add apps/web/src/__husky_fixture__.ts`.
3. **commit 대신 hook 직접 호출** `bash .husky/pre-commit`.
4. **기대**. lint-staged → eslint → `no-console` 위반 검출 → exit code ≠ 0.
5. 에러 메시지에 `__husky_fixture__.ts` + `no-console` + 라인 번호 표시 확인.
6. fixture 파일 제거 (`git restore --staged apps/web/src/__husky_fixture__.ts && rm apps/web/src/__husky_fixture__.ts`). git history 영향 0.

**S2 — 정상 staged commit 통과** (plan-eng-review Q1 보강 — git history 오염 회피).
1. `apps/web/src/__husky_fixture__.ts` 임시 파일 작성 (정상 코드. `export const x = 1;`).
2. `git add apps/web/src/__husky_fixture__.ts`.
3. **commit 대신 hook 직접 호출** `bash .husky/pre-commit` (또는 `pnpm exec lint-staged --diff="--cached"` dry-run).
4. **기대**. lint-staged → eslint 통과 → exit 0. 체감 지연 측정 `time bash .husky/pre-commit` → ≤ NFR-HUSKY-01 (≤ 2초, M1 + warm cache).
5. fixture 파일 제거 (`git restore --staged apps/web/src/__husky_fixture__.ts && rm apps/web/src/__husky_fixture__.ts`). git history 영향 0.

**S4 — backend/docs 영역만 staged 시 frontend lint 미실행** (plan-eng-review Q1+Q2 보강).
1. `docs/__husky_fixture__.md` 임시 파일 작성 (`docs/decisions/` 외 — ADR 디렉토리 오염 회피).
2. `git add docs/__husky_fixture__.md`.
3. **commit 대신 hook 직접 호출** `time bash .husky/pre-commit`.
4. **기대**. lint-staged 매칭 안 함 → 빠른 통과 → exit 0 + `time` 출력 ≤ NFR-HUSKY-05 (≤ 500ms). frontend eslint 미실행 확인 (`--debug` 옵션으로 task 0 확인).
5. fixture 제거 (`git restore --staged docs/__husky_fixture__.md && rm docs/__husky_fixture__.md`).

**EC-10 — worktree per 작업 환경 호환**.
- 본 task 자체가 `.worktrees/husky-lint-staged-adr-revise/` worktree 안에서 실행 중. S1/S2/S4 시나리오 모두 본 worktree 에서 정상 작동하면 EC-10 통과.

**EC-11 — executable bit 회귀 확인**.
- `git ls-files --stage .husky/pre-commit` → `100755`.

**빌드 회귀 검증**.
- `pnpm --filter @bts/web lint` → exit 0.
- `pnpm --filter @bts/web typecheck` → exit 0.
- `pnpm --filter @bts/web test` → exit 0 + PR #11 baseline 77개 + DevSeedHashGenerator 의 vitest 회귀 보존.
- `pnpm --filter @bts/web build` → exit 0.

**NFR 측정 명시** (plan-eng-review P1 보강).
- NFR-HUSKY-01 (`apps/web` staged commit 체감 지연 ≤ 2초). 측정 `time bash .husky/pre-commit` (S2 시나리오) → real 시간 ≤ 2.0s 확인. M1 + warm cache 기준.
- NFR-HUSKY-05 (`apps/web` 외만 staged 시 ≤ 500ms). 측정 `time bash .husky/pre-commit` (S4 시나리오) → real 시간 ≤ 0.5s 확인.
- NFR-HUSKY-03 (`pnpm install` 추가 시간 ≤ 5초). 측정 `time pnpm install` (idempotent rerun, warm cache).
- 측정 결과는 plan §리뷰 결과 또는 PR body 본문에 1줄씩 기록.

**frontend-ci.yml + GH Actions husky silent skip 검증 (EC-2 + G6)**.
- 본 PR push 후 GitHub Actions `frontend-ci` workflow 트리거 확인.
- run log 의 `pnpm install` 단계 출력에 husky `prepare` 가 silent skip 됨 확인 (`CI=true` / `GITHUB_ACTIONS=true` 자동 감지).
- workflow exit code 0 + cold start NFR-LOG-FE-05 (≤ 4분) 회귀 없음.

**REFACTOR** (검증 결과 정리).
- 모든 fixture 파일/커밋 제거 (worktree 깨끗).
- 검증 결과 plan 의 §리뷰 결과 섹션에 1줄씩 기록.

**검증** (메타 — 본 task 완료 기준).
- S1/S2/S4 fixture 시나리오 모두 통과 → 본 task 통과.
- 빌드 회귀 4종 모두 exit 0 → 본 task 통과.
- GH Actions workflow 통과 → 본 task 통과.
- 어느 하나라도 실패 시 BLOCKED → 책임 task (T1~T6 중 해당) implementer 재dispatch.

## Plan 메타

- **task 수**. 7 (Wave 1 6-병렬 + Wave 2 검증).
- **wave 구조**. depth 2. Wave 1 = T1~T6 (depends-on []), Wave 2 = T7 (depends-on [1,2,3,4,5,6]).
- **예상 시간**. 직렬 약 35분. wave 병렬 적용 시 약 12분 (Wave 1 6-병렬 ~7분 + Wave 2 통합 검증 ~5분).
- **TDD 강제**. E2E TDD 변형 적용 (RED phase 명시 commit 생략 OK, fixture 검증으로 등동). `bts-impl` SKILL 의 "test 커밋 우선" 자동 검증은 본 PR 비활성.
- **병렬 dispatch**. Wave 1 6-병렬 (PR #7 의 5-병렬 패턴 한 단계 확장, PR #10 의 wave 당 max 7 권장 안). 모든 T1~T6 파일 겹침 0 + 코드 의존성 0.
- **추가 검증**. T7 이 통합 검증 wave 로 단일 task — lint / typecheck / test / build / fixture S1·S2·S4 / EC-10 / EC-11 / GH Actions silent skip 일괄.
- **참고 패턴**. PR #7 (1-wave 5-병렬, 가장 빠른 케이스). PR #12 (1-wave 3-병렬 + 검증 wave, chore E2E TDD 변형).
- **Wave 1 일괄 install 패턴 (PR #11 learning)**. T1 (root package.json) 의 `pnpm install` 변경은 다른 task 의 파일에 영향 0. 그러나 wave 1 내 모든 implementer 가 동일 worktree 의 `node_modules` 공유 → lockfile race 우려. 회피 방법 — Wave 1 의 6 implementer 가 모두 자기 작업 완료 후 verifier (controller) 가 단일 `pnpm install` 한 번만 실행하는 패턴 적용 (PR #11 의 4-wave lockfile race 회피 패턴 재활용). plan-eng-review 단계에서 재확인.

## 리뷰 결과

### plan-eng-review (2026-05-22)

**상태**. ✅ PASS (BLOCKER 0건, CONCERNS 1건 해소 + 권장 보완 3건 inline 반영).

**5가지 검증 관점 결과**.

| # | 관점 | 결과 |
|---|---|---|
| 1 | wave 분해 + depends-on / 파일 겹침 | ✅ T1~T6 파일 겹침 0 + depends-on 0 (6-병렬). wave depth 2. learnings.md "wave 당 max 7" 안. |
| 2 | lockfile race 회피 (PR #11 learning) | ✅ Plan §"Wave 1 일괄 install 패턴" 명시 — controller 가 단일 `pnpm install` 한 번만 실행. |
| 3 | ADR §정정 이력 표 형식 BTS 표준 적합성 | ✅ 외과적 정정 (D3-a) + 표 형식 (날짜/PR/사유/정정 전/정정 후) 누적 가능. BTS 향후 ADR 정정 표준 적합. |
| 4 | E2E TDD 변형 등동 검증력 | ✅ PR #12 검증 패턴 재활용. RED phase fixture 검증 (T1~T5 grep / T7 hook 직접 호출) 이 RED→GREEN 등동. |
| 5 | D6 (husky v9) + D2-a 명령 실현 가능성 | ✅ D2-a 명령 정상 작동 검증 (apps/web cwd 에서 ESLint flat config 자동 탐지). |

**CONCERNS 1건 해소**.

- **A1. lint-staged 버전 결정** — spec/plan 의 `^16.0.0` (caret 범위가 16.x 만 허용) vs 최신 17.0.5 (major bump). Maxi 결정 (2026-05-22) → **`^17.0.0` 채택** (husky v9 와 동일 원칙). spec D6-a 구현 세부 + plan T1 inline 반영 완료.

**권장 보완 3건 inline 반영**.

- **Q1. T7 S1/S2/S4 시나리오 정밀화** — `git commit ... && git reset --hard HEAD~1` 패턴 (git history 잠시 흐트러짐) → **`bash .husky/pre-commit` 직접 호출 + `git restore --staged`** 패턴으로 변경. git history 영향 0. plan T7 inline 반영 완료.
- **Q2. T7 S4 fixture 위치** — `docs/decisions/__husky_fixture__.md` (ADR 디렉토리 오염) → `docs/__husky_fixture__.md` (decisions/ 외) 로 이동. plan T7 inline 반영 완료.
- **P1. NFR 측정 책임 명시** — T7 에 NFR-HUSKY-01 (≤ 2초), NFR-HUSKY-05 (≤ 500ms), NFR-HUSKY-03 (≤ 5초) 의 측정 방법 (`time bash .husky/pre-commit`, `time pnpm install`) 추가. 측정 결과 plan §리뷰 결과 또는 PR body 1줄씩 기록 약속. plan T7 inline 반영 완료.

**Outside voice (codex challenge) skip** — chore + 작은 변경 (7 task / 6 파일 / +30~50줄 예상) 이라 cost > value.

### plan-devex-review (2026-05-22)

**상태**. ✅ PASS (BLOCKER 0건, 권장 보완 2건 inline 반영 + informational 1건 후속 위임).

**페르소나** (args 명시). BTS 의 frontend 개발자 (Maxi 본인 + 향후 Claude Code subagent). multi-worktree + multi-agent 동시 작업 환경.

**5가지 DX 점검 관점 결과**.

| # | 관점 | 점수 | 결과 |
|---|---|---|---|
| 1 | CONTRIBUTING.md 안내 가독성 | 🟡 7/10 → 9/10 (보강 후) | GAP-DX1 보강 — "왜 husky" + 활성화 검증 방법. |
| 2 | pre-commit hook 차단 에러 명확성 | 🟢 8/10 | ESLint 표준 메시지 (Tier 2). 추가 fix 없음. |
| 3 | `--no-verify` 우회 가이드 | 🟢 9/10 | T6 CONTRIBUTING 본문 명시. 충분. |
| 4 | worktree per 작업 환경 onboarding | 🟡 7/10 → 9/10 (보강 후) | GAP-DX2 보강 — pnpm install 위치 명확화 (root 또는 worktree 어디서든). |
| 5 | ADR §정정 이력 표 향후 DX | 🟡 7/10 | GAP-DX3 informational — wide 가독성. 본 PR 비스코프 (learning 후보 L2). |

**Overall DX Scorecard**.
- Getting Started. 7→9/10. Error Messages. 8/10. Documentation. 7/10. Dev Environment. 9/10. DX Measurement. 7/10.
- **Overall 8/10** (보강 후 9/10).
- **TTHW** (신규 dev 의 첫 commit 차단 경험). ~3분 (pnpm install + fixture 작성 + commit 시도).

**권장 보완 2건 inline 반영**.

- **GAP-DX1. CONTRIBUTING 안내 보강** — T6 의 CONTRIBUTING 본문에 (1) **왜 husky 가 필요한가** (NEVER-15 frontend client-side 강제, server-side 차단 사유 ADR 링크) + (2) **활성화 검증 방법** (`git config --get core.hooksPath` 가 `.husky` 가리키면 정상) 추가. plan T6 inline 반영 완료.
- **GAP-DX2. worktree per 작업 환경 onboarding 명확화** — T6 의 `pnpm install` 실행 위치 모호 → "**repo root 또는 worktree 어디서든** `pnpm install` 1회. `.git/` 와 `.husky/` 는 monorepo 1개라 worktree 별 별도 install 불필요" 명시. plan T6 inline 반영 완료.

**informational 1건 후속**.

- **GAP-DX3 (L2). ADR §정정 이력 표 wide 가독성** — 본 PR 첫 정정은 짧음 (단일 단락 + 트리거 1건). 향후 ADR 정정 사례 누적 + 긴 정정 사유는 footnote 분리 표준 검토 가능. learnings 후보 L2 — 본 PR scope 외, 머지 후 learnings.md 에 기록.

**Outside voice (codex challenge) skip** — chore + 작은 변경 이라 cost > value.

### T7 통합 검증 실측 결과 (2026-05-22)

**Wave 1 종료 + controller 단일 `pnpm install` 패턴 (PR #11 learning lockfile race 회피)**.

- `pnpm install` 첫 실행 (cold). 28.3초 + 660 패키지 추가 + husky 9.1.7 + lint-staged 17.0.5.
- `prepare$ husky` 자동 실행 → `core.hooksPath=.husky/_` 설정 + `.husky/_/` shim 디렉토리 자동 생성 (17개 hook shim + 자체 `.gitignore`).
- husky v9 정상 동작 — `.husky/_/pre-commit` shim 이 exec bit (`-rwxr-xr-x`), `.husky/pre-commit` 본체는 mode 100644 (shim 이 sh content 읽어서 실행, 본체 exec bit 불필요).
- T4 의 root `.gitignore` `.husky/_/` 등록과 husky 자체 `.husky/_/.gitignore` 중복 redundancy (안전).

**T7 fixture 시나리오 결과**.

| 시나리오 | 기대 | 실측 | 결과 |
|---|---|---|---|
| S1 (`console.log` staged) | exit ≠ 0 + no-console 에러 | exit 1 + `Unexpected console statement. Only these console methods are allowed: warn, error  no-console` (Tier 2 메시지) | ✅ |
| S2 (정상 staged, cold) | exit 0 | exit 0, 14.98s (cold) | ✅ |
| S2 (정상 staged, warm) | exit 0 + NFR-HUSKY-01 ≤ 2초 | exit 0, 3.5~3.9s (warm × 2회 평균) | ⚠️ NFR 초과 (lint-staged 의 git stash + restore overhead 포함, baseline 기록) |
| S4 (`docs/__husky_fixture__.md`) | exit 0 + NFR-HUSKY-05 ≤ 500ms | exit 0, 0.89s | ⚠️ 약간 초과 (lint-staged 매칭 안 함 → 매처 overhead. 사용자 체감 빠름) |
| EC-2 (idempotent rerun) | NFR-HUSKY-03 ≤ 5초 | 245ms | ✅ 압도적 통과 |
| EC-10 (worktree per 작업) | hook 정상 작동 | 본 worktree 안에서 모든 S1/S2/S4 작동 | ✅ |
| EC-11 (exec bit) | `.husky/_/pre-commit` shim `100755` | `-rwxr-xr-x` + git index `100755` | ✅ |

**빌드 회귀 검증** (PR #11 baseline 보존).

| 명령 | 결과 | 시간 |
|---|---|---|
| `pnpm --filter @bts/web lint` | exit 0 | 2.29s |
| `pnpm --filter @bts/web typecheck` | exit 0 | 5.95s |
| `pnpm --filter @bts/web test` | exit 0, **11 files / 77 tests passed** | 20.26s |
| `pnpm --filter @bts/web build` | ✓ built, JS **177.29 kB gzip** (baseline 동일) | 3.29s |

**자동 검증** (Wave 1 종료 commit 시).

- chore wave-1 종료 commit 시점에 husky hook 자동 작동 확인 — staged `.husky/pre-commit` / `pnpm-lock.yaml` / `docs/specs/...md` 모두 `apps/web/**` 패턴 비매칭 → `lint-staged could not find any staged files matching configured tasks` + exit 0. **T7 S4 + EC-10 의 자연 검증**.

**NFR 실측 baseline 정리** (spec NFR 목표 ideal, 실측 baseline 으로 향후 측정 기준 확립).

- NFR-HUSKY-01 (≤ 2초). 실측 warm cache 3.5~3.9초. 차이 사유 — lint-staged 의 git stash/restore + pnpm workspace resolution overhead. 사용자 체감 ~4초 baseline. NFR 갱신 안 함 (ideal 목표 유지).
- NFR-HUSKY-03 (≤ 5초). 실측 0.245초. 압도적 통과.
- NFR-HUSKY-05 (≤ 500ms). 실측 0.89초. lint-staged 매처 시작 overhead 포함. baseline 으로 기록.

**미검증 1건** — GH Actions husky silent skip (EC-2 의 `CI=true` 자동 감지). 본 PR push 후 `frontend-ci.yml` 재실행 시점에 GH Actions run log 의 `prepare` 단계 출력 확인 가능. /bts-codereview 단계에서 검증.

### /bts-codereview (2026-05-22)

**상태**. ✅ PASS (BLOCKER 0건, CRITICAL 0건, CONCERNS 1건 해소 + NOTE 2건 후속 위임).

**superpowers:code-reviewer agent**. CONCERNS 1건 발견 — spec EC-11 / D6-a 구현 세부 / §완료 기준 line 210 의 "exec bit 100755" 표기가 T7 실측 결과 husky v9 의 정상 동작 (`.husky/pre-commit` 본체 100644 + `.husky/_/pre-commit` shim 만 100755) 과 불일치. spec 3곳 외과적 정정 적용 (2026-05-22 PR #15 정정 사유 명시). NOTE 2건 (NFR baseline plan-only 명시 / learnings.md 후속 정리) 본 PR 비스코프.

**`/review` (gstack)**. ✅ PASS — `pnpm audit` 0 vulnerabilities (husky 9.1.7 + lint-staged 17.0.5 + transitive deps). ADR §Consequences D1~D5 본문 모두 보존 (외과적 정정 정합). lint-staged 의 staged 파일 path escape 안전 (path injection 위험 0). `--no-verify` 우회는 auth/CSRF 무관 (lint 강제만, 사회적 처리). SQL/LLM/Race condition 등 critical 카테고리 본 PR 무관.

**`/plan-ceo-review`** skip (type=chore + 인프라/문서, auth/migration 조건 미충족).

**EC-2 (GH Actions husky silent skip) — 실측 검증** 게이트 2 진입 시 본문에 결과 명시.


