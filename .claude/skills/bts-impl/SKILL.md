---
name: bts-impl
description: Use when a reviewed plan has been approved by the user (게이트 1) and implementation tasks need to be executed with TDD discipline by sub-agents.
---

# /bts-impl

plan의 task를 sub-agent에게 위임. **TDD 강제 (red → green → refactor)**.

## 선행 읽기 (controller 1회 로드 → sub-agent prompt에 inject)

controller(메인 에이전트)는 다음 4개를 **세션당 1회만 Read**하고, 모든 implementer/verifier prompt에 본문 인라인 주입.

- `DEVELOPMENT.md` (절대 규칙 19개)
- `DATA.md` (데이터 무결성 5원칙)
- `Maxi_wiki/BTS/domain/<bc>.md` (해당 BC 노트)
- 작업 관련 `Maxi_wiki/BTS/decisions/<adr>.md` (있을 때)

**sub-agent는 위 4개 파일을 직접 Read 금지** (controller가 이미 prompt 본문에 첨부함, 중복 로드는 토큰 낭비). 각 agent.md의 "참조 파일" 섹션은 "controller inject" 표시가 있는 항목은 직접 Read 금지, "필요 시 직접 Read" 표시 항목만 직접 Read 가능.

## 절차

### Step 1. subagent-driven-development 진입

```
Skill({
  skill: "superpowers:subagent-driven-development",
  args: "BTS plan 실행. plan 파일: docs/plans/<date>-<slug>.md. controller가 wave 단위로 implementer를 병렬 dispatch + verifier도 wave 묶음 dispatch. TDD 강제."
})
```

controller(메인 에이전트)가 plan을 읽고 wave 계산 → wave별로 반복.

### Step 2-pre. Task wave 계산 (병렬 batch 구성)

plan을 읽고 task별 메타(`agent` / `files` / `depends-on`)를 추출해 wave 계산.

1. **파싱**. 각 task의 `### Task N.` 헤딩 아래 `**메타**.` 블록에서 agent / files / depends-on 추출
2. **엣지 구성**.
   - 명시 엣지. 각 task의 `depends-on: [M, ...]`에서 M → N
   - 파일 충돌 엣지. 두 task의 `files` 교집합 ≠ ∅이면 번호 작은 쪽 → 큰 쪽 추가 (자동 직렬화)
3. **DAG 검증**. cycle 감지 시 BLOCKED → bts-plan loop back (cycle 그래프 첨부)
4. **topological wave**. 진입 차수 0인 task = wave 1 → 그 task들 제거 → 다음 진입 차수 0 = wave 2 → ...
5. **출력 예시**.
   ```
   wave 1 = [Task 1, Task 3]   # 독립 (depends-on 없음, files 안 겹침)
   wave 2 = [Task 2, Task 4]   # Task 1, 3 완료 후
   wave 3 = [Task 5]           # Task 2, 4 완료 후
   ```

**규칙**.
- 같은 wave 안 task는 controller가 **한 메시지에 여러 Agent() 호출**로 동시 dispatch
- 다음 wave는 이전 wave 모든 task가 PASS 된 후에만 진입
- 메타 누락 / 파싱 실패 / cycle 감지 → BLOCKED, bts-plan 재호출

### Step 2. wave별 dispatch 루프

각 wave `w`마다 2-A → 2-B → (필요 시 2-C) → 2-D 순서.

#### 2-A. wave 내 implementer 병렬 dispatch (TDD 강제)

**병렬 발행 규칙**. wave `w`의 모든 task에 대해 **한 응답 안에 여러 Agent() tool 호출**을 동시에 발행. 응답이 두 개로 갈리면 직렬화되어 병렬 이점이 사라짐. controller는 wave 내 모든 응답이 돌아올 때까지 대기한 뒤 2-B 진행.

wave 내 각 task에 대해 다음 형식으로 dispatch (병렬 발행).

