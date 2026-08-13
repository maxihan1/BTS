# BTS 워크플로우 맵

> `/bts` 체인의 참조 지도. **실행 절차의 정본은 각 `.claude/skills/bts-*/SKILL.md`.**
> 구 `bts-workflow` 스킬을 문서로 강등한 것 (2026-08-03) — 실행 스킬이 아닌 참조 맵이
> 스킬로 등록되어 매 세션 스킬 목록을 소비하던 비용을 제거했다.

## 7단계 개요

```
사용자 자연어 → /bts
  ↓ 티어 판정 (바꾼 경로가 절차를 정한다 — CLAUDE.md §작업 티어)
[1] /bts-start         (classify + worktree + Draft PR)
[2] /bts-spec          (BC 식별 + 스펙)          — T2/T3 만
[3] /bts-plan          (TDD task 분해)           — T2/T3 만
[4] /bts-review-plan   (타입별 리뷰 렌즈)         — T2/T3 만
  ↓ 🛑 게이트 1  (T2/T3 만 — T0/T1 은 생략)
[5] /bts-impl          (sub-agent dispatch + TDD)
[6] /bts-codereview    (독립 리뷰 — T0/T1 1종 · T2+ 2종)
  ↓ 🛑 게이트 2  (전 티어 필수 — 생략 없음)
[7] /bts-merge         (머지 + worktree 정리 + dashboard + Obsidian 수동 sync)
```

구 `[2] /bts-domain` 은 `/bts-spec` 이 흡수했다 (2026-08-13). BC 식별과 ADR 확인은
`/bts-spec` 의 선행 절차이며, 스킬 하나를 지나기 위한 왕복 1회가 사라졌다.

## 외부 스킬 의존 — 없음

각 `/bts-*` 는 자기 절차를 본문에 갖는다. 과거에는 얇은 래퍼로 두고 외부 스킬(superpowers ·
gstack plan-review 계열)에 로직을 위임했으나, **선언한 호출의 실제 발동률이 0~13% 로 측정**되어
(2026-08-13 실측 · 트랜스크립트 100세션) 선언과 실행이 갈라져 있었다. 절차는 본문으로 내재화하고
호출 선언은 제거했다. 리뷰 렌즈만 예외로 `/bts-review-plan` Step 2 표가 관리한다.

## 작업 상태 추적

| 상태 | 단일 진실 공급원 |
|---|---|
| 진행 중 | 체크아웃된 worktree (`.worktrees/<slug>`) + open PR · `.claude/STATE.md` |
| 리뷰 대기 | open PR + `gh pr ready` (draft 해제) |
| 머지 후 | `Maxi_wiki/BTS/history.md` (bts-merge Step 7 수동 append) + `docs/INDEX-recent.md` (자동 생성) |

## 분기 표 — 정본 포인터

분기 표의 사본을 이 문서에 두지 않는다. 두 목록은 서로를 검사하지 않는다 — 사본이 생기면
drift 가 사고가 된다 (`backend` 행이 리뷰 분기에서 3개월간 미정의였던 실적).

- **바꾼 경로 → 티어**. `scripts/workflow/surfaces.ts` (글로브 정본) · `detect-tier.ts` (판정) ·
  `docs/rules/behavior-rules.md` (표면 표) — `verify-master-plan.sh` 룰 J 가 차집합 0 을 강제
- **입력 → 타입/담당 agent/BC**. `scripts/workflow/classify-task.ts` (`detectType` · `detectAgent` · `BC_KEYWORDS`)
- **타입 → plan 리뷰 렌즈**. `.claude/skills/bts-review-plan/SKILL.md` Step 2 표 —
  `scripts/workflow/skill-type-coverage.test.ts` 가 TaskType 유니온과의 차집합 0 을 CI 로 강제
- **티어 → 단계 스킵·게이트**. `.claude/skills/bts/SKILL.md`

## 핵심 원칙

1. **PR + worktree = 작업 단위** — 어떤 Edit/Write 도 worktree 없이 main 에 적용 금지
2. **TDD 강제** — 강도는 티어가 정한다. T1 은 재현 테스트 1개 먼저, T2+ 는 `test:` 커밋이
   `feat:` 보다 먼저 (verifier 가 git log 대조 · `tier-floor.test.ts` 가 CI 에서 재대조).
   예외. ui 시각 변경은 시각 검증 트랙 (`/bts-impl` §타입별 규율)
3. **task 병렬 dispatch (`/bts-impl`)** — plan 메타 `depends-on` + `files` 로 wave 계산,
   같은 wave task 는 한 응답에 묶어 동시 dispatch. 파일 겹치면 자동 직렬화
4. **코드 리뷰는 PR 단위** — task 별 quality review 없음, spec-compliance 만 가벼운 drift 감지
5. **보안 경로는 파일 수 무관 최소 T2 + 보안 렌즈** — `identity-access` 의 인증 코드는 대부분
   `domain/` 밖에 있어 폴더 기준 규칙이 통째로 비켜간다 (2026-08-13 실측)
6. **첫 실질 커밋 시 Draft PR 자동 개설** (`/bts-start` 이 처리)

## Obsidian 단방향 동기화

Repo → `Maxi_wiki/BTS/` **단방향**. Obsidian → Repo 방향은 없다 (Maxi 의 사고 공간은 코드
영역에 반영 안 됨). 절차 정본은 `.claude/skills/bts-merge/SKILL.md` Step 7 (수동 4단계).
자동화(sync 스크립트 + post-merge hook)는 백로그.
