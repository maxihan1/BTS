---
name: bts
description: BTS 의 모든 코드 작업 진입점. 자연어로 하고 싶은 일을 주면 티어를 재고 네 단계(시작 → 구현 → 끝 리뷰 → 병합)를 끝까지 돈다. "멘션 알림 만들어줘", "로그아웃 버그 고쳐줘", "보드 버튼 위치 바꿔줘" 처럼 무언가를 만들거나 고치라고 할 때 쓴다. 읽기만 하는 질문 · 코드 설명에는 쓰지 않는다.
---

# /bts

네 단계를 끝까지 돈다. **사람에게 묻는 것은 T2+ 시작 질문 한 번뿐이다** (2026-10-20 Maxi — 시작 1회 + 자동 merge).
예외는 셋 — 실측 티어가 선언보다 높을 때 · 고칠 수 없는 BLOCKER · 같은 문제 3회 실패.

```
1. 시작     분류 · worktree + 훅 · (T2+) plan 파일 · Draft PR · (T2+) 시작 질문
2. 구현     코드 + 테스트 · push 전 범위 테스트
3. 끝 리뷰  push · 실측 티어 · 리뷰 한 번 · 로컬 테스트 EXIT=0
4. 병합     merge · 정리 · Obsidian · 보고
```

티어 요약은 `CLAUDE.md`, 표면 글로브는 `scripts/workflow/surfaces.ts` 가 정본이다. 절차는 이 파일만 소유한다.

| | T0 | T1 | T2 | T3 |
|---|---|---|---|---|
| plan 파일 | 없음 | 없음 | 1파일(짧게) | 1파일 + ADR |
| 테스트 | lint+타입 · 시각이면 눈확인 | 재현 테스트 먼저 | TDD red-first (`test:` → `feat:`) | TDD + 마이그레이션 검증(`DATA.md`) |
| 시작 질문 | — | — | 1회 | 1회 |
| 끝 리뷰 | `code-reviewer` | `code-reviewer` | + `/review` | + `/review` + `plan-ceo-review` |

- 혼합이면 최고 티어. 기본 T1. Maxi 가 지정하면 그것이 우선.
- 테스트만 바뀌면 티어를 올리지 않는다. 보안 표면은 T2 미만 불가, 마이그레이션은 T3.

## 0. 이어가기 감지

```bash
git worktree list
gh pr list --draft --author @me --json number,title,headRefName
```

메인 체크아웃 말고 worktree 나 draft PR 이 있으면 `AskUserQuestion` — 이어가기 / 새 작업 / 이전 폐기.
이어가기의 재개 지점은 그 PR 의 커밋 목록과 plan 파일 「할 일」 체크로 읽는다. 추정하지 않는다.

## 1. 시작

1. **분류** — 결과는 `.bts-cache/classify.json` 에 남고 세션 라벨이 그것을 읽는다.
   ```bash
   node --experimental-strip-types scripts/workflow/classify-task.ts --title "<사용자 입력>" --cache
   node --experimental-strip-types scripts/workflow/session-label.ts
   ```
   애매하면 높은 티어. 바꿀 경로를 이미 알면 `detect-tier.ts` 로 확인한다.
2. **worktree** — 배경 세션은 `EnterWorktree`, 아니면 `git worktree add .worktrees/<slug> -b <type>/<slug> origin/main`.
   **만든 직후 훅을 연결한다.** git 은 훅을 못 찾으면 실패가 아니라 침묵으로 건너뛴다. `WT` 는 방금 만든 worktree 경로다.
   ```bash
   ln -s "$(pwd)/.husky/_" "${WT}/.husky/_"
   ln -s "$(pwd)/node_modules" "${WT}/node_modules"
   ln -s "$(pwd)/apps/web/node_modules" "${WT}/apps/web/node_modules"
   ```
   worktree 안에서 `pnpm install` 을 돌리지 않는다 — main 의 `node_modules` 를 덮어쓴다.
