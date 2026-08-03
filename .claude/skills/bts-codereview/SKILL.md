---
name: bts-codereview
description: Use when implementation tasks are complete and the resulting PR needs a single-shot multi-source code review against absolute rules and structural issues before the final approval gate.
---

# /bts-codereview

PR 단위 1회 코드 리뷰. **두 종류의 리뷰를 병행**.

## 선행 읽기

**모두 컨텍스트에 이미 있음. 재로드 금지** (`/bts` 진입 시 + `/bts-impl` controller inject로 로드 완료).
- `Maxi_wiki/BTS/learnings.md` — `/bts` Phase B에서 **헤딩 인덱스 + 선별 발췌**로 로드됨 (전량 재로드 금지)
- `DEVELOPMENT.md` §1 — `/bts-impl` controller가 inject
- `DATA.md` §1 — `/bts-impl` controller가 inject (해당 타입 시)

Step 2 agent prompt에 inline 첨부할 때도 컨텍스트 내 내용을 그대로 사용 (재 Read 불필요).

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
BTS 프로젝트 PR 리뷰. 절대 규칙 19개 (DEVELOPMENT.md §1) + 데이터 무결성 5원칙 (DATA.md §1) 위반 여부 중점 검증.

작업 영역. <type> (예. auth, backend, ui, migration)
plan 파일. docs/plans/<date>-<slug>.md
PR diff. <PR_DIFF>

**중점 검증**. DEVELOPMENT.md §1 절대 규칙 19개 (NEVER-1~19) + DATA.md §1 데이터 무결성 5원칙 전부 위반 여부 검증. auth/migration 시 §1.1 보안 + §1.2 데이터 무결성 추가 강조.

**주입 범위 (controller)**. agent prompt에는 **DEVELOPMENT.md §1 + DATA.md §1 섹션만** 인라인 첨부.
learnings 는 Phase B에서 선별한 발췌 + `grep -n '^### '` 헤딩 인덱스 전체를 첨부 —
**본문 전체(98KB) 인라인 금지**, 그 재복제가 이 하네스의 단일 최대 컨텍스트 낭비였다.

보고. PASS / CONCERNS (수정 권장) / BLOCKER (수정 필수).
"""
})
```

### Step 3. /review (gstack) 호출 — 병행

```
Skill({
  skill: "review",
  args: "PR pre-landing 리뷰. 작업 디렉토리. .worktrees/<slug> (이 디렉토리에서 실행 — 프로젝트 체크리스트 .claude/skills/review/checklist.md가 worktree 루트 기준으로 존재). base는 origin/main. 글로벌 카테고리(SQL 안전성, race, LLM 신뢰 경계 등)에 더해 체크리스트의 BTS 고유 항목(Pass 0 PRE_EXISTING 판별, init_codegen 미러, 도메인 예외 핸들러 스코프, @JsonInclude↔Zod 정합) 적용."
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

게이트 2를 위한 요약 출력. **글로벌 §Explanation Style Work-Report Format(계층형 3블록)을 반드시 먼저 낸다.** Maxi가 승인 여부를 판단하는 지점이므로, 리뷰 지적은 전문용어를 풀이한 쉬운 문장으로 `✅`+`💡`에 요약하고, 원문 기술 내용은 `🔧` 아래로 격리한다. 처음 나온 약어(ReDoS·OCC·nullable 등)는 괄호 풀이 필수.

```
🛑 게이트 2 — Maxi 검토 부탁드립니다.

✅ 한 줄  <검토 결과를 비전문가 한 문장으로. 서식 정본은 CLAUDE.md §사용자 커뮤니케이션 스타일>
💡 의미  <그대로 머지해도 되는지 / 고칠지 판단 재료>
🔧 기술 상세 (안 봐도 됨)
   PR: <url> · 변경 요약: <files changed>, +<add>/-<del>
   - code-reviewer agent / /review (gstack) / plan-ceo-review 결과 각 1줄
   - CONCERNS·BLOCKER 는 항목별로: 쉬운 문장 요약 + (전문용어 괄호 풀이)
   - fast-track 경로였다면 "스킵된 단계([2]/[3]/[5] + 게이트 1)" 목록 명시

다음 옵션:
1. 승인 → /bts-merge (머지 + worktree 정리 + Obsidian sync)
2. 짚은 점 수정 후 재리뷰 → /bts-impl 다시 (loop back)
3. 보류
```

AskUserQuestion으로 응답 수집.

## 출력 형식

```
🔄 [7/8] /bts-codereview
   ├─ code-reviewer agent: PASS
   ├─ /review (gstack): CONCERNS 2건
   ├─ /plan-ceo-review: skip (type=feature)
   └─ 다음. 게이트 2 (Maxi 검토)
```

## 머지 후 자동 처리 (게이트 2 승인 시)

게이트 2 승인 시 **`/bts-merge`로 위임**. 머지 전 검증(verify-master-plan), `gh pr merge`, worktree 정리, dashboard 재생성, 5173 orphan kill, 공유 .git 오염 점검, Obsidian 동기화, 메모리 갱신을 한 절차로 묶어 처리한다. 반복 사고 5종(worktree/stash/orphan-port/spec 소실/머지에러 오인) 차단 절차가 거기 고정돼 있다.

```
Skill({ skill: "bts-merge", args: "slug=<slug>, PR=#<N>, type=<type>, FR변경=<yes/no>, plan변경=<yes/no>" })
```

## 실패 / 엣지 케이스

- **두 리뷰 결과 충돌** (agent PASS, /review BLOCKER). BLOCKER가 항상 우선. Maxi에게 충돌 내용 표시
- **CONCERNS 반복 (3회)**. 작업 자체에 근본 문제. `/bts-domain` 또는 `/bts-spec`으로 loop back 제안
- **agent가 "이 작업은 더 작게 쪼개야 함"으로 보고**. Maxi에게 분할 옵션 제시 (이 PR 머지 + 후속 PR vs 이 PR 분할)
- **머지 충돌**. `git pull --rebase origin main` 후 충돌 파일 표시. 사용자 개입 필수
