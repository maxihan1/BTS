# `node` 로 `.ts` 를 부르는 호출문의 타입 스트리핑 플래그를 전수 봉인한다

> 티어: T2
> slug: node-ts-invocation-flag-guard
> type: chore
> agent: backend-engineer
> 생성: 2026-08-14

## Brief

**FR 없음 — FR수 불변 139.** 프로덕션 Kotlin 0줄 · `apps/web` 0파일 · 마이그레이션 0 · 신규 의존성 0.
하네스·가드 표면 전용 작업이다.

**사용자 원문.** 저장된 컨텍스트의 「남은 일 7 — 등재 대기 1건」에서 출발했다. 원 서술은
`bts-start/SKILL.md:34` 의 `classify-task.ts` 호출에 `--experimental-strip-types` 가 빠져
`ERR_UNKNOWN_FILE_EXTENSION` 으로 죽는다는 **한 줄짜리 결함**이었다. 착수 전 실측에서
**훨씬 넓고, 성격이 다른 문제**로 드러났다.

### 실측 — 증상

| 확인한 것 | 결과 |
|---|---|
| `node scripts/workflow/classify-task.ts …` (문서 그대로) | `ERR_UNKNOWN_FILE_EXTENSION` 즉사. 원 주장 **참** |
| `pnpm test:workflow` — `CLAUDE.md` 가 「CI 와 같은 목록」이라 적은 명령 | **62 pass / 20 fail** |
| 실패 20건의 성격 | 전량 `ERR_UNKNOWN_FILE_EXTENSION`. **테스트 실패가 아니라 파일이 안 열린 것** — `.ts` 판별식 전부 |
| 같은 목록에 `--experimental-strip-types` 부착 | **296 pass / 0 fail** |
| 로컬 node | `v22.14.0` |
| 러너 toolcache node (`verify-runner-health.sh` 실측) | **`22.23.2`** — 22.18+ 는 타입 스트리핑 **기본 활성** |
| CI 선언 | `actions/setup-node@v4` · `node-version: 22` → 최신 22.x 로 부유 |
| 버전 핀 (`.nvmrc` · `.node-version` · `mise`) | **전부 부재** |
| `engines` | `node >=22` — 22.14 도 22.18 도 만족한다. **이 경계를 못 잡는다** |

즉 **CI 초록 / 로컬 빨강이 구조적으로 고정**돼 있었다. 로컬에서 `pnpm test:workflow` 를 돌린
사람은 「62개 통과」를 보고 넘어가는데, 실제로는 `.ts` 판별식 20개가 **한 줄도 실행되지 않았다.**

### 실측 — 플래그가 빠진 살아 있는 호출문 (전수)

```
package.json:9                             "classify"       → 즉사
package.json:10                            "test:workflow"  → .ts 20건 전량 미실행   ★
.claude/skills/bts-start/SKILL.md:34       classify-task    → 원 항목이 지목한 곳
scripts/workflow/README.md:16,19           classify-task    → 문서대로 치면 죽는다
scripts/workflow/README.md:82,85           detect-tier      → 파이프 형태도 동일
scripts/workflow/README.md:94              node --test      → 판별식 실행 안내 자체
docs/plans/2026-08-12-debt24-master.md:38  debt-ledger      → 부채 장부 정본
```

플래그가 **붙어 있는** 대조군 — `.claude/skills/bts-codereview/SKILL.md:25` ·
`.github/workflows/backend-ci.yml:197,200` · `scripts/doc-index/mutation-probe.sh:23`.

### 결함 양식

**「`node` 로 `.ts` 를 부르는 호출문 목록」과 「플래그를 든 호출문 목록」이 서로를 안 본다.**
★메모리 `two-lists-never-check-each-other` 의 재발. 처방도 그 항목이 이미 정해 두었다 —
**차집합 판별식 + 비-공허 짝 + CI**.

**가장 뼈아픈 부분.** `scripts/doc-index/mutation-probe.sh:19` 에 이미 이렇게 적혀 있다.

> `★--experimental-strip-types` 없이는 Node 22.14 에서 `.ts` 가 `ERR_UNKNOWN_FILE_EXTENSION` 으로

**한 곳에 적어두고 나머지에 전파하지 않았다.** 사실을 몰라서 생긴 결함이 아니라,
**아는 사실을 강제로 바꾸지 않아서** 생긴 결함이다.

### 선례

`learnings.md` 2026-07-15 (PR #274) — 「검증 장치 자체가 고장 나 있었다」.
그 항목의 예방 ④ 가 이 작업의 한 줄 요약이다.

> **검증 장치가 고장 나면 검증했다는 착각이 검증 부재보다 나쁘다.**

### 착수 시 분류 — classify 정정 4건

`classify-task.ts` 원출력을 그대로 쓰지 않았다. 정정 근거를 남긴다.

| 항목 | 원출력 | 정정 | 근거 |
|---|---|---|---|
| `type` | `backend` | **`chore`** | 프로덕션 Kotlin 0줄 · 하네스/가드 표면 |
| `tier` | `T1` | **`T2`** | `detect-tier.ts` 실측 `TIER: T2`. `package.json` 이 `DEPS` 표면 → 판정 5문 ① 최고 티어 지배 |
| `primary_bc` | `issue-tracking` | **`null`** | `backend/` 0파일. 어느 BC 도 안 건드린다 — 키워드 오판 (같은 오분류 상습 재발) |
| `slug` | `node-ts-experimental-strip-types-nvmrc` | **`node-ts-invocation-flag-guard`** | 원출력이 3번째 범위(판별식 신설)를 빠뜨렸다. 그것이 이 작업의 하중 부재다 |

`detect-tier.ts` 실측 원문 — `TIER: T2` · `SURFACES: TEST, DEPS, HARNESS` ·
**`UNMAPPED: scripts/workflow/README.md`** (판정 5문 ③ — 게이트 2 요약에 그대로 싣는다).

**절차 기록.** 이 체인의 `bts-start` Step 1 자체가 위 결함으로 죽었다. 실패가 아니라 **재현**이며,
플래그를 붙인 형태로 우회해 진행했다.

### 승인된 범위 (Maxi, 게이트 1 이전)

1. **플래그 전수 부착** — 위 목록 전부.
2. **`.nvmrc` 버전 핀** — 로컬과 CI 를 같은 node 로 정렬.
3. **판별식 신설 + 비-공허 확인** — 플래그 없는 호출문을 red 로 만든다.

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