3. **plan 파일 (T2+)** — `docs/plans/<YYYY-MM-DD>-<slug>.md`. 이것뿐이다.
   ```markdown
   # <제목>

   티어: T2
   타입: <classify type>

   <무엇을 왜 — 한 문단. FR ID 가 있으면 여기에>

   ## Jira 대조
   <Jira Cloud 실물 조회 근거 — 출처 URL 이 든 대응 표. 대응이 없으면 「대응 없음 — ADS <패턴> 준용」과 사유>

   ## 할 일
   - [ ] <할 일 — 고칠 파일. 3–7개>
     - **검증**: `<명령>`
   ```
   `**검증**:` 은 줄 머리에 둔다 — `select-test-scope.ts --plan` 이 그 꼴만 읽는다.
   `## Jira 대조` 는 타입과 무관하게 쓴다. 절차는 `docs/design/jira-parity-contract.md` §1.
   T3 는 `docs/decisions/<YYYY-MM-DD>-<slug>.md` ADR 을 더한다. 새 문서는 `node scripts/build-doc-index.mjs` 로 인덱스를 갱신한다.
4. **Draft PR** — 빈 커밋(`chore: <slug> 시작`) → `git push -u origin <브랜치>` → `gh pr create --draft --title "[<type>] <제목>" --body "<본문>"`.
   본문은 「무엇을 · 왜」 한 문단 · 「확인 방법」(누를 화면이나 명령) · 티어.
   T0/T1 에서 UI 를 건드리면 plan 파일이 없으므로 본문에 `Jira 대응`(화면 + 출처 URL + 조회일) · `채택` · `편차` 3줄을 적는다.
5. **시작 질문 (T2+ 만)** — `session-label.ts --step "시작 질문"` 다음 `AskUserQuestion` 하나.
   질문 칸에 하려는 것 한 문장 · 영향 범위(BC · 파일) · 제가 정해 둔 것 · 화면이 바뀌면 글자 그림. 선택지는 진행 / 중단.
   Maxi 는 질문 창만 보고 답한다 — 배경을 질문 밖에만 쓰지 않는다.

**읽을 것은 이번 일에 걸리는 절만.** 늘 `DEVELOPMENT.md` §1 과 `docs/rules/code-checklists.md` 의 영역 절(백엔드 · 프론트 · 보안 · DB · E2E).
DB 면 `DATA.md` §1. UI 면 `docs/design/jira-parity-contract.md` §1.
과거 교훈은 `/Users/maxi.moff/Maxi_wiki/BTS/learnings.md` 를 `grep -n '^### '` 로 헤딩만 뽑아 이번 BC · 키워드 항목만 연다 — 전량 Read 금지.
명세는 `grep -n '^#'` 로 절을 찾아 그 범위만 연다.

## 2. 구현

- 한 PR = 한 BC. 다른 BC 는 pgmq 이벤트로만.
- T1 버그는 재현 테스트를 먼저 쓰고 red 를 본다. T2+ 는 `test:` 커밋이 첫 `feat:` 보다 앞선다.
  UI 시각 변경은 red-first 면제 — 기존 E2E 동반 실행 + 브라우저 눈확인. 로직이 섞인 부분만 TDD.
- **기본은 직접 한다.** 할 일이 셋 이상이고 서로 독립일 때만 `bts-implementer` 한두 명에 묶어 맡긴다 — 에이전트 하나가 시작만으로 수만 토큰을 쓴다.
  프롬프트에 할 일 · 고칠 파일 · 검증 명령 · BC · `DEVELOPMENT.md` §1 발췌를 싣는다. 보안 · 마이그레이션 할 일은 `model: "opus"`.
  같은 worktree 를 함께 쓰면 고칠 파일이 겹치지 않게 나누고, 커밋은 `git commit -- <경로들>` 로 자기 파일만 한다.
- FR 추가 · 삭제 · 범위 변경이면 같은 PR 에서 `docs/rules/fr-sync-checklist.md` 전 항목을 맞춘다.
- push 전 — 계산기가 이번 브랜치가 돌릴 명령을 낸다. 그대로 실행한다.
  ```bash
  node --experimental-strip-types scripts/workflow/select-test-scope.ts [--plan docs/plans/<plan 파일>]
  ```
  worktree 에서는 `pnpm` 래퍼 대신 `node_modules/.bin/*` 를 직접 부른다. **판정은 종료 코드로** — 건수로 하지 않는다.
  출력이 길면 `> "$CLAUDE_JOB_DIR/tmp/test.log" 2>&1; echo "EXIT=$?"` (파이프는 종료 코드를 바꾼다).

## 3. 끝 리뷰 — 한 번

1. `git push`.
2. **실측 티어**
   ```bash
   git diff --name-only origin/main...HEAD \
     | node --experimental-strip-types scripts/workflow/detect-tier.ts --declared <선언 티어>
   ```
   `PROMOTION_NEEDED` 면 자동 승격하지 않고 멈춰 `AskUserQuestion` — 구현이 끝난 뒤에 계획 승인을 받는 꼴이 되므로 사람이 정한다.
   `UNMAPPED` 경로는 PR 본문에 그대로 적는다.
