---
name: bts-workflow
description: Use when another bts-* skill references this for the overall workflow map, or when looking up which step/skill handles a given task type, or when onboarding to BTS workflow conventions.
---

# BTS 워크플로우 개요

```
사용자 자연어 → /bts
  ↓ 선행 읽기 (_index, history, learnings)
[1] /bts-start         (classify + worktree + Draft PR)
[2] /bts-domain        (grill-with-docs)
[3] /bts-spec          (office-hours → brainstorming)
[4] /bts-plan          (writing-plans, TDD)
[5] /bts-review-plan   (타입별 plan-*-review)
  ↓ 🛑 게이트 1
[6] /bts-impl          (subagent-driven + TDD 강제)
[7] /bts-codereview    (code-reviewer agent + /review)
  ↓ 🛑 게이트 2
[자동] merge → worktree 정리 + sync-obsidian (history/decisions append)
```

## 스킬 매핑 (얇은 오케스트레이션)

각 `/bts-*`은 외부 스킬을 호출하는 얇은 래퍼. 핵심 로직은 외부 스킬이 담당.

| 단계 | bts-* 스킬 | 호출하는 외부 스킬 |
|---|---|---|
| 1 | `/bts-start` | `superpowers:using-git-worktrees`, (`office-hours` builder mode, feature 신규 시) |
| 2 | `/bts-domain` | `grill-with-docs` |
| 3 | `/bts-spec` | `office-hours` (Phase A), `superpowers:brainstorming` (Phase B), (`design-consultation` 첫 UI 시), (`design-shotgun` ui 타입) |
| 4 | `/bts-plan` | `superpowers:writing-plans` |
| 5 | `/bts-review-plan` | `plan-ceo-review`, `plan-design-review`, `plan-eng-review`, `plan-devex-review`, `/autoplan` |
| 6 | `/bts-impl` | `superpowers:subagent-driven-development`, `superpowers:test-driven-development`, `superpowers:systematic-debugging` (실패 시), `superpowers:verification-before-completion` (완료 시) |
| 7 | `/bts-codereview` | `superpowers:code-reviewer` (agent), `/review` (gstack) |

## 작업 상태 추적

| 상태 | 단일 진실 공급원 |
|---|---|
| 진행 중 | 체크아웃된 worktree (`.worktrees/<slug>`) + open PR |
| 리뷰 대기 | open PR + `gh pr ready` (draft 해제) |
| 머지 후 | `docs/completed-work-log.md` + `Maxi_wiki/BTS/history.md` (post-merge hook) |
| 에픽 단위 | `docs/epics/<slug>.md` (PR 라벨 `epic:<slug>` 시 동일 hook) |

## 타입별 분기 (`scripts/workflow/classify-task.ts`)

| 신호 | 타입 | 담당 agent | plan 리뷰 |
|---|---|---|---|
| `backend/modules/identity-access/**`, `auth`/`2fa`/`saml` 키워드 | **auth** | security-engineer | plan-eng + plan-ceo |
| `backend/modules/` 기타 (issue-tracking, workflow, automation, notification) | **backend** | backend-engineer | plan-eng |
| `apps/web/**`, `*.tsx`, `page.tsx` | **ui** | frontend-engineer | plan-design |
| `packages/ui/`, 신규 shadcn 컴포넌트, "목업"/"시안"/"디자인" 키워드 | **design** | designer (→ frontend-engineer 핸드오프) | plan-design |
| `backend/db/migration/**`, `V*__*.sql` | **migration** | db-engineer | plan-eng + plan-ceo |
| `apps/web/src/api/`, `backend/modules/*/api/`, "엔드포인트"/"REST" 키워드 | **api** | backend-engineer + designer 검토 | plan-eng + plan-devex |
| `tests/`, `tests/e2e/`, "playwright"/"vitest" 키워드 | **qa** | qa-engineer | skip |
| `fix:`/`refactor:` 접두사 | **bugfix** | (auto, 영향 영역 따름) | skip (fast-track) |
| `chore:`/`docs:`/`style:` 접두사 | **chore** | (auto) | skip (fast-track) |
| "만들어줘"/"추가"/"신규 기능" + 새 BC 또는 다중 BC | **feature** | (auto, 영향 영역 따름) | /autoplan (task ≥ 3 시) |

## 핵심 원칙

1. **PR + worktree = 작업 단위** — 어떤 Edit/Write도 worktree 없이 main에 적용 금지
2. **TDD 강제 (`/bts-impl`)** — `test:` 커밋이 `feat:` 커밋보다 먼저 있어야 함 (verifier가 git log 검증)
3. **코드 리뷰는 PR 단위 1회** — task별 quality review 없음, spec-compliance만 가벼운 drift 감지
4. **`auth`/`migration` 작업** 특별 취급 — plan-eng + plan-ceo 둘 다, codereview에 추가 가이드 첨부
5. **`feature` + task ≥ 3** — `/autoplan` 자동 진입 (4종 리뷰 + 결정 게이트)
6. **첫 실질 커밋 시 Draft PR 자동 개설** (`/bts-start`이 처리)

## Obsidian 단방향 동기화

| 방향 | 시점 | 동작 |
|---|---|---|
| Repo → Obsidian | 머지 직후 (Phase 0. 수동 / Phase 1. hook + sync-obsidian.ts) | history append + plans/decisions 복사 |
| Obsidian → Repo | **없음** | Maxi의 사고 공간은 코드 영역에 반영 안 됨 |

**Phase 0 PoC 단계**. `scripts/workflow/sync-obsidian.ts` 미존재. `/bts-codereview` 머지 후 단계가 수동으로 history/decisions/plans을 미러 (해당 SKILL.md "Obsidian 동기화" 섹션 참조).

**Phase 1 도입 예정**. sync-obsidian.ts + post-merge hook 자동화. 첫 머지 1회 수동 처리 후 패턴 학습한 다음 스크립트 작성.

각 단계가 진입 시 읽는 Obsidian 노트는 해당 `/bts-*` SKILL.md 참조.

## 변경 이력

- 2026-05-19. 초안 (Phase 0 PoC 진입 전). AIG `aig-workflow` 패턴 + 9개 보강 반영.
