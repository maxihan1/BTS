---
name: bts
description: BTS 의 모든 코드 작업 진입점. 자연어로 하고 싶은 일을 주면 세 단계(시작 → 구현 → 마무리)를 merge 까지 돈다. "멘션 알림 만들어줘", "로그아웃 버그 고쳐줘", "보드 버튼 위치 바꿔줘" 처럼 무언가를 만들거나 고치라고 할 때 쓴다. 읽기만 하는 질문 · 코드 설명에는 쓰지 않는다.
---

# /bts

세 단계를 끝까지 돈다. **사람에게 묻는 것은 위험 경로의 시작 질문 한 번뿐이다** (2026-10-21 재구축).
예외는 둘 — 고칠 수 없는 BLOCKER · 같은 문제 3회 실패.

```
1. 시작    worktree + 연결 · 위험 판정 · (위험) 시작 질문
2. 구현    코드 + 대상 테스트
3. 마무리  (위험) 리뷰 한 번 · PR · merge · 정리
```

plan 파일 · Draft PR · 티어 분류는 없다.

## 1. 시작

1. **이어가기** — `git worktree list` 에 이번 일의 worktree 가 있으면 그 안에서 이어 간다. 재개 지점은 그 브랜치의 커밋 목록으로 읽는다.
2. **worktree** — 배경 세션은 `EnterWorktree`, 아니면 `git worktree add .worktrees/<slug> -b <type>/<slug> origin/main`.
   만든 직후 main 체크아웃(`M`)의 훅과 `node_modules` 를 잇는다. 빠뜨리면 훅이 실패가 아니라 침묵으로 건너뛴다.
   ```bash
   ln -s "$M/.husky/_" .husky/_
   ln -s "$M/node_modules" node_modules
   ln -s "$M/apps/web/node_modules" apps/web/node_modules
   ```
   worktree 안에서 `pnpm install` 을 돌리지 않는다 — main 의 `node_modules` 를 덮어쓴다.
3. **위험 판정** — 고칠 경로가 정해지면
   ```bash
   printf '%s\n' <고칠 경로들> | node --experimental-strip-types scripts/workflow/detect-tier.ts
   ```
   `SURFACES` 에 `MIGRATION` · `SHARED_KERNEL` · `TOPOLOGY` 가 있거나 `SECURITY_LENS` 줄이 나오면 **위험**, 나머지는 **보통**이다.
   구현 중에 위험 경로를 건드리게 되면 그때 위험으로 바꾸고 4 를 한다.
4. **시작 질문 (위험만)** — `AskUserQuestion` 하나. 질문 칸에 하려는 것 한 문장 · 영향 범위(BC · 파일) · 정해 둔 것. 선택지는 진행 / 중단.

**읽을 것은 이번 일에 걸리는 절만** — 늘 `DEVELOPMENT.md` §1. DB 면 `DATA.md` §1. UI 면 `docs/design/jira-parity-contract.md` §1.
영역 함정이 필요하면 `docs/rules/code-checklists.md` 에서 그 절만 연다(`grep -n '^## '`).

## 2. 구현

- 한 PR = 한 BC. 다른 BC 는 pgmq 이벤트로만.
- **테스트는 바꾼 코드의 대상만 돌린다.**
  ```bash
  (cd backend && ./gradlew :modules:<bc>:test --tests '<클래스>')
  (cd apps/web && node_modules/.bin/vitest run <파일>)
  ```
  버그는 재현 테스트를 먼저 쓰고 red 를 본 뒤 같은 명령으로 green 을 본다. 새 동작에는 그것을 덮는 테스트를 같은 PR 에 둔다.
  모듈 전체 · 인자 없는 `vitest run` · `pnpm verify` · Playwright 는 돌리지 않는다 — hook 이 막는다. 막히면 대상을 좁힌다.
- UI 는 브라우저로 눈확인한다. E2E 는 쓰지 않는다. PR 본문에 `Jira 대응`(화면 + 출처 URL + 조회일) · `채택` · `편차` 3줄.
- **기본은 직접 한다.** 할 일이 셋 이상이고 서로 독립일 때만 `bts-implementer` 에 나눠 맡긴다.
  같은 worktree 를 함께 쓰면 고칠 파일이 겹치지 않게 나누고, 커밋은 `git commit -- <경로들>` 로 자기 파일만 한다.
- FR 추가 · 삭제 · 범위 변경이면 같은 PR 에서 `docs/rules/fr-sync-checklist.md` 를 맞추고 `bash scripts/verify-master-plan.sh` 를 한 번 돌린다.
- 문서 인덱스는 pre-commit 이 검사한다. 거기서 막혔을 때만 `node scripts/build-doc-index.mjs`.

## 3. 마무리

1. **리뷰 한 번 (위험만)** — `code-reviewer` 에이전트. 프롬프트에 diff 범위 `origin/main...HEAD` · worktree 절대경로 · 하려던 것 · `DEVELOPMENT.md` §1 · DB 면 `DATA.md` §1.
   지적은 고치고 push 한다. **고친 뒤 리뷰를 다시 부르지 않는다.** 고친 곳은 그 대상 테스트로만 확인한다. 보통이면 리뷰가 없다.
   모듈 전체 관문(`select-test-scope.ts --run`)은 돌리지 않는다(2026-10-21~, Maxi 지시). merge 근거는 2단계의 대상 테스트 결과다.
2. **PR · merge**
   ```bash
   git push -u origin <브랜치>
   gh pr create --title "[<type>] <제목>" --body "<무엇을 왜 · 확인 방법(돌린 대상 테스트) · 위험 여부>"
   gh pr merge <번호> --squash --delete-branch
   gh pr view <번호> --json state -q .state   # MERGED 인지 본다
   ```
   `--delete-branch` 가 「main is already used by worktree」로 실패해도 merge 는 된 것이다.
3. **정리 (`MERGED` 뒤에만)** — 배경 세션은 먼저 `ExitWorktree`(`keep`)로 루트에 나온다.
   ```bash
   node --experimental-strip-types scripts/workflow/safe-worktree-remove.ts <worktree 경로>
   git worktree prune
   git checkout main && git pull --ff-only origin main
   ```
   `safe-worktree-remove` 가 종료 코드 2 면 미커밋 · 미푸시가 남은 것이다 — 지우지 말고 보고한다.
4. Obsidian — `/Users/maxi.moff/Maxi_wiki/BTS/history.md` 에 `- YYYY-MM-DD #<PR> [<type>/<slug>] <제목>` 한 줄.
5. 보고 — 첫 줄에 무엇이 반영됐고 Maxi 가 어디서 확인하는지. 이어서 PR · 위험 여부 · 대상 테스트 결과 각 한 줄.

## 막혔을 때

같은 문제를 3번 시도해서 안 되면 멈추고 보고한다 — 무엇을 했나 · 어디서 막혔나 · 다음 후보 하나.
검사가 빨간데 내 변경 때문이 아닌 것 같으면 `git diff origin/main...HEAD` 로 실제 바뀐 것부터 본다.
머지 충돌은 `git pull --rebase origin main` — 충돌 파일이 남으면 멈추고 보고한다.
