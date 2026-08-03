# BTS 워크플로우 맵

> `/bts` 체인의 참조 지도. 실행 절차의 정본은 각 `.claude/skills/bts-*/SKILL.md`.
> 구 `bts-workflow` 스킬을 문서로 강등한 것 (2026-08-03) — 실행 스킬이 아닌 참조 맵이
> 스킬로 등록되어 매 세션 스킬 목록을 소비하던 비용을 제거했다.

## 8단계 개요

```
사용자 자연어 → /bts
  ↓ 선행 읽기 (_index, history 최근 50줄, learnings 헤딩 인덱스)
[1] /bts-start         (classify + worktree + Draft PR)
[2] /bts-domain        (grill-with-docs)
[3] /bts-spec          (office-hours → brainstorming)
[4] /bts-plan          (writing-plans, TDD)
[5] /bts-review-plan   (타입별 plan-*-review)
  ↓ 🛑 게이트 1  (fast-track 은 생략 — /bts Phase C 게이트 정책)
[6] /bts-impl          (subagent-driven + TDD 강제)
[7] /bts-codereview    (code-reviewer agent + /review)
  ↓ 🛑 게이트 2  (모든 타입 필수 — 생략 없음)
[8] /bts-merge         (머지 + worktree 정리 + dashboard + Obsidian 수동 sync)
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
| 8 | `/bts-merge` | (내장 절차 — verify-master-plan, gh pr merge, worktree 정리, build-dashboard, Obsidian sync) |

## 작업 상태 추적

| 상태 | 단일 진실 공급원 |
|---|---|
| 진행 중 | 체크아웃된 worktree (`.worktrees/<slug>`) + open PR |
| 리뷰 대기 | open PR + `gh pr ready` (draft 해제) |
| 머지 후 | `Maxi_wiki/BTS/history.md` (bts-merge Step 7 수동 append) + `docs/INDEX-recent.md` (문서 인덱스 자동 생성) |

## 타입별 분기 — 정본 포인터

분기 표의 사본을 이 문서에 두지 않는다. 두 목록은 서로를 검사하지 않는다 — 사본이 생기면
drift 가 사고가 된다 (`backend` 행이 리뷰 분기에서 3개월간 미정의였던 실적).

- **입력 → 타입/담당 agent/BC**. `scripts/workflow/classify-task.ts` (`detectType` · `detectAgent` · `BC_KEYWORDS`)
- **타입 → plan 리뷰 체인**. `.claude/skills/bts-review-plan/SKILL.md` Step 2 표 — `scripts/workflow/skill-type-coverage.test.ts` 가 TaskType 유니온과의 차집합 0 을 CI 로 강제
- **타입 → 단계 스킵 (fast-track)**. 각 스킬 하단 "Fast-track 스킵 조건" + `.claude/skills/bts/SKILL.md` Phase C 게이트 정책

## 핵심 원칙

1. **PR + worktree = 작업 단위** — 어떤 Edit/Write도 worktree 없이 main에 적용 금지
2. **TDD 강제 (`/bts-impl`)** — `test:` 커밋이 `feat:` 커밋보다 먼저 있어야 함 (verifier가 git log 검증)
3. **task 병렬 dispatch (`/bts-impl`)** — plan 메타 `depends-on` + `files` 로 wave 계산, 같은 wave task는 한 응답에 묶어 동시 dispatch. 파일 겹치면 자동 직렬화
4. **코드 리뷰는 PR 단위 1회** — task별 quality review 없음, spec-compliance만 가벼운 drift 감지
5. **`auth`/`migration` 작업** 특별 취급 — plan-eng + plan-ceo 둘 다, codereview에 추가 가이드 첨부
6. **`feature` + task ≥ 3** — `/autoplan` 자동 진입 (4종 리뷰 + 결정 게이트)
7. **첫 실질 커밋 시 Draft PR 자동 개설** (`/bts-start`이 처리)

## Obsidian 단방향 동기화

Repo → `Maxi_wiki/BTS/` **단방향**. Obsidian → Repo 방향은 없다 (Maxi의 사고 공간은 코드
영역에 반영 안 됨). 절차 정본은 `.claude/skills/bts-merge/SKILL.md` Step 7 (수동 4단계).
자동화(sync 스크립트 + post-merge hook)는 백로그.
