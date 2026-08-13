---
name: bts-start
description: Called by /bts step 1 — bootstraps the working environment after a task has been accepted. Never invoke directly.
---

# /bts-start

체인 [1]. 러너 헬스체크 → 분류 → worktree+훅 배선 → plan 스텁 → Draft PR.
**전 티어 호출**된다. T0/T1 은 Step 2·4 를 건너뛰고 Step 5 를 첫 push 시점으로 이연한다.

## 선행 읽기

없음. `/bts` 가 `_index` · `history` · learnings 발췌를 이미 로드했다.

## Step 0. 러너 엔진 헬스체크 (필수 · 티어 무관)

작업을 시작하기 전에 self-hosted 러너의 실행 엔진(node · java)이 살아 있는지 본다.
**여기서 red 면 이 작업의 CI 는 전부 무의미하다** — 러너를 먼저 고친다.

```bash
bash scripts/verify-runner-health.sh
```

exit 1 이면 출력의 복구 절차를 그대로 따르고, **초록이 될 때까지 Step 1 로 넘어가지 않는다.**

왜 여기인가. 2026-08-04 홈 폴더 용량 정리로 러너 엔진 4개가 지워져 **사흘간 CI 가 0회 실행**됐고
그 사이 #342·#343·#344 가 검증 없이 머지됐다. 러너는 `online` 이었고 빨간불도 떴지만
「테스트 실패」와 구분되지 않았다. CI 안의 헬스체크(`runner-health.yml`)도 있지만 그 전제가
미검증이므로 **이 로컬 점검이 전제 무관 백스톱**이다. 배경. `docs/runbooks/self-hosted-runner.md` §4

## Step 1. 작업 분류 (캐시 적용)

```bash
classify=$(node scripts/workflow/classify-task.ts \
  --title "<사용자 입력>" --cache)
# 출력: { title, slug, type, agent, primary_bc, tier, task_count, cached_at }
# 캐시: .bts-cache/classify.json (1시간 TTL)
```

**type ∈ {auth, backend, ui, design, migration, api, qa, bugfix, chore, feature}** — 신호 0이면 `backend` 기본값.
`tier` 는 착수 시점의 **선언**이다. 실측 티어는 머지 전 diff 로 다시 잰다(`/bts` 판정 5문 ⑤).

## Step 2. 신규 아이디어 게이트 (T2+ · 조건부)

**T2/T3 이고** 입력이 「새 기능을 만들어 달라」이며 명세가 모호할 때만 `office-hours` (builder mode) 로 사전 검증한다. 산출물 `docs/specs/_idea-<slug>.md`. "만들 가치 없음" 결론이면 Maxi 확인 후 중단.
명세가 명확하거나 T0/T1 이면 스킵.

## Step 3. git worktree 생성

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

# ★★ 훅의 **실행선** 연결 — 빠뜨리면 훅이 연결돼 있어도 첫 커밋에서 죽는다.
#    훅 본체가 `node_modules/.bin/lint-staged` 를 직접 부르는데 worktree 에는 그 디렉토리가
#    없다(`.gitignore` 대상). 2026-08-12 실측 — 'No such file or directory' 로 pre-commit 사망.
ln -s "$(pwd)/node_modules" ".worktrees/${slug}/node_modules"
ln -s "$(pwd)/apps/web/node_modules" ".worktrees/${slug}/apps/web/node_modules"

echo "✅ Worktree: $(pwd)/.worktrees/${slug}"
echo "👉 이후 모든 작업은 worktree 내부에서 진행"
```

**Atlas 고유 규칙**.
- 위치 고정 `.worktrees/<slug>` (`.gitignore` 대상) · 브랜치 명명 `<type>/<slug>`
- **훅 연결 필수**. husky 의 `core.hooksPath` 는 **상대 경로** `.husky/_` 인데 그 디렉토리는
  `.gitignore` 대상이라 worktree 에 checkout 되지 않는다. git 은 훅을 못 찾으면 **실패가 아니라
  침묵으로 건너뛴다** — 연결이 없으면 인덱스 drift·lint 위반이 전부 통과해 CI 에서야 빨간불이
  된다(PR #331·#333 실측, 5커밋 구간 red).
- **★`node_modules` 심볼릭도 필수**. `.gitignore` 는 worktree 가 `node_modules` ·
  `apps/web/node_modules` · `.husky/_` **셋**을 심볼릭으로 갖는다고 이미 선언하는데 종전 절차는
  마지막 하나만 걸었다 — **선언과 생성이 어긋난 상태**였다.
- 위 두 배선은 `scripts/workflow/worktree-hook-wiring.test.ts` 가 강제한다. **이 코드블록을 쪼개지 말 것** — 훅 연결 3요소가 한 블록 안에 있어야 통과한다.
- ⚠️ **`pnpm install` 로 대신하지 말 것.** worktree 에서 install 을 돌리면 main 의
  `node_modules/.modules.yaml` 을 덮어써 main 을 망가뜨린 전례가 있다(2026-07-17, 3일간 8회 머지).

## Step 4. plan 스텁 생성 (T2+ 만)

T2 는 spec 을 흡수한 **1파일**, T3 는 spec·plan(+ADR) 분리. **T0/T1 은 plan 파일을 만들지 않는다** — 티어 판별식이 「plan 파일 부재 = T0/T1 선언」으로 읽으므로 빈 스텁을 남기면 판정이 뒤집힌다.
템플릿 정본. [file-templates.md](file-templates.md) §1 (머리에 `티어: T2` 행 필수)

## Step 5. Draft PR 개설

T2/T3 은 여기서, **T0/T1 은 첫 push 시점으로 이연**한다(빈 커밋 왕복 제거).

```bash
git commit --allow-empty -m "chore: ${slug} — work in progress"
git push -u origin "${branch_prefix}/${slug}"
gh pr create --draft --title "[${TYPE}] ${TITLE}" --body-file /tmp/bts-pr-body.md
```

PR 본문 템플릿. [file-templates.md](file-templates.md) §2

## Step 6. 다음 스킬 체이닝

`/bts` 컨트롤러에 결과를 돌려준다. 다음 단계는 컨트롤러가 티어로 정한다 — T2+ 는 `/bts-spec`, T0/T1 은 곧장 구현 인라인.

## 출력 형식

```
🔄 [1/7] /bts-start
   ├─ 분류: type=feature, tier=T2, agent=backend-engineer, tasks=4 (cached)
   ├─ Worktree: .worktrees/issue-mention-notify (feat/issue-mention-notify)
   ├─ Plan 스텁: docs/plans/2026-08-13-issue-mention-notify.md (T2 — 1파일)
   └─ Draft PR: https://github.com/maxihan1/BTS/pull/N
```

## 실패 / 엣지 케이스

- **uncommitted 변경**. main 워크스페이스에 uncommitted 있으면 worktree 생성 차단. Maxi 에게 옵션 제시(stash / discard / 별도 worktree)
- **slug 충돌**. 같은 slug worktree 이미 존재 시 `-2` 접미사
- **gh push 실패**(rate limit / 인증). Maxi 에게 보고, worktree 는 유지
