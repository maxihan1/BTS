---
name: bts-impl
description: Called by /bts step 5 — executes the approved plan's tasks with TDD discipline via sub-agents. Never invoke directly.
---

# /bts-impl

체인 [5]. plan 의 task 를 sub-agent 에게 위임한다. **TDD 강제 (red → green → refactor)**.
T0/T1 은 이 스킬에 들어오지 않는다 — `/bts` 컨트롤러가 직접 편집하고 규율만 티어 표를 따른다.

## 선행 읽기 (controller 1회 로드 → sub-agent prompt 에 인라인 주입)

controller(메인 에이전트)가 **세션당 1회만** Read 하고 모든 implementer/verifier prompt 에 본문을 붙인다. **sub-agent 는 이 5개를 직접 Read 금지** — 중복 로드는 토큰 낭비다.

- `DEVELOPMENT.md` — **§1(절대 규칙) + task 언어의 §2.x 만**(Kotlin → §2.1, TS → §2.2. 전문 주입 금지)
- `DATA.md` — **db/backend/api/auth/migration task 에만 전문**. 프론트·qa 는 5원칙 요지 1줄
- `Maxi_wiki/BTS/domain/<bc>.md` · 작업 관련 `Maxi_wiki/BTS/decisions/<adr>.md`(있을 때)
- `docs/rules/wave-protocol.md` — 병렬 wave 규약 정본 (에이전트 정의에는 포인터만 있다)

## 타입별 규율

| 타입 | 구현 규율 | 필수 검증 |
|---|---|---|
| auth · migration · backend · api · feature | TDD red→green→refactor | 현행 + (auth/migration) ceo 리뷰 |
| **ui** (기존 화면 수정) | **시각 검증 트랙 — red-first 면제** | 관련 기존 E2E 동반 실행 + 브라우저 눈확인(라이트/다크) + `test:` 커밋 존재 |
| bugfix | TDD 유지 — **재현 테스트 먼저** | 현행 |
| chore | 해당 표면의 기존 테스트 | 현행 |

**ui 시각 검증 트랙**(`jira-parity-contract.md` 배선). ① 착수 전 계약 §5 사전 grep — 수정 표면이 노출된 기존 E2E/유닛 어서션 전수 식별 ② 구현 커밋 + **동반 테스트 커밋**(순서 무관, 단 `test:` 커밋 자체는 필수) ③ ①에서 식별한 기존 E2E 동반 실행, 로그를 보고에 첨부 ④ 브라우저 눈확인(계약 §6) — 라이트/다크 양쪽.
로직 변경(핸들러·유틸·api 클라이언트 분기 추가 등)이 섞인 ui task 는 **그 부분만 TDD 현행**을 따른다. 모호하면 TDD 가 기본값.

## Step 1. wave 계산

plan 의 task 메타(`agent` / `files` / `depends-on`)로 실행 순서를 잡는다.

- **엣지 2종** — 명시 `depends-on`, 그리고 **`files` 교집합이 있으면 자동 직렬화**(번호 작은 쪽 → 큰 쪽).
- **wave** — 진입 차수 0 인 task 묶음이 wave 1, 그것을 뺀 뒤 다시 0 인 묶음이 wave 2 …
- **메타 누락 · 파싱 실패 · cycle 감지 → BLOCKED**, `/bts-plan` loop back(cycle 그래프 첨부).

## Step 2. wave 별 dispatch 루프

각 wave 마다 2-A(implementer) → 2-B(집계) → 2-C(verifier) 순서.

#### 2-A. implementer 병렬 dispatch

- wave 의 모든 task 를 **한 응답 안에 여러 `Agent()` 호출**로 동시 발행한다. 응답이 갈리면 직렬화돼 병렬 이점이 사라진다.
- `subagent_type` = `task.agent ?? plan_header.agent ?? 'backend-engineer'`.
- 프롬프트 본문은 참조 파일을 그대로 쓴다. **선행 읽기 주입은 controller 가 1회** 만든 것을 재사용한다.
- 파일 겹침이 있는 task 는 같은 wave 에 넣지 않는다(Step 1 에서 이미 직렬화됨).
- 프롬프트 정본 2종(TDD · ui 시각 트랙). [dispatch-prompt.md](dispatch-prompt.md)

#### 2-B. wave 상태 집계

| implementer 응답 | 다음 동작 |
|---|---|
| `DONE` | 2-C verifier 대상에 포함 |
| `DONE_WITH_CONCERNS` | concern 을 verifier prompt 에 실어 대상 포함 |
| `NEEDS_CONTEXT` | 누락 컨텍스트 보강 후 해당 task 단일 재dispatch |
| `BLOCKED` (TDD 위반 / 절대 규칙 / 도메인 모호 / 선언 외 파일 수정) | 아래 복구 규율로 재dispatch |

**BLOCKED 복구 규율.** 재dispatch 프롬프트에 **시도한 것 / 관찰한 것 / 가설 2개 / 필요한 정보** 네 항목을 반드시 채운다 — 이것 없이 다시 던지면 같은 실패가 반복된다.
wave 안 일부만 BLOCKED 면 그 task 만 재시도하고, BLOCKED task 에 의존하지 않는 다음 wave task 는 진입 가능하다.

#### 2-C. spec-compliance-verifier 병렬 dispatch

