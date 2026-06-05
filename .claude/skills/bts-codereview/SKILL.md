---
name: bts-codereview
description: Use when implementation tasks are complete and the resulting PR needs a single-shot multi-source code review against absolute rules and structural issues before the final approval gate.
---

# /bts-codereview

PR 단위 1회 코드 리뷰. **두 종류의 리뷰를 병행**.

## 선행 읽기

**모두 컨텍스트에 이미 있음. 재로드 금지** (`/bts` 진입 시 + `/bts-impl` controller inject로 로드 완료).
- `Maxi_wiki/BTS/learnings.md` — `/bts` Phase B에서 로드됨
- `DEVELOPMENT.md` 절대 규칙 18개 — `/bts-impl` controller가 inject
- `DATA.md` 데이터 무결성 5원칙 — `/bts-impl` controller가 inject (`auth`/`migration` 시)

Step 2 agent prompt에 본문 inline 첨부할 때도 컨텍스트 내 내용을 그대로 사용 (재 Read 불필요).

## 절차

### Step 1. PR diff 추출

```bash
cd .worktrees/<slug>
gh pr ready  # Draft → Ready (리뷰 대상)
PR_DIFF=$(gh pr diff)
```

### Step 2. superpowers:code-reviewer agent 호출

```
Agent({
  subagent_type: "superpowers:code-reviewer",
  description: "<slug> PR 코드 리뷰",
  prompt: """
BTS 프로젝트 PR 리뷰. 절대 규칙 18개 (DEVELOPMENT.md §1) + 데이터 무결성 5원칙 (DATA.md §1) 위반 여부 중점 검증.

작업 영역. <type> (예. auth, backend, ui, migration)
plan 파일. docs/plans/<date>-<slug>.md
PR diff. <PR_DIFF>

**중점 검증**. DEVELOPMENT.md §1 절대 규칙 18개 (NEVER-1~18) + DATA.md §1 데이터 무결성 5원칙 전부 위반 여부 검증. auth/migration 시 §1.1 보안 + §1.2 데이터 무결성 추가 강조. agent prompt에 DEVELOPMENT.md / DATA.md / learnings.md 본문 전체를 인라인 첨부 (controller가 주입).

보고. PASS / CONCERNS (수정 권장) / BLOCKER (수정 필수).
"""
})
```

### Step 3. /review (gstack) 호출 — 병행

```
Skill({
  skill: "review",
  args: "PR pre-landing 리뷰. 작업 디렉토리. .worktrees/<slug>. SQL 안전성, LLM 신뢰 경계, 조건부 부수효과 등 구조적 이슈 검사."
})
```

`/review`는 다른 관점 (구조/안전성). agent의 절대 규칙 검증과 상호 보완.

### Step 4. auth/migration 추가 리뷰 (조건부)

`classify.type == "auth"` 또는 `migration`이면 `/plan-ceo-review`를 PR-level에서 한 번 더.

```
Skill({
  skill: "plan-ceo-review",
  args: "이 PR이 plan 단계의 ceo-review에서 합의된 제약을 지키고 있는지 검증. plan: docs/plans/<date>-<slug>.md. PR diff: <diff>."
})
```

이유. auth/migration은 데이터/보안 폭발 반경 ↑. plan에서 통과한 정책 약속이 코드에서 어그러지는지 확인.

### Step 5. 결과 통합

세 결과를 PR 본문에 자동 append.

```markdown
## 리뷰 결과 (PR 단위)

### superpowers:code-reviewer agent
- ✅ PASS / ⚠️ CONCERNS / 🛑 BLOCKER
- 항목별 평가 (절대 규칙 위반 없음, learnings 회귀 없음, ...)

### /review (gstack)
- ✅ PASS / ⚠️ CONCERNS / 🛑 BLOCKER
- SQL 안전성, LLM 신뢰 경계, 부수효과 ...

### /plan-ceo-review (auth/migration 시)
- plan 약속 준수 여부
```

### Step 6. 사용자 게이트 2 진입

