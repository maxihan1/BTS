---
name: bts
description: Use when user gives a natural-language coding request for BTS — feature add, bug fix, refactor, new module. Skip for read-only questions, code explanation, or continuing existing work that doesn't start a new branch.
---

# /bts

BTS 모든 코드 작업의 **단일 진입점**. 7단계 스킬을 자연어 1줄로 압축.

## 언제 호출되나

- 사용자가 `/bts <자연어>` 명시 호출
- 사용자가 자연어로 코드 변경 요청 (예. "이슈에 멘션 알림 추가") → 메인 에이전트가 자동 진입

**호출하지 않는 경우**.
- 단순 질문 (예. "이 함수 뭐 해?") — 그냥 답변
- 코드 탐색 / 설명 — 그냥 진행
- 기존 작업 이어가기 — 사용자가 명시적으로 다른 스킬 호출

## 사용자 승인 게이트 (2 곳)

```
/bts <자연어>
   ↓ [자동 선행 읽기] Maxi_wiki/BTS/_index + history(최근10) + learnings(최근5)
[1] /bts-start         → classify + worktree + Draft PR
[2] /bts-domain        → grill-with-docs
[3] /bts-spec          → office-hours (A) → brainstorming (B)
[4] /bts-plan          → writing-plans (TDD task 분해)
[5] /bts-review-plan   → 타입별 리뷰 체인
🛑 게이트 1 — Maxi 검토 (도메인/스펙/계획 일괄)
[6] /bts-impl          → subagent-driven + TDD 강제
[7] /bts-codereview    → code-reviewer + /review (gstack)
🛑 게이트 2 — Maxi 검토 (BLOCKER)
[자동] verify → merge → worktree 정리 + sync-obsidian
```

`auth`/`migration`/큰 변경도 두 번 멈춤. **자동이라도 사용자 동의 없이 머지 안 감**.

## 절차

### Phase A. 입력 분석 + 세션 복원

#### A-0. 활성 작업 감지 (모든 입력 형태에 선행, 필수)

이전 세션이 중단된 채 남긴 worktree / draft PR을 감지. 발견되면 사용자 확인 없이 진행 금지.

```bash
ACTIVE_WORKTREES=$(ls -d .worktrees/*/ 2>/dev/null)
ACTIVE_DRAFT_PRS=$(gh pr list --draft --author @me --json number,title,headRefName 2>/dev/null)
```

둘 중 하나라도 비어있지 않으면 `AskUserQuestion`.

```
"진행 중인 작업이 감지되었습니다 (worktree: N개, draft PR: M개). 어떻게 할까요?"
- 옵션 1. 이전 작업 이어가기 → 해당 worktree로 진입 + plan 파일 상태 기반 다음 단계 추정
- 옵션 2. 새 작업 시작 → 이전은 그대로 유지, 새 worktree 추가 생성
- 옵션 3. 이전 작업 폐기 → worktree 삭제 + draft PR close
```

**"이어가기" 선택 시** — `docs/plans/<date>-<slug>.md`의 채워진 섹션을 읽어 다음 단계 추정 (`bts-start`는 스킵, worktree 재생성 안 함).

| plan 섹션 상태 | 재진입 단계 |
|---|---|
| `## 도메인 정리` 비어있음 | `bts-domain` |
| `## 스펙` 비어있음 | `bts-spec` |
| `## Plan` 비어있음 | `bts-plan` |
| `## 리뷰 결과` 비어있음 | `bts-review-plan` |
| 모두 채워졌고 PR `Draft` | 게이트 1 재진입 (또는 commit 1개 이상이면 `bts-impl` 진행 중으로 간주, 사용자에 확인) |
| PR `Ready for review` | 게이트 2 재진입 |

**"새 작업 시작" / 활성 작업 없음** → A-1로 진행.

#### A-1. 신규 입력 분석

| 입력 형태 | 처리 |
|---|---|
| **빈 입력** | `gh pr list --state open` 후 "어떤 PR 이어서 작업?" |
| **모호한 입력** (30자 미만 + 동사만) | AskUserQuestion으로 3 옵션 제시 |
| **구체적 입력** | 그대로 진행 |