- `DONE` / `DONE_WITH_CONCERNS` task 전부를 검증한다. **controller 가 task 별 git log + diff 를 먼저 직접 수집해 verifier prompt 에 인라인 첨부** — verifier 가 추측으로 거짓 PASS 를 내는 경로를 막는다.
- verifier 는 **read-only 분석 전용**. 추가 Bash 실행 금지.
- PASS 응답에 **실제 commit hash 문자열이 인용돼 있지 않으면 controller 가 거절**하고 재dispatch 한다.
- 응답 `PASS` → 해당 task 졸업 / `DRIFT` → drift 항목 전달해 재dispatch / `TDD_VIOLATION`(ui 트랙은 `TRACK_VIOLATION`) → 복구 규율로 재dispatch.
- 프롬프트·판정 기준·응답 형식 정본. [verifier-contract.md](verifier-contract.md)

wave 내 모든 task 가 PASS 면 다음 wave 로.

## Step 3. QA 추가 (조건부)

`classify.type ∈ {feature, auth}` 면 `qa-engineer` 를 dispatch 해 핵심 시나리오 Playwright E2E 1~2개를 `apps/web/e2e/<slug>.spec.ts` 에 추가한다. **구현 코드 수정 금지**(테스트만). 보고는 ADDED / SKIPPED.
그 밖의 타입은 스킵 — 단위·통합 테스트로 충분하다.

## Step 4. PR push 전 최종 점검

**아래 명령을 종료 코드로 판정한다.** 사고 배경 · `pnpm install` 금지 · 인덱스 재생성 절차 전문. [worktree-commands.md](worktree-commands.md)

```bash
./gradlew test ktlintCheck detekt   # 백엔드
pnpm typecheck lint test            # 프론트엔드
pnpm test:e2e                       # (qa-engineer 추가 시)
pnpm test:workflow                  # ★ 워크플로우 판별식 — CI 와 같은 목록
```

**★★worktree 에서는 `pnpm` 래퍼가 죽는다** — 심볼릭 `node_modules` 를 보고 의존성 검사를 돌려 `ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY` 로 끝난다(2026-08-12 실측). 바이너리를 직접 부른다.

```bash
# 워크플로우 판별식 — 위 `pnpm test:workflow` 와 **같은 파일 목록**을 돈다
node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'
echo "EXIT=$?"

node_modules/.bin/lint-staged
apps/web/node_modules/.bin/vitest run    # 루트에 없는 도구는 후자에만 있다
```

**★두 명령의 파일 목록이 같아야 한다.** `package.json` 의 `test:workflow` 가 바뀌었는데 대체 명령이 안 따라오면 **로컬이 CI 보다 적게 돌면서 초록**이 된다 — `worktree-hook-wiring.test.ts` 가 글로브를 `package.json` 에서 직접 읽어 대조한다.

**★★판정은 종료 코드로 한다 — 통과 건수로 하지 않는다.** 「Tests N passed」와 「EXIT=1」은 같은 실행에서 동시에 참일 수 있다(FR-UX-09 F2 세션이 건수만 읽고 초록 보고 → 게이트 2 에서 교정).

```bash
pnpm test > /tmp/test.log 2>&1   # ✅ 출력이 길면 파일로 — 종료 코드는 그대로 남는다
echo "EXIT=$?"; tail -30 /tmp/test.log

pnpm test 2>&1 | tail -20        # ❌ 파이프는 셸이 보고하는 종료 코드를 tail 의 것으로 바꾼다
```

보고에는 **통과 건수와 종료 코드를 함께** 적는다. 새 문서(spec/plan/decision)를 만든 작업이면 `node scripts/build-doc-index.mjs` 로 인덱스를 먼저 재생성한다 — pre-commit `--check` 차단을 푸는 명령이다.

## Step 5. 체이닝

`/bts` 컨트롤러에 반환 → 컨트롤러가 `/bts-codereview` 호출.

## 출력 형식

**진행 트리 위에 글로벌 §Explanation Style 계층형 요약을 먼저 얹는다.** 트리의 내부 용어(wave·dispatch·DRIFT)를 읽지 않아도 무엇을·왜 했는지 알게 한다.

```
✅ 한 줄  <비전문가 한 문장 — 무엇이 됐나>
💡 의미  <Maxi 에게 무슨 뜻인지 + 다음 단계>
🔧 기술 상세 (안 봐도 됨)
🔄 [5/7] /bts-impl (4 tasks, 2 waves)
   ├─ wave 1  Task 1 ✅ PASS (TDD 3 커밋) · Task 3 ⚠️ DRIFT 1회 → 재dispatch ✅ PASS
   ├─ wave 2  Task 2 ✅ PASS · Task 4 ✅ PASS
   ├─ E2E 추가. qa-engineer → apps/web/e2e/<slug>.spec.ts (2 시나리오)
   └─ 최종 점검 ✅ (test 47 passed, EXIT=0, lint clean)
```

## 실패 / 엣지 케이스

- **같은 실패 3회 반복**. 중단하고 Maxi 에게 보고한다. T0/T1 이었다면 보고에 「티어 재판정 제안」을 포함한다
- **추측성 대규모 수정 금지**. 원인을 못 짚었으면 범위를 넓히지 말고 BLOCKED 로 보고한다
- **verification 실패(lint 위반)**. implementer 재dispatch(lint fix 전용)
- **plan 메타 누락 · cycle**. Step 1 에서 BLOCKED → `/bts-plan` loop back
- **도메인 모델이 틀렸다는 결론**. 작업 중단 → `/bts-spec` loop back (드문 케이스)
