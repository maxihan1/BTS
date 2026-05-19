---
name: bts-start
description: 사용자 자연어 입력을 받아 classify → git worktree 생성 → plan 스텁 → Draft PR 자동 개설. /bts 진입 직후 자동 호출. 외부 스킬 superpowers:using-git-worktrees, office-hours(builder, feature 신규 시) 사용.
---

# /bts-start

`/bts` 진입 직후 첫 단계. 작업 분류 + worktree + Draft PR 스켈레톤.

## 선행 읽기

- 없음 (이미 `/bts`가 `_index`/`history`/`learnings` 로드함)

## 절차

### Step 1. 작업 분류 (캐시 적용)

```bash
classify=$(npx tsx scripts/workflow/classify-task.ts \
  --title "<사용자 입력>" --cache)
# 출력: { type, agent, slug, runtimeDeploy, ultraplan }
# 캐시: .bts-cache/classify.json (1시간 TTL)
```

**type ∈ {auth, backend, ui, design, migration, api, qa, bugfix, chore, feature}**

판정 모호 (`type=unknown`) 시 AskUserQuestion으로 Maxi에게 확인.

### Step 2. feature 신규 아이디어 게이트 (조건부)

`type=feature` AND 입력에 "새 기능"/"만들어줘" 키워드 + Maxi가 명세 모호 신호 → `office-hours` (builder mode) 호출해 사전 검증.

```
Skill({ skill: "office-hours", args: "builder mode <입력 내용>" })
```

산출물. `docs/specs/_idea-<slug>.md`. 통과 시 정상 진행, "이 기능 만들 가치 없음" 결론이면 사용자 확인 후 중단.

명세가 명확하면 스킵.

### Step 3. git worktree 생성

`superpowers:using-git-worktrees` 스킬 invoke. Atlas 고유 규칙으로 오버라이드.

```bash
# 안전 검증
git status --porcelain  # uncommitted 있으면 차단
git fetch origin

# worktree 생성
slug=<classify 결과의 slug>
branch_prefix=<type> # feat, fix, refactor, chore, ...
git worktree add ".worktrees/${slug}" -b "${branch_prefix}/${slug}"

echo "✅ Worktree: $(pwd)/.worktrees/${slug}"
echo "👉 이후 모든 작업은 worktree 내부에서 진행"
```

**Atlas 고유 규칙**.
- 위치 고정. `.worktrees/<slug>` (`.gitignore`됨)
- 브랜치 명명. `<type>/<slug>` (예. `feat/issue-mention-notify`, `auth/2fa-totp`)

### Step 4. plan 스텁 생성

worktree 내부에서.

```bash
cd .worktrees/${slug}
mkdir -p docs/plans
date=$(date +%Y-%m-%d)
cat > "docs/plans/${date}-${slug}.md" <<EOF
# ${TITLE}

> slug: ${slug}
> type: ${TYPE}
> agent: ${AGENT}
> 생성: ${date}

## Brief

<사용자 원문 + classify 결과>

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
EOF
```

### Step 5. Draft PR 자동 개설

```bash
# 첫 빈 커밋으로 PR 발사 가능 상태 만들기
git commit --allow-empty -m "chore: ${slug} — work in progress"
git push -u origin "${branch_prefix}/${slug}"
gh pr create --draft \
  --title "[${TYPE}] ${TITLE}" \
  --body "$(cat <<BODY
## 개요
${USER_INPUT}

## 작업 분류
- type: ${TYPE}
- agent: ${AGENT}
- slug: ${slug}

## Plan
docs/plans/${date}-${slug}.md (작업 진행에 따라 채워짐)

## 체크리스트
- [ ] domain 정리 (/bts-domain)
- [ ] spec 작성 (/bts-spec)
- [ ] plan 작성 (/bts-plan)
- [ ] plan 리뷰 (/bts-review-plan)
- [ ] 구현 (/bts-impl, TDD 강제)
- [ ] 코드 리뷰 (/bts-codereview)
BODY
)"
```

### Step 6. 다음 스킬 체이닝

자동으로 `/bts-domain` 호출.

## 출력 형식

```
🔄 [1/7] /bts-start
   ├─ 분류: type=feature, agent=backend-engineer, tasks=4 (cached)
   ├─ Worktree: .worktrees/issue-mention-notify (feat/issue-mention-notify)
   ├─ Plan 스텁: docs/plans/2026-05-19-issue-mention-notify.md
   └─ Draft PR: https://github.com/maxihan1/BTS/pull/N
```

## 실패 / 엣지 케이스

- **uncommitted 변경**. main 워크스페이스에 uncommitted 있으면 worktree 생성 차단. Maxi에게 "현재 변경을 어떻게 할까요?" 옵션 제시 (stash / discard / 별도 worktree)
- **slug 충돌**. 같은 slug worktree 이미 존재 시 `-2` 접미사
- **gh push 실패** (rate limit / 인증). 사용자에게 보고, worktree는 유지