```
Agent({
  subagent_type: "<task.agent ?? plan_header.agent ?? 'backend-engineer'>",
  description: "Task N — <task 제목>",
  prompt: """
plan 파일의 Task N을 구현. 작업 디렉토리: .worktrees/<slug>.

**TDD 강제. 다음 순서 절대 지킬 것.**

1. RED. plan의 RED phase 테스트를 작성 → 즉시 커밋 (`test: <slug> task-N red`)
2. 테스트 실행해서 실패 확인. 실패 출력 전체를 보고에 첨부
3. GREEN. plan의 GREEN phase 최소 구현 → 커밋 (`feat: <slug> task-N green`)
4. 테스트 실행해서 통과 확인. 통과 출력 첨부
5. REFACTOR. plan의 REFACTOR phase 정리 → 커밋 (`refactor: <slug> task-N`)
6. 모든 단계에서 절대 규칙 (DEVELOPMENT.md, DATA.md) 준수

**RED 단계 건너뛰면 BLOCKED 처리됨.**

**파일 범위 제약 (병렬 dispatch 안전성).**
이 task가 건드릴 파일은 plan 메타의 `files`에 선언된 것에 한정.
선언 외 파일 수정 시 BLOCKED. 같은 wave의 다른 task와 worktree를 공유하므로
선언 외 파일 수정은 race / drift 위험.

작업 위치: .worktrees/<slug> 절대 경로 안에서만 Edit/Write.
허용 파일: <plan 메타 files 인라인 주입>.
참조 파일: DEVELOPMENT.md, DATA.md, Maxi_wiki/BTS/domain/<bc>.md.

상태 보고. DONE / DONE_WITH_CONCERNS / NEEDS_CONTEXT / BLOCKED.
DONE/DONE_WITH_CONCERNS 보고 시 RED/GREEN 각 commit hash 명시, REFACTOR는 있으면 함께 (controller가 git log와 대조).
"""
})
```

#### 2-B. wave 상태 집계

wave 내 모든 implementer 응답을 모은 뒤 각 task별로 처리.

| implementer 응답 | 다음 동작 |
|---|---|
| `DONE` | 2-D verifier 묶음 dispatch 대상에 포함 |
| `DONE_WITH_CONCERNS` | concern 검토 후 verifier 대상 포함 (concern을 verifier prompt에 포함) |
| `NEEDS_CONTEXT` | 누락된 컨텍스트 보강 후 implementer 재dispatch (해당 task 단일) |
| `BLOCKED` (TDD 위반 / 절대 규칙 / 도메인 모호 / 선언 외 파일 수정) | `superpowers:systematic-debugging` invoke 후 재시도 |

**wave 내 일부 BLOCKED 처리**. wave w에서 일부 task가 BLOCKED 면 그 task만 재시도하고, 나머지 PASS task는 다음 wave 진입 가능 (해당 PASS task에 의존하지 않는 wave w+1 task부터). 단, wave w의 BLOCKED task에 직접 의존하는 wave w+1 task는 대기.

#### 2-C. systematic-debugging (실패 시 자동)

```
Skill({
  skill: "superpowers:systematic-debugging",
  args: "Task N implementer가 BLOCKED. 원인: <BLOCKED 사유>. 로그/에러 출력: <첨부>. 가설 → 검증 → 최소 수정 절차로 진단."
})
```

진단 결과로 implementer 재dispatch (추가 컨텍스트 + 수정 방향).

#### 2-D. wave 내 spec-compliance-verifier 병렬 dispatch (TDD 강제 검증)

wave 내 `DONE` / `DONE_WITH_CONCERNS` task 전부에 대해 검증. **controller가 task별 git log + diff를 먼저 직접 수집한 후 verifier prompt에 인라인 첨부** — verifier가 추측/누락으로 거짓 PASS 응답하는 위험 차단 (LLM 신뢰만으로는 강제 불가).

##### 2-D-pre. controller가 직접 수집 (task당 1회, Bash)

```
Bash({
  command: "cd .worktrees/<slug> && git log --reverse --pretty='%h %s' -- <plan 메타 files 공백 구분> && echo '---DIFF---' && git diff main...HEAD -- <plan 메타 files 공백 구분>"
})
```