### Phase B. 선행 읽기 (필수)

다음 3개 Obsidian 노트를 Read tool로 로드. 모든 단계의 컨텍스트 기준.

- `/Users/maxi.moff/Maxi_wiki/BTS/_index.md`
- `/Users/maxi.moff/Maxi_wiki/BTS/history.md` (마지막 50줄)
- `/Users/maxi.moff/Maxi_wiki/BTS/learnings.md`

### Phase C. 단계 체이닝 (호출 책임 = bts 컨트롤러)

**bts 컨트롤러는 각 단계 응답을 받은 후 명시적으로 다음 `Skill()`을 호출.** "다음 단계가 자동으로 호출된 셈" 가정 금지 — 명시적 호출 없으면 진행 안 함.

1. `Skill({skill: "bts-start"})` → classify 결과를 `.bts-cache/classify.json` 저장
2. `Skill({skill: "bts-domain"})` (fast-track 시 스킵)
3. `Skill({skill: "bts-spec"})` (fast-track 시 스킵)
4. `Skill({skill: "bts-plan"})`
5. `Skill({skill: "bts-review-plan"})` (fast-track 시 스킵)

#### 🛑 게이트 1 (plan 산출물 요약 → `AskUserQuestion`)

응답 분기 — bts 컨트롤러가 직접 처리.

| 응답 | bts 컨트롤러 동작 |
|---|---|
| `승인` | 즉시 `Skill({skill: "bts-impl"})` 호출 → 응답 후 `Skill({skill: "bts-codereview"})` 호출 |
| `수정 요청` | "어느 섹션?" `AskUserQuestion` → 해당 단계 (`bts-plan` / `bts-spec` / `bts-domain`) 재호출 → 게이트 1 재진입 |
| `중단` | 워크플로우 종료. worktree + draft PR 유지 (재진입 가능) |

#### 🛑 게이트 2 (PR diff + 리뷰 결과 요약 → `AskUserQuestion`)

| 응답 | bts 컨트롤러 동작 |
|---|---|
| `승인` | `bts-codereview`의 "머지 후 자동 처리" 섹션 실행 (gh pr merge → worktree 정리 → sync-obsidian) |
| `수정 후 재리뷰` | concerns 첨부해 `Skill({skill: "bts-impl"})` 재호출 → `bts-codereview` 재호출 → 게이트 2 재진입 |
| `보류` | 워크플로우 일시 중단. draft PR + worktree 유지 (다음 `/bts` 호출 시 Phase A-0 복원 경로로 재진입) |

### Phase D. 진행 상황 출력

각 자동 단계마다 1줄 출력 (사용자가 black box 느낌 방지).

```
🔄 [1/7] 분류 중... → type=feature, agent=backend-engineer, tasks=4 (cached)
🔄 [2/7] 도메인 정리 중... → glossary 신규 용어 0건, ADR 0건
🔄 [3/7] 스펙 작성 중 (Phase A office-hours)...
🔄 [3/7] 스펙 검증 중 (Phase B brainstorming)...
...
```

## 실패 / 엣지 케이스

- **classify가 모호**. `type = unknown` 시 Maxi에게 "이 작업의 타입은?" AskUserQuestion
- **worktree 충돌**. 동일 slug가 이미 있으면 `-2` 접미사 자동
- **plan-* 리뷰 BLOCKER**. 중단 후 사용자에게 수정안 제시. 승인 후 리뷰 재실행
- **TDD 강제 위반**. spec-compliance-verifier가 BLOCKER 반환 → implementer 재dispatch
- **머지 충돌**. 머지 전 `git pull --rebase origin main` 자동, 충돌 시 사용자 개입

## 관련 스킬

- 전체 개요. [bts-workflow](../bts-workflow/SKILL.md)
- 각 단계. [bts-start](../bts-start/SKILL.md), [bts-domain](../bts-domain/SKILL.md), [bts-spec](../bts-spec/SKILL.md), [bts-plan](../bts-plan/SKILL.md), [bts-review-plan](../bts-review-plan/SKILL.md), [bts-impl](../bts-impl/SKILL.md), [bts-codereview](../bts-codereview/SKILL.md)