게이트 2를 위한 요약 출력.

```
🛑 게이트 2 — Maxi 검토 부탁드립니다.

PR: https://github.com/maxihan1/BTS/pull/N (#<title>)
변경 요약: <files changed>, +<additions>/-<deletions>

리뷰 결과:
- code-reviewer agent: ✅ PASS
- /review (gstack): ⚠️ CONCERNS 2건
  - 1. comments.author_id가 nullable로 마이그레이션됨 (의도?)
  - 2. mention 정규식이 ReDoS 가능 (예: @[a-zA-Z0-9_]+ → @\w{1,32})
- /plan-ceo-review: (skip, auth/migration 아님)

다음 옵션:
1. 승인 → 머지 + 배포 + sync-obsidian
2. CONCERNS 수정 후 재리뷰 → /bts-impl loop back
3. 보류
```

AskUserQuestion으로 응답 수집.

## 출력 형식

```
🔄 [7/7] /bts-codereview
   ├─ code-reviewer agent: PASS
   ├─ /review (gstack): CONCERNS 2건
   ├─ /plan-ceo-review: skip (type=feature)
   └─ 다음. 게이트 2 (Maxi 검토)
```

## 머지 후 자동 처리 (게이트 2 승인 시)

> **머지 전 필수 게이트 (CLAUDE.md §명세/범위 변경 시 전수 동기화)**. FR 추가·삭제·범위 변경을 동반한 PR은 머지 전 `bash scripts/verify-master-plan.sh` 통과 확인 — FR ID 정합 + 카운트 drift(fr-index/README/product 헤더·소속FR/CLAUDE) 자동 차단. 실패 시 누락된 정본·미러·카운트를 같은 PR에서 동기화 후 재실행.

```bash
bash scripts/verify-master-plan.sh   # FR 변경 동반 시 필수, exit 0 확인
gh pr merge --squash --delete-branch
cd /Users/maxi.moff/Projects/BTS

# worktree 정리. uncommitted/unpushed 있을 가능성 대비 force 옵션
git worktree remove --force .worktrees/<slug> 2>/dev/null \
  || echo "worktree 이미 제거됨 또는 부재"
```

### Obsidian 동기화 (Phase 0 임시. Phase 1에 자동화 예정)

**현재 (Phase 0)**. 다음을 메인 에이전트가 수동으로 수행 (스크립트 부재).

1. `Maxi_wiki/BTS/history.md`에 1줄 append.
   ```
   - YYYY-MM-DD #<PR번호> [<type>/<slug>] <PR 제목> (<리뷰 종류>)
   ```
2. `docs/decisions/<new>.md`가 새로 생긴 경우 → `Maxi_wiki/BTS/decisions/`에 복사
3. `docs/plans/<merged>.md` → `Maxi_wiki/BTS/plans/`에 복사
4. PR 라벨에 `learning:<topic>` 있으면 → `Maxi_wiki/BTS/learnings.md` append (수동 정리)

**Phase 1 도입 예정**.
- `scripts/workflow/sync-obsidian.ts` 작성 (실제 머지 1회 후 패턴 학습 → 자동화)
- `.git/hooks/post-merge` 또는 `.github/workflows/post-merge.yml` 설치 스크립트
- 머지마다 1번 자동 실행, 1줄 stdout 보고

## 실패 / 엣지 케이스

- **두 리뷰 결과 충돌** (agent PASS, /review BLOCKER). BLOCKER가 항상 우선. Maxi에게 충돌 내용 표시
- **CONCERNS 반복 (3회)**. 작업 자체에 근본 문제. `/bts-domain` 또는 `/bts-spec`으로 loop back 제안
- **agent가 "이 작업은 더 작게 쪼개야 함"으로 보고**. Maxi에게 분할 옵션 제시 (이 PR 머지 + 후속 PR vs 이 PR 분할)
- **머지 충돌**. `git pull --rebase origin main` 후 충돌 파일 표시. 사용자 개입 필수