3. **리뷰 한 번** — 티어 표의 렌즈를 한 응답에서 병렬로 낸다.
   - `code-reviewer` 에이전트 — 프롬프트에 diff 범위 `origin/main...HEAD` · worktree 절대경로 · plan 파일 · `DEVELOPMENT.md` §1 · DB 면 `DATA.md` §1.
   - T2+ `Skill("review")` — args 에 worktree 경로와 base `origin/main`. BTS 체크리스트는 `.claude/skills/review/checklist.md`.
   - T3 또는 type `auth` · `migration` — `Skill("plan-ceo-review")` 에 plan 파일과 diff.
4. 지적은 고치고 push 한다. **고친 뒤 리뷰를 다시 부르지 않는다.** 고칠 수 없는 BLOCKER 나 사람이 정할 문제일 때만 멈추고 묻는다.
5. **merge 근거는 로컬 테스트 하나다** — CI 가 없다. 리뷰 수정까지 끝난 상태에서 2단계의 `select-test-scope` 명령을 다시 돌려
   전부 `EXIT=0` 일 때만 4. 병합으로 간다. 아니면 고치고 다시 돈다. 결과(명령 · `EXIT=`)를 PR 본문에 한 줄 적는다.

## 4. 병합

1. worktree 에서 `git status --porcelain` 이 비고 `git log origin/<브랜치>..HEAD` 가 0 인지 본다. 남았으면 커밋 · push 먼저.
2. **스펙 정합** — FR · 스펙 문서가 바뀌었으면 `bash scripts/verify-master-plan.sh` — exit 0 이 아니면 같은 PR 에서 맞춘다.
3. `gh pr ready <번호>` → `gh pr edit <번호> --title "<최종 제목>" --body "<한 일 · 확인 방법 · 리뷰 · 테스트 결과 한 줄씩>"`.
4. `gh pr view <번호> --json headRefOid -q .headRefOid` → `gh pr merge <번호> --squash --delete-branch --match-head-commit <헤드>` →
   `gh pr view <번호> --json state -q .state` 가 `MERGED` 인지 본다. `--delete-branch` 가 「main is already used by worktree」로 실패해도 merge 는 된 것이다.
5. **`MERGED` 확인 뒤에만 정리한다.** 배경 세션은 먼저 `ExitWorktree`(`keep`)로 루트에 나온다.
   ```bash
   node --experimental-strip-types scripts/workflow/safe-worktree-remove.ts <worktree 경로>
   git worktree prune
   git push origin --delete <브랜치>
   git checkout main && git pull --ff-only origin main
   lsof -ti:5173 | xargs kill -9 2>/dev/null || true   # worktree 에서 띄운 Vite 가 남는다
   node -e 'const fs=require("fs");fs.rmSync(".bts-cache",{recursive:true,force:true});fs.mkdirSync(".bts-cache")'
   git status
   ```
   `safe-worktree-remove` 가 종료 코드 2 면 미커밋 · 미푸시가 남은 것이다 — 지우지 말고 보고한다.
   `git status` 에 `needs merge` 나 stage 1/2/3 이 보이면 공유 `.git` 오염이다 — 손대지 말고 보고한다.
6. 대시보드는 main 의 post-merge 훅이 `[chore] dashboard regen` 으로 올린다. 그 커밋이 안 보일 때만 `node scripts/build-dashboard.mjs`.
7. Obsidian — `/Users/maxi.moff/Maxi_wiki/BTS/history.md` 에 `- YYYY-MM-DD #<PR> [<type>/<slug>] <제목>` 한 줄. 새 ADR 은 `decisions/` 로 복사.
8. 보고 — 첫 줄에 무엇이 반영됐고 Maxi 가 어디서 확인하는지. 이어서 PR · 티어(선언/실측) · 리뷰 · 테스트 각 한 줄.

## 막혔을 때

같은 문제를 3번 시도해서 안 되면 멈추고 보고한다 — 무엇을 했나 · 어디서 막혔나 · 다음 후보 하나.
검사가 빨간데 내 변경 때문이 아닌 것 같으면 `git diff origin/main...HEAD` 로 실제 바뀐 것부터 본다.
머지 충돌은 `git pull --rebase origin main` — 충돌 파일이 남으면 멈추고 보고한다.