수집 출력 예시 (verifier prompt에 그대로 인라인).

```
a1b2c3d test: <slug> task-1 red
e4f5g6h feat: <slug> task-1 green
i7j8k9l refactor: <slug> task-1
---DIFF---
<unified diff>
```

##### 2-D-dispatch. verifier 병렬 호출 (한 응답에 여러 Agent())

```
Agent({
  subagent_type: "general-purpose",
  description: "Task N — spec compliance",
  prompt: """
이 verifier는 **read-only 분석 전용**. 추가 git/Bash 명령 실행 금지. 아래 첨부 데이터로만 판정.

## git log + diff 출력 (controller가 수집, Task N 의 files 한정)
<여기에 2-D-pre의 출력 전체 inline 첨부>

## plan Task N 명세
<plan 파일의 Task N 섹션 전체 inline>

## 판정 기준 (셋 다 확인)

1. **TDD 순서**. 위 git log 출력에서 `test: <slug> task-N red` commit의 hash가 `feat: <slug> task-N green` commit의 hash보다 먼저(위쪽 = 더 오래된)에 있는가? **응답에 두 commit hash를 직접 인용**하여 증거 제시.
2. **drift**. 첨부된 diff가 plan Task N의 RED/GREEN/REFACTOR 명세와 일치하는가? 불일치 항목 나열.
3. **선언 외 파일**. diff에 plan 메타 `files` 외 경로가 등장하는가? 있다면 정당한 사유 명시.

## 응답 형식 (필수, 증거 인용 없이는 PASS 무효)

PASS:
- TDD: test commit `<hash>` (<message>) → feat commit `<hash>` (<message>) 순서 확인됨.
- drift: 없음.
- 선언 외 파일: 없음.

DRIFT:
- TDD: (확인)
- drift 항목: 1. <항목>, 2. <항목>
- 선언 외 파일: <목록 또는 없음>

TDD_VIOLATION:
- 증거: git log 출력에 `test:` commit 없음 OR `feat:` commit이 `test:` commit보다 먼저 등장.
- 인용: <git log 해당 줄 그대로>.

**증거 commit hash 인용 없는 PASS 응답은 controller가 거절하고 verifier 재dispatch**.
"""
})
```

controller는 응답을 받은 후 PASS 응답에 실제 hash 문자열 (`a1b2c3d` 형태)이 포함되어 있는지 검증. 누락 시 TDD_VIOLATION으로 간주.

| verifier 응답 | 동작 |
|---|---|
| `PASS` | plan의 Task N 체크박스 `[x]` → 해당 task wave 졸업 |
| `DRIFT` | drift 항목을 implementer에 전달 → 재dispatch (해당 task 단일) |
| `TDD_VIOLATION` | systematic-debugging 후 implementer 재dispatch (테스트 먼저 작성) |

wave 내 모든 task PASS 면 다음 wave 진입.

### Step 3. 모든 task 완료 후 QA 추가 (조건부)

`classify.type ∈ {feature, auth}` 면 qa-engineer dispatch.

```
Agent({
  subagent_type: "qa-engineer",
  description: "<slug> E2E 시나리오 추가",
  prompt: """
plan의 핵심 시나리오에 대해 Playwright E2E 1~2개 추가.
작업 디렉토리. .worktrees/<slug>. 작성 위치. apps/web/e2e/<slug>.spec.ts.
**구현 코드는 수정 금지** (테스트만).
보고. ADDED / SKIPPED (이미 충분).
"""
})
```

비-feature/auth 작업은 스킵 (단위/통합 테스트로 충분).

### Step 4. verification-before-completion

```
Skill({
  skill: "superpowers:verification-before-completion",
  args: "PR push 전 최종 점검. 작업 디렉토리. .worktrees/<slug>."
})
```

내부에서 다음 실행 (해당 도구가 설치돼 있을 때).

