---
name: bts-start
description: Use when /bts has accepted a new coding request and the working environment (branch, worktree, plan stub, draft PR) must be bootstrapped before any other workflow step runs.
---

# /bts-start

`/bts` 진입 직후 첫 단계. 작업 분류 + worktree + Draft PR 스켈레톤.

## 선행 읽기

- 없음 (이미 `/bts`가 `_index`/`history`/`learnings` 로드함)

## 절차

### Step 0. 러너 엔진 헬스체크 (필수)

작업을 시작하기 전에 self-hosted 러너의 실행 엔진(node · java)이 살아 있는지 본다.
**여기서 red 면 이 작업의 CI 는 전부 무의미하다** — 러너를 먼저 고친다.

```bash
bash scripts/verify-runner-health.sh
```

exit 1 이면 출력의 복구 절차를 그대로 따르고, **초록이 될 때까지 Step 1 로 넘어가지 않는다.**

왜 여기인가. 2026-08-04 홈 폴더 용량 정리로 러너 엔진 4개가 지워져 **사흘간 CI 가 0회 실행**됐고
그 사이 #342·#343·#344 가 검증 없이 머지됐다. 러너는 `online` 이었고 빨간불도 떴지만
「테스트 실패」와 구분되지 않았다. CI 안의 헬스체크(`runner-health.yml`)도 있지만 그 전제가
미검증이므로 **이 로컬 점검이 전제 무관 백스톱**이다.
배경. `docs/runbooks/self-hosted-runner.md` §4

### Step 1. 작업 분류 (캐시 적용)

```bash
classify=$(node scripts/workflow/classify-task.ts \
  --title "<사용자 입력>" --cache)
# 출력: { title, slug, type, agent, primary_bc, task_count, cached_at }
# 캐시: .bts-cache/classify.json (1시간 TTL)
```

**type ∈ {auth, backend, ui, design, migration, api, qa, bugfix, chore, feature}** — 신호 0이면 `backend` 기본값.

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

# ★ pre-commit 훅 연결 — 빠뜨리면 이 worktree 의 모든 커밋이 무방비다.
ln -s "$(pwd)/.husky/_" ".worktrees/${slug}/.husky/_"

echo "✅ Worktree: $(pwd)/.worktrees/${slug}"
echo "👉 이후 모든 작업은 worktree 내부에서 진행"
```

**Atlas 고유 규칙**.
- 위치 고정. `.worktrees/<slug>` (`.gitignore`됨)
- 브랜치 명명. `<type>/<slug>` (예. `feat/issue-mention-notify`, `auth/2fa-totp`)
- **훅 연결 필수**. husky 의 `core.hooksPath` 는 **상대 경로** `.husky/_` 인데 그 디렉토리는
  `.gitignore` 대상이라 worktree 에 checkout 되지 않는다. git 은 훅을 못 찾으면 **실패가 아니라
  침묵으로 건너뛴다** — 아무도 눈치채지 못한다. 연결이 없으면 인덱스 drift·lint 위반이
  전부 통과해 CI 에서야 빨간불이 된다(PR #331·#333 실측, 5커밋 구간 red).
  배선은 `scripts/workflow/worktree-hook-wiring.test.ts` 가 강제한다.

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
🔄 [1/8] /bts-start
   ├─ 분류: type=feature, agent=backend-engineer, tasks=4 (cached)
   ├─ Worktree: .worktrees/issue-mention-notify (feat/issue-mention-notify)
   ├─ Plan 스텁: docs/plans/2026-05-19-issue-mention-notify.md
   └─ Draft PR: https://github.com/maxihan1/BTS/pull/N
```

## 실패 / 엣지 케이스

- **uncommitted 변경**. main 워크스페이스에 uncommitted 있으면 worktree 생성 차단. Maxi에게 "현재 변경을 어떻게 할까요?" 옵션 제시 (stash / discard / 별도 worktree)
- **slug 충돌**. 같은 slug worktree 이미 존재 시 `-2` 접미사
- **gh push 실패** (rate limit / 인증). 사용자에게 보고, worktree는 유지
