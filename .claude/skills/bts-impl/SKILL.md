---
name: bts-impl
description: 구현 단계. superpowers:subagent-driven-development를 사용해 task마다 implementer(TDD 강제) → spec-compliance-verifier 루프. 실패 시 systematic-debugging 자동 invoke. 모든 task 완료 후 verification-before-completion. /bts-review-plan 게이트 1 통과 후 호출.
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
  args: "BTS plan 실행. plan 파일: docs/plans/<date>-<slug>.md. controller가 task별로 implementer + spec-compliance-verifier를 순차 dispatch. TDD 강제."
})
```

controller(메인 에이전트)가 plan을 읽고 다음을 반복.

### Step 2. task별 dispatch 루프

각 task `N`마다.

#### 2-A. implementer dispatch (TDD 강제)

```
Agent({
  subagent_type: "<classify.agent>",  # backend-engineer / frontend-engineer / etc.
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

작업 위치: .worktrees/<slug> 절대 경로 안에서만 Edit/Write.
참조 파일: DEVELOPMENT.md, DATA.md, Maxi_wiki/BTS/domain/<bc>.md.

상태 보고. DONE / DONE_WITH_CONCERNS / NEEDS_CONTEXT / BLOCKED.
"""
})
```

#### 2-B. 상태 처리

| implementer 응답 | 다음 동작 |
|---|---|
| `DONE` | spec-compliance-verifier로 |
| `DONE_WITH_CONCERNS` | concern 검토 후 verifier로 (concern을 verifier prompt에 포함) |
| `NEEDS_CONTEXT` | 누락된 컨텍스트 보강 후 implementer 재dispatch |
| `BLOCKED` (TDD 위반 / 절대 규칙 / 도메인 모호) | `superpowers:systematic-debugging` invoke 후 재시도 |

#### 2-C. systematic-debugging (실패 시 자동)

```
Skill({
  skill: "superpowers:systematic-debugging",
  args: "Task N implementer가 BLOCKED. 원인: <BLOCKED 사유>. 로그/에러 출력: <첨부>. 가설 → 검증 → 최소 수정 절차로 진단."
})
```

진단 결과로 implementer 재dispatch (추가 컨텍스트 + 수정 방향).

#### 2-D. spec-compliance-verifier dispatch

```
Agent({
  subagent_type: "general-purpose",
  description: "Task N — spec compliance",
  prompt: """
다음을 확인하고 보고.

1. git log에서 `test:` 커밋이 `feat:` 커밋보다 먼저 있는가? (TDD 검증)
2. 변경 diff가 plan Task N의 명세와 일치하는가? (drift 검증)
3. plan 외 다른 파일 수정이 있는가? 있다면 정당한가?

작업 디렉토리. .worktrees/<slug>. plan 파일. docs/plans/<date>-<slug>.md.
**코드 품질 / 절대 규칙 검증은 안 함** (PR 단위 코드 리뷰가 담당).
보고. PASS / DRIFT / TDD_VIOLATION.
"""
})
```

| verifier 응답 | 동작 |
|---|---|
| `PASS` | plan의 Task N 체크박스 `[x]` → 다음 task |
| `DRIFT` | drift 항목을 implementer에 전달 → 재dispatch |
| `TDD_VIOLATION` | systematic-debugging 후 implementer 재dispatch (테스트 먼저 작성) |

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
🔄 [6/7] /bts-impl (4 tasks)
   ├─ Task 1: parse @username — backend-engineer ✅ PASS (TDD: red→green→refactor 3 커밋)
   ├─ Task 2: MentionNotificationService — backend-engineer ✅ PASS
   ├─ Task 3: 권한 체크 — security-engineer ⚠️ DRIFT 1회 → 재dispatch ✅ PASS
   ├─ Task 4: 알림 채널 라우팅 — backend-engineer ✅ PASS
   ├─ E2E 추가: qa-engineer → tests/e2e/issue-mention-notify.spec.ts (2 시나리오)
   └─ verification-before-completion ✅ (test 47 passed, lint clean)
```

## 실패 / 엣지 케이스

- **TDD 위반 3회 반복**. implementer가 자꾸 GREEN 먼저 한다 → Maxi에게 보고, 해당 agent의 prompt 가이드 강화 필요 표시
- **verification 실패 (lint 위반)**. implementer 재dispatch (lint fix 전용)
- **qa-engineer가 "이미 충분"이라 SKIP**. plan에 "E2E 생략 사유" 기록
- **systematic-debugging이 "도메인 모델 잘못됨"으로 결론**. 작업 중단 → `/bts-domain` loop back (드문 케이스)