```bash
./gradlew test ktlintCheck detekt   # 백엔드
pnpm typecheck lint test            # 프론트엔드
pnpm test:e2e                       # (qa-engineer 추가 시)
pnpm test:workflow                  # ★ 워크플로우 판별식 — CI 와 같은 목록
```

**★ `pnpm test:workflow` 를 빼지 말 것.** CI(workflow-scripts-ci)가 돌리는 것이 정확히 이
명령이다. 로컬 목록에서 빠지면 로컬과 CI 가 서로를 안 보는 두 목록이 되고, "로컬 초록 → push →
CI 빨강" 이 구조적으로 반복된다([[two-lists-never-check-each-other]]).

새 문서(spec/plan/decision)를 만든 작업이면 인덱스를 먼저 재생성한다. 판별식 룰 I·J 가
이것을 검사한다.

```bash
node scripts/build-doc-index.mjs                    # 파일을 쓴다
git status --porcelain docs/INDEX*.md               # 변경이 있으면
git add docs/INDEX*.md && git commit -m "chore: doc index regen — <slug> 등재"
```

worktree 훅이 연결돼 있으면(`/bts-start` Step 3) 이 재생성을 잊은 커밋은 pre-commit 에서
막힌다 — 위 절차는 그 차단을 푸는 방법이다. 배선 강제는
`scripts/workflow/worktree-hook-wiring.test.ts`.

실패 시 implementer 재dispatch. 통과 시 다음 단계.

### Step 5. 다음 스킬 체이닝

자동으로 `/bts-codereview` 호출.

## 출력 형식

**아래 진행 트리 위에 글로벌 §Explanation Style Work-Report Format(계층형 요약)을 먼저 얹는다.** 진행 트리(wave/dispatch/DRIFT 등 내부 용어)는 상태 표시용으로 유지하되, 그 앞에 비전문가용 `✅ 한 줄`+`💡 의미`를 두어 Maxi가 트리를 읽지 않아도 무엇을·왜 했는지 알게 한다.

```
✅ 한 줄
   이슈에서 "@이름"으로 사람을 부르면 그 사람에게
   알림이 가는 기능을 만들었어요.

💡 의미
   이제 담당자를 콕 집어 부를 수 있어, 놓치는 알림이 줄어요.
   테스트도 통과해서 바로 검토(게이트 2)로 넘어갑니다.

🔧 기술 상세 (안 봐도 됨)
🔄 [6/8] /bts-impl (4 tasks, 2 waves)
   ├─ wave 1 (병렬 dispatch)
   │   ├─ Task 1. parse @username — backend-engineer ✅ PASS (TDD 3 커밋)
   │   └─ Task 3. 권한 체크 — security-engineer ⚠️ DRIFT 1회 → 재dispatch ✅ PASS
   ├─ wave 2 (병렬 dispatch, depends-on [1, 3])
   │   ├─ Task 2. MentionNotificationService — backend-engineer ✅ PASS
   │   └─ Task 4. 알림 채널 라우팅 — backend-engineer ✅ PASS
   ├─ E2E 추가. qa-engineer → apps/web/e2e/issue-mention-notify.spec.ts (2 시나리오)
   └─ verification-before-completion ✅ (test 47 passed, lint clean)
```

## 실패 / 엣지 케이스

- **TDD 위반 3회 반복**. implementer가 자꾸 GREEN 먼저 한다 → Maxi에게 보고, 해당 agent의 prompt 가이드 강화 필요 표시
- **verification 실패 (lint 위반)**. implementer 재dispatch (lint fix 전용)
- **qa-engineer가 "이미 충분"이라 SKIP**. plan에 "E2E 생략 사유" 기록
- **systematic-debugging이 "도메인 모델 잘못됨"으로 결론**. 작업 중단 → `/bts-domain` loop back (드문 케이스)
- **plan 메타 누락 / 파싱 실패**. Step 2-pre에서 BLOCKED → `/bts-plan` loop back (메타 블록 강제 가이드 prompt 주입)
- **depends-on 순환 참조**. Step 2-pre cycle 감지 → `/bts-plan` loop back (cycle 그래프 첨부)
