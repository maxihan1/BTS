---
name: bts
description: BTS 프로젝트의 단일 진입점. 자연어 1줄을 받아 classify → worktree → domain → spec → plan → review → 사용자 승인 → impl(TDD) → codereview → 사용자 승인 → merge까지 자동 체이닝. 사용자는 명시적 승인 게이트 2 곳에서만 개입.
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

### Phase A. 입력 분석

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

### Phase C. 단계 체이닝

1. `/bts-start` 호출 → `classify` 결과를 `.bts-cache/classify.json`에 저장 (이후 단계 재사용)
2. `/bts-domain` → `/bts-spec` → `/bts-plan` → `/bts-review-plan` 자동 체이닝
3. 🛑 게이트 1. plan 파일 경로 + 4종 산출물 요약 출력 → "계획 OK?" AskUserQuestion
4. 승인 시 `/bts-impl` → `/bts-codereview` 체이닝
5. 🛑 게이트 2. PR diff 요약 + 리뷰 결과 → "머지 OK?" AskUserQuestion
6. 승인 시 merge + worktree 정리 + sync-obsidian

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
