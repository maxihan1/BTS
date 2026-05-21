<!-- chore. apps/web eslint no-console 룰 + frontend logging 정책 ADR spec (PR #11 CONCERNS-1 후속) -->

# eslint no-console 룰 + frontend logging 정책 ADR — 스펙

> slug. `eslint-no-console-frontend-logging-adr`
> type. `chore`
> agent. `frontend-engineer`
> 트리거. PR #11 (FR-AU-09 D6 로그인 폼 UI) code-reviewer CONCERNS-1 후속
> 도메인 정리. plan §도메인 정리 참고 (BC 영향 0, 위반 1건, 신규 ADR)

## 1. 배경

- `DEVELOPMENT.md §1 절대 규칙 #15` 가 `console.log` / `println` 사용 금지를 명문화. 그러나 frontend (apps/web) 에는 이 규칙을 빌드 시점에 강제하는 lint 룰이 없음.
- PR #11 머지 직전 code-reviewer agent 가 `useLogoutMutation.ts:21` 의 `console.error` 1건을 CONCERNS-1 로 지적. 토큰/PII 미노출이지만 NEVER-15 문자 위반. PR #11 즉시 머지 + 후속 PR 위임 결정.
- 본 PR 는 세 가지를 함께 처리.
  1. **도구로 자동 차단** — eslint `no-console` 룰 적용. 신규 위반 발생 시 lint 단계에서 차단.
  2. **자동 트리거 확보** — BTS 첫 **GitHub Actions CI workflow** 신설 (`.github/workflows/frontend-ci.yml`). PR 머지 차단까지. 룰만 추가하고 자동 트리거 없으면 "도구로 차단" 의도가 달성 안 됨 (sanity check G1 결과). Maxi 결정 (2026-05-22).
  3. **정책 명문화** — `docs/decisions/2026-05-22-frontend-logging-policy.md` ADR 신설. 현재 정책 (dev console 어디까지 허용 / prod 로그 수집 인프라 부재 / Pino 도입 시점) 의 결정과 근거 기록.

## 2. 사용자 시나리오 (Given-When-Then)

### S1. 신규 위반 차단 (golden path)

> **Given** frontend 개발자 (Claude Code subagent / Maxi 본인) 가 apps/web 안에 새 파일을 작성. 본문 어딘가에 `console.log("debug")` 를 작성.
>
> **When** `pnpm --filter @bts/web lint` 실행 (CI 또는 pre-commit hook).
>
> **Then** lint 가 fail. 위반 파일 + 라인 + 룰 명 (`no-console`) 출력. 빌드/머지 차단.

### S2. 기존 위반 cleanup

> **Given** `apps/web/src/auth/useLogoutMutation.ts:21` 에 `console.error` 1줄. PR #11 CONCERNS-1 사례.
>
> **When** 본 PR 에서 no-console 룰을 활성화 + 위 console.error 줄을 정책에 맞게 대체.
>
> **Then** `pnpm --filter @bts/web lint` 통과. logout 실패 디버깅 정보는 정책상 허용 위치 (예. 향후 logger 또는 dev-only console) 로 이동.

### S3. dev 환경에서의 디버깅 (정책 결정에 따라 분기)

> **Given** frontend 개발자가 dev 모드 (Vite dev server) 에서 일시적 디버깅 정보 출력 필요.
>
> **When** `console.log` / `console.warn` / `console.error` 호출.
>
> **Then** (allow list 결정 사항) — `allow: ['warn', 'error']` 정책 시 warn/error 만 통과, log/info/debug 는 차단. 정책 1안 (D1) 참고.

### S4. prod 빌드 시 로그 처리 (미래 시나리오)

> **Given** prod 빌드 (`pnpm --filter @bts/web build`) 후 정적 자산이 배포된 환경.
>
> **When** 사용자 브라우저에서 frontend 코드가 동작 중. 예외 발생.
>
> **Then** (정책 결정 사항) — 현재 prod 로그 수집 인프라 없음. 예외는 사용자 브라우저 console 에만 남음. 미래 Pino + 외부 수집기 (Sentry / Datadog / Loki) 도입 시점에 본 ADR 갱신. 정책 2안 (D2) 참고.

### S5. PR 머지 차단 자동화 (CI 통과)

> **Given** 누군가 (Claude Code subagent / Maxi) 가 `apps/web` 수정 PR 를 push.
>
> **When** GitHub Actions `frontend-ci` workflow 가 자동 트리거. lint (no-console 포함) + 추가 항목 (D5 결정) 실행.
>
> **Then** lint 위반 시 workflow fail → PR check 빨강 → branch protection (Maxi 가 GitHub Settings 에서 별도 설정) 으로 머지 차단. lint 통과 시 check 초록 → 머지 가능.

## 3. 기능 요구사항 (FR)

| ID | 요구사항 | 검증 |
|---|---|---|
| **FR-LOG-FE-01** | `apps/web/eslint.config.js` 에 `no-console` 룰을 활성화 | `grep -n "no-console" apps/web/eslint.config.js` 결과 ≥ 1줄 |
| **FR-LOG-FE-02** | allow list 정책 결정 (D1) 에 따라 룰 옵션 설정 | `pnpm --filter @bts/web lint` 가 S1 시나리오의 위반 케이스를 차단 |
| **FR-LOG-FE-03** | 기존 위반 1건 cleanup (`useLogoutMutation.ts:21`) | D1-a 선택 시. `grep -rn "console\.\(log\|info\|debug\|dir\|group\|table\|trace\)" apps/web/src/` 결과 0건 (allow list 통과 호출은 잔존 허용). D1-c 선택 시. `grep -rn "console\." apps/web/src/` 결과 0건 |
| **FR-LOG-FE-04** | `docs/decisions/2026-05-22-frontend-logging-policy.md` ADR 작성 | 파일 존재 + 5개 결정 (D1~D5) 본문 포함 + 미래 정정 트리거 3건 (§7 D4 참조) 명시 |
| **FR-LOG-FE-05** | test 파일 / 외부 라이브러리 빌트인 console 등 예외 처리 | spec §5 엣지 케이스 참고 |
| **FR-LOG-FE-06** | `.github/workflows/frontend-ci.yml` 신규 (BTS 첫 CI workflow) | PR 트리거 시 workflow 자동 실행. 위반 시 fail check. 실행 항목은 D5 결정 |

## 4. 비기능 요구사항 (NFR)

| ID | 요구사항 | 목표값 |
|---|---|---|
| **NFR-LOG-FE-01** | lint 실행 시간 영향 | 본 룰 추가 전후 시간 차 ≤ 100ms |
| **NFR-LOG-FE-02** | 기존 빌드 / 테스트 영향 | `pnpm --filter @bts/web typecheck` `test` `build` 통과 유지 (PR #11 baseline 동일 79개 test pass) |
| **NFR-LOG-FE-03** | DX 영향 | dev 디버깅 시 정책상 허용된 출력 방법이 1가지 이상 존재 (allow list OR logger OR dev-only console) |
| **NFR-LOG-FE-04** | ADR 가독성 | 페르소나 = Maxi 본인 (BTS 헌법 / `DEVELOPMENT.md §1 절대 규칙` / `CLAUDE.md` 친숙). 6개월 후 본인이 재독 시 5분 내 결정의 사유를 이해. 외부 frontend 개발자도 §1 배경부터 읽으면 결정 사유 추적 가능 |
| **NFR-LOG-FE-05** | CI workflow 실행 시간 | 캐시 hit 시 ≤ 90초. 캐시 cold start (의존성 fresh install) 시 ≤ 4분 |

## 5. 엣지 케이스

| ID | 케이스 | 처리 방침 |
|---|---|---|
| **EC-1** | test 파일 (`*.test.ts` / `*.spec.ts`) 안 console | (D3 의존) ESLint `overrides` 로 test 파일 룰 완화 OR test 도 동일 차단 |
| **EC-2** | msw / playwright / vitest 빌트인 console | 외부 라이브러리 호출이 사용자 코드에서 console.* 호출이 아니라 영향 없음. 확인만 |
| **EC-3** | 일회성 dev script (예. `apps/web/scripts/`) | 현재 apps/web 안 scripts/ 없음. 추후 도입 시 ESLint ignore 또는 overrides 적용 |
| **EC-4** | `eslint-disable-next-line no-console` 의도적 우회 | 허용 (마지막 수단). PR review 단계에서 사유 확인 |
| **EC-5** | React `error boundary` 에서 unrecoverable error 캐치 | (D2 의존) D1-a + D2-a 추천 조합 시 `console.error` allow list 통과 + prod 빌드에서 사용자 브라우저 console 에 stack trace 노출. ADR 본문 한 줄 명시 (정책상 OK — PII 없음 가정) |
| **EC-6** | `import.meta.env.DEV` 가드와의 조합 | (D2 의존) dev-only console 패턴 허용 vs 금지 |
| **EC-7** | CI 의존성 캐시 cold start (의존성 변경 PR) | pnpm lockfile 변경 PR 마다 캐시 무효화. NFR-LOG-FE-05 의 cold start 4분 ≤ 목표 안에 들어야 함. pnpm store 캐시 키 = `pnpm-lock.yaml` hash |
| **EC-8** | branch protection 설정 누락 | 본 PR 머지 후 Maxi 가 GitHub Settings → Branches → main → Required checks 에 `frontend-ci / lint` 추가해야 진짜 머지 차단 작동. 본 PR 비스코프 (§9) 명시 |

## 6. 제약 조건

| ID | 제약 | 사유 |
|---|---|---|
| **C-1** | 본 정책은 `apps/web` 에만 적용 | backend (`backend/`) 는 별도 정책 (NEVER-15 + slf4j Logger 패턴). PR 분리 |
| **C-2** | Pino 도입은 본 PR 범위 외 | 외부 로그 수집 인프라 (Sentry / Datadog / Loki) 도입 시점과 동기. 본 PR 는 정책 명시만 |
| **C-3** | 기존 위반 1건만 cleanup. 추가 cleanup 없음 | scope 최소화. 미래 위반은 lint 가 차단 |
| **C-4** | ADR 양식은 기존 `docs/decisions/` 13건 양식 따름 | 일관성. Status / Context / Decision / Consequences / 정정 가능 구조 |

## 7. 결정 사항 (Decisions) — Maxi 게이트 1 검토 대상

### D1. no-console 룰의 allow list

| 옵션 | 정책 | 트레이드 오프 |
|---|---|---|
| **D1-a** | `{ allow: ['warn', 'error'] }` | warn/error 만 허용. 실제 frontend 사고 디버깅에는 warn/error 가 가장 빈번. dev/prod 동일. (**추천**) |
| D1-b | `{ allow: ['error'] }` | error 만 허용. 더 엄격. 그러나 warn 도 disable 시 의도적 우회 (`// eslint-disable-next-line`) 횟수가 늘어 DX 저하 |
| D1-c | `'error'` (allow 없음, 전부 차단) | 완전 차단. 우회로는 `eslint-disable` 또는 logger. 정책상 가장 엄격하지만 useLogoutMutation 패턴 같은 단순 로그가 매번 우회 주석 필요 |

추천 **D1-a**. PR #11 위반 사례 (`console.error`) 가 정책상 허용으로 들어가면 cleanup 부담 0, 향후 사고 디버깅에도 자연스러움. NEVER-15 의 문자 (`console.log` / `println`) 와 정확히 일치.

### D2. dev 디버깅 패턴

| 옵션 | 정책 | 트레이드 오프 |
|---|---|---|
| **D2-a** | `import.meta.env.DEV` 가드 + allow list 통과 호출 | 단순. dev 에서만 출력. prod 빌드 시 Vite 가 dead code elimination. (**추천**) |
| D2-b | Pino logger 즉시 도입 + level 환경별 분기 | 완성도 높음. 단, prod 로그 수집 인프라 부재 → logger 가 어디로 송출? 미동기 인프라가 ADR 의 약속 깨짐 |
| D2-c | 정책 없음 (allow list 만으로 충분) | 가장 단순. dev/prod 동일 출력. prod 빌드에서 사용자 브라우저 console 에 노출 — 토큰/PII 무관 정보만 통과 정책 |

추천 **D2-a**. prod 로그 수집 인프라 없는 현 시점에 logger 도입은 over-engineering. dev-only 가드 + allow list 조합이 자연스러움.

### D3. test 파일 처리

| 옵션 | 정책 | 트레이드 오프 |
|---|---|---|
| **D3-a** | test 파일도 동일 룰 적용 (overrides 없음) | 일관성. test 도 console 안 씀. vitest 자체 로그가 디버깅에 충분. (**추천**) |
| D3-b | test 파일 룰 완화 (`overrides: { files: ['*.test.ts','*.spec.ts'] }`) | test 의 디버깅 자유도 증가. 그러나 위반의 진입점이 늘어남 |

추천 **D3-a**. 현재 apps/web 안 test 의 console 호출 0건 (`grep` 결과). 완화 사유 없음.

### D4. prod 로그 수집 인프라 / Pino 도입 시점

| 옵션 | 정책 | 트레이드 오프 |
|---|---|---|
| **D4-a** | 현재 도입 안 함. ADR 에 "보류 + 트리거 조건 3건" 명시 | 본 PR scope 와 일치. 인프라 부재 인정 + 미래 도입 조건 명문화. (**추천**) |
| D4-b | Pino 즉시 도입 + console.* 전면 교체 | 완성도. 그러나 인프라 부재 → logger 가 console transport 사용 → D1/D2 와 중복 |
| D4-c | Sentry 또는 외부 SaaS 즉시 도입 | 가장 완성도. 비용 + 외부 의존성 추가. 본 PR scope 폭증 |

추천 **D4-a**. 본 PR 는 정책 명시 + 도구화. Pino / Sentry 같은 인프라 결정은 별도 PR (별도 ADR).

**D4-a 의 미래 정정 트리거 조건** (sanity check G3 + plan-eng-review D-1·D-2 결과, Maxi 확정 2026-05-22). 다음 중 1건 충족 시 본 ADR 재검토 + Pino / 외부 수집기 도입 결정.

1. **prod 사용자 100명 초과** — 1K 규모 BTS 의 10% 도달. 사용자 incident 발생 시 frontend 측 로그 추적 부재가 BLOCKER 가 되는 임계점. **단, 본 트리거는 사용자 수 측정 인프라 도입 시점부터 발효** (BTS 가 Phase 0 진입 직전 — prod 사용자 수 측정 인프라 부재). 측정 인프라 도입 PR 시 본 ADR 함께 갱신.
2. **첫 prod incident 발생 (frontend 원인 의심)** — 사용자가 보고한 incident (장애 / 오동작 / 사고) 중 root cause 가 **frontend 사용 중 발생한 것으로 의심**되는 사건이 1회 이상. 객관 기준 — 보고된 사용자 행위가 frontend 페이지 / 컴포넌트 / 클라이언트 사이드 로직 동작 중 발생, 그리고 재현 시도 시 console 로그만으로 root cause 식별 불가.
3. **Sentry / Datadog / Loki 등 외부 로그 수집기 도입 결정 (다른 결정으로 인한)** — backend 측 또는 별도 결정으로 외부 수집 인프라가 들어오면 frontend 도 그 transport 에 붙어야 함. 도입 결정 시 본 ADR 즉시 재검토.

### D5. CI workflow 실행 항목 (BTS 첫 CI 도입)

본 PR 가 BTS 첫 GitHub Actions workflow 도입. 실행 scope 결정 필요.

| 옵션 | scope | 트레이드 오프 |
|---|---|---|
| D5-a | `lint` 만 | 가장 작음. 본 PR 의 목적 (no-console 자동 차단) 과 정확 일치. 그러나 첫 CI 도입의 가치를 최소화. typecheck / test 회귀 가드 부재 |
| D5-b | `lint` + `typecheck` | TS strict 환경에 자연스러운 set. type 오류도 차단. test 회귀 가드는 PR review 수동 |
| **D5-c** | `lint` + `typecheck` + `test` (vitest 단위 + msw 통합) | frontend Phase 0 baseline 회귀 가드 완전. PR #11 의 77개 unit 가 모두 자동 회귀 검증. 캐시 cache hit 시 90초 이내 목표 가능. (**추천**) |
| D5-d | `lint` + `typecheck` + `test` + `build` | 완전. build 까지 검증하면 prod 빌드 영향 사고도 자동 차단. 다만 cold start 시 build 시간 1~2분 추가 — NFR-LOG-FE-05 의 4분 목표 위협. Playwright E2E 는 별도 workflow (본 PR 비스코프) |

추천 **D5-c**. lint (본 PR trigger) + typecheck (TS strict 자연스러움) + test (PR #11 의 77개 vitest 회귀 가드). build / E2E 는 후속 PR.

**선택 trade-offs**.
- workflow trigger. `pull_request` 시 `apps/web/**` 또는 `package.json` / `pnpm-lock.yaml` 또는 본 workflow 파일 자체 변경 path 만 실행 (다른 PR 에는 무관 — backend-only PR 가 frontend CI 끌어들이지 않음).
- `docs/decisions/**` 미포함 — ADR 만 변경 PR 는 frontend CI 안 트리거 (의도된 동작). ADR 갱신 후속 PR 가 자동 워크플로우 트리거 필요 시 path filter 갱신.
- 향후 `apps/admin/`, `packages/ui/` 같은 frontend 영역 추가 시 path filter 갱신 필요 (silent skip 위험).
- Node 버전. 22 (BTS 표준, package.json engines 일치).
- pnpm 버전. lockfile 표준. action `pnpm/action-setup@v4` 사용. pnpm store 캐시 hit 시간 단축.
- 동시 실행. 같은 PR 의 새 push 시 이전 run cancel-in-progress 적용.
- **확장성**. 본 workflow 패턴은 후속 도입 시 복제 가능. naming `<영역>-ci` (예. `backend-ci`, `e2e-ci`) + path filter + concurrency 패턴 일관 유지. ADR 본문에 명시.

## 8. 측정 가능한 완료 기준

- [ ] `apps/web/eslint.config.js` 에 `no-console` 룰 추가 (D1 결정 반영)
- [ ] `apps/web/src/auth/useLogoutMutation.ts:21` 의 `console.error` 가 정책상 허용 위치로 이동 또는 제거
- [ ] `pnpm --filter @bts/web lint` 통과 (위반 0건)
- [ ] `pnpm --filter @bts/web typecheck` `test` `build` 통과 (회귀 0)
- [ ] `docs/decisions/2026-05-22-frontend-logging-policy.md` 신규 ADR 작성 (D1~D5 결정 + 근거 + 미래 정정 트리거 3건)
- [ ] `.github/workflows/frontend-ci.yml` 신규 작성 (D5 결정 반영, cancel-in-progress, path filter, pnpm 캐시)
- [ ] GitHub Actions 실제 실행 검증 — 본 PR push 후 workflow 트리거 + 캐시 hit / miss 시간 NFR-LOG-FE-05 범위 안
- [ ] PR 라벨 `learning:logging-policy` 부착 (post-merge sync-obsidian 시 자동 learning append)

## 9. 비스코프 (Out of Scope)

- backend logging 정책 (NEVER-15 + slf4j Logger). 별도 PR.
- Pino / Sentry / Datadog / Loki 인프라 도입. 별도 PR + ADR.
- frontend 에러 boundary 의 사용자 표시 UI. 별도 PR.
- `DEVELOPMENT.md §1 절대 규칙 #15` 자체의 개정. 본 PR 는 규칙 강화만, 본문 수정 없음.
- **husky + lint-staged 도입.** 본 PR 는 GitHub Actions 만. 로컬 pre-commit hook 은 별도 결정 (G1-b 옵션 a 검토 후 별도 PR 가능).
- **branch protection 설정.** GitHub Settings → Branches → main → Required checks 에 `frontend-ci / lint` 추가는 Maxi 가 머지 후 직접. 코드 영역 아님.
- **backend / E2E workflow.** Playwright E2E 는 별도 workflow (시간 5~10분, 본 PR 의 NFR-LOG-FE-05 목표 깸). backend (Gradle) CI 는 별도 결정.

## 10. Brainstorming Check

✅ 통과 (2회 iteration. brainstorming Phase B 에서 gap 5건 발견 → Maxi 결정 2건 (G1 CI 통합 / G3 트리거 조건 명시) + spec inline 보강 3건 (G2 grep 기준 / G4 페르소나 / G5 EC-5 ADR 본문) 반영. CI workflow 도입 결정으로 spec scope 확장 — D5 신설 / FR-LOG-FE-06 / NFR-LOG-FE-05 / EC-7 / EC-8 / 비스코프 4건 추가).

자세한 sanity check 결과는 PR #12 의 conversation log 참고.
