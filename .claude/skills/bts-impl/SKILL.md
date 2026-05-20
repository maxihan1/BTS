---
name: bts-impl
description: Use when a reviewed plan has been approved by the user (게이트 1) and implementation tasks need to be executed with TDD discipline by sub-agents.
---

# /bts-impl

plan의 task를 sub-agent에게 위임. **TDD 강제 (red → green → refactor)**.

## 선행 읽기 (sub-agent별로 다름)

controller(메인 에이전트)는 모든 task에 공통으로 다음을 prompt 인라인 주입.

- `DEVELOPMENT.md` (절대 규칙 18개)
- `DATA.md` (데이터 무결성 5원칙)
- `Maxi_wiki/BTS/domain/<bc>.md` (해당 BC 노트)
- 작업 관련 `Maxi_wiki/BTS/decisions/<adr>.md` (있을 때)

sub-agent별 추가 읽기는 해당 agent.md의 "참조 파일" 섹션 참조.

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

**agent=null 처리** (`classify.type == "unknown"` 또는 Maxi가 reclassify 거부 시).
- fallback. `backend-engineer`로 dispatch (모듈러 모놀리스 기본 영역)
- prompt 맨 위에 "type 분류 모호함. 구현 전 작업 의도/영역을 한 번 더 확인하고 보고" 한 줄 추가

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

#### 2-D. wave 내 spec-compliance-verifier 병렬 dispatch

wave 내 `DONE` / `DONE_WITH_CONCERNS` task 전부에 대해 **한 응답 안에 여러 Agent() 호출**로 동시 dispatch. verifier는 read-only (git log + diff 분석)라 worktree 동시 접근 안전.

```
Agent({
  subagent_type: "general-purpose",
  description: "Task N — spec compliance",
  prompt: """
다음을 확인하고 보고.

1. git log에서 Task N 의 `test:` 커밋이 `feat:` 커밋보다 먼저 있는가? (TDD 검증, Task N 의 files 한정해 `git log -- <files>`)
2. 변경 diff가 plan Task N의 명세와 일치하는가? (drift 검증)
3. plan 메타 `files` 외 파일 수정이 있는가? 있다면 정당한가?

작업 디렉토리. .worktrees/<slug>. plan 파일. docs/plans/<date>-<slug>.md.
**코드 품질 / 절대 규칙 검증은 안 함** (PR 단위 코드 리뷰가 담당).
보고. PASS / DRIFT / TDD_VIOLATION.
"""
})
```

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
작업 디렉토리. .worktrees/<slug>. 작성 위치. tests/e2e/<slug>.spec.ts.
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
```

실패 시 implementer 재dispatch. 통과 시 다음 단계.

### Step 5. 다음 스킬 체이닝

자동으로 `/bts-codereview` 호출.

## 출력 형식

```
🔄 [6/7] /bts-impl (4 tasks, 2 waves)
   ├─ wave 1 (병렬 dispatch)
   │   ├─ Task 1. parse @username — backend-engineer ✅ PASS (TDD 3 커밋)
   │   └─ Task 3. 권한 체크 — security-engineer ⚠️ DRIFT 1회 → 재dispatch ✅ PASS
   ├─ wave 2 (병렬 dispatch, depends-on [1, 3])
   │   ├─ Task 2. MentionNotificationService — backend-engineer ✅ PASS
   │   └─ Task 4. 알림 채널 라우팅 — backend-engineer ✅ PASS
   ├─ E2E 추가. qa-engineer → tests/e2e/issue-mention-notify.spec.ts (2 시나리오)
   └─ verification-before-completion ✅ (test 47 passed, lint clean)
```

## 실패 / 엣지 케이스

- **TDD 위반 3회 반복**. implementer가 자꾸 GREEN 먼저 한다 → Maxi에게 보고, 해당 agent의 prompt 가이드 강화 필요 표시
- **verification 실패 (lint 위반)**. implementer 재dispatch (lint fix 전용)
- **qa-engineer가 "이미 충분"이라 SKIP**. plan에 "E2E 생략 사유" 기록
- **systematic-debugging이 "도메인 모델 잘못됨"으로 결론**. 작업 중단 → `/bts-domain` loop back (드문 케이스)
- **plan 메타 누락 / 파싱 실패**. Step 2-pre에서 BLOCKED → `/bts-plan` loop back (메타 블록 강제 가이드 prompt 주입)
- **depends-on 순환 참조**. Step 2-pre cycle 감지 → `/bts-plan` loop back (cycle 그래프 첨부)
- **wave 내 일부 task BLOCKED**. 해당 task만 systematic-debugging + 재dispatch. 그 task에 의존하지 않는 다음 wave task는 선진입 가능
- **선언 외 파일 수정 (병렬 안전성 위반)**. implementer BLOCKED 처리. plan 메타 `files` 갱신이 진짜 필요한지 검토 후 재dispatch (drift 가능성 우선 의심)
- **wave 1 task 수 == 전체 task 수 (의존성/파일 충돌 전혀 없음)**. 전 task 1-shot 병렬. 가장 빠른 케이스. plan이 잘 분해됨
