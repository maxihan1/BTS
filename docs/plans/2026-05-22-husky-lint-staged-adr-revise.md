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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
