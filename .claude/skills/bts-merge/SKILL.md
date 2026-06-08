---
name: bts-merge
description: Use when 게이트 2 (codereview) has been approved and the PR must be merged, the worktree cleaned up, dashboards regenerated, and Obsidian synced — the final step of the /bts workflow. Encodes the hard-won 5-step merge pattern that prevents the recurring worktree/stash/orphan-port accidents.
---

# /bts-merge

`/bts` 워크플로우 [8] 마지막 단계. 게이트 2 승인 후 머지 + 정리. **이 절차는 반복 사고 5종을 구조적으로 차단하려고 고정됐다** — 순서를 건너뛰지 말 것.

> 이 단계가 막는 과거 사고. [[merge-delete-branch-worktree-fail]], [[worktree-lint-staged-shared-git-stash-collision]], [[e2e-orphan-vite-after-worktree-remove]], [[bts-spec-file-uncommitted-loss]], [[bts-ktlintformat-docs-commit-traps]].

## 선행 조건

- 게이트 2 (`/bts-codereview`) 승인 완료 (Maxi가 "1. 승인" 선택).
- 작업 worktree(`.worktrees/<slug>`)에 미커밋·미푸시 변경이 없어야 함 (Step 1에서 확인).

## 절차

### Step 1. 머지 전 전수 확인 (소실 방지)

worktree에 커밋 안 된 산출물(특히 spec/docs 파일)이 남아 있으면 worktree 제거 시 `--force`로 영구 소실된다. 머지 전에 **반드시** 확인한다.

```bash
cd .worktrees/<slug>
git status --porcelain          # 비어 있어야 함. 남아 있으면 커밋+푸시 먼저
git log origin/<branch>..HEAD --oneline   # 미푸시 커밋 0 확인
```

미커밋 파일이 있으면 → 해당 task의 산출물인지 확인 후 커밋·푸시. spec/plan 문서는 누락되기 쉬우니 특히 점검.

### Step 2. FR 변경 동반 시 마스터플랜 검증 (필수 게이트)

FR 추가·삭제·범위 변경을 동반한 PR은 머지 전 카운트 drift를 자동 차단한다.

```bash
bash scripts/verify-master-plan.sh    # exit 0 확인
```

실패(exit 4 등) 시 → 누락된 정본·미러·카운트를 **같은 PR에서** 동기화(CLAUDE.md §명세/범위 변경 시 전수 동기화, 8종 대상) 후 재실행. FR 무관 PR(순수 bugfix/chore)은 생략 가능.

### Step 3. dashboard 재생성 — post-merge 훅이 자동 처리

`docs/plan/**`의 D단계 체크박스나 FR 마킹을 바꾸면 `progress.html`이 stale 해지지만, **main 머지 시 post-merge 훅이 `build-dashboard.mjs`를 자동 실행하고 `[chore] dashboard regen` 커밋을 푸시한다** (PR #93 첫 실전에서 확인). 평소엔 손대지 말 것 — 수동 재생성은 훅과 중복된다.

훅이 없거나 실패한 경우(`git log`에 regen 커밋이 안 보일 때)만 수동 처리.

```bash
node scripts/build-dashboard.mjs      # 훅 미작동 시에만
git add docs/plan/progress.html && git commit -m "[chore] dashboard regen [skip ci]" && git push
```

### Step 4. 머지

```bash
gh pr ready                                  # Draft였다면 해제
gh pr merge --squash --delete-branch
```

> ⚠️ `--delete-branch`가 `"main is already used by worktree"` 에러를 내도 **GitHub 머지 자체는 성공한 것**이다([[merge-delete-branch-worktree-fail]]). 에러 메시지에 속지 말고 Step 5 수동 정리로 넘어간다. 실제 머지 여부는 `gh pr view <N> --json state`로 확인.

머지 충돌 시 → `git pull --rebase origin main` 후 충돌 파일 표시, Maxi 개입 요청.

### Step 5. worktree 정리 + 환경 청소

```bash
cd /Users/maxi.moff/Projects/BTS

# worktree 제거 (Step 1에서 미커밋 0 확인했으므로 안전)
git worktree remove --force .worktrees/<slug> 2>/dev/null \
  || echo "worktree 이미 제거됨 또는 부재"
git worktree prune

# 원격 브랜치 잔여 정리 (--delete-branch 실패했을 경우)
git push origin --delete <branch> 2>/dev/null || true

# main 최신화
git checkout main && git pull --ff-only origin main

# E2E를 worktree에서 돌렸다면 orphan Vite dev 서버가 5173에 남는다 → kill
lsof -ti:5173 | xargs kill -9 2>/dev/null || true
```

### Step 6. 공유 .git 오염 점검 (worktree 작업 후)

worktree의 lint-staged backup stash가 공유 `.git`에 쌓여 main 트리를 오염시키는 경우가 있다([[worktree-lint-staged-shared-git-stash-collision]]). 머지 후 main에서 상태를 확인한다.

```bash
git -C /Users/maxi.moff/Projects/BTS status
```

`"needs merge"`나 인덱스 stage 1/2/3가 보이면 오염. 복구는 **3중 백업 후** `git reset --hard origin/main` (stash ref는 보존됨). 무관한 main 미커밋 작업이 있었다면 먼저 그 작업부터 백업.

### Step 7. Obsidian 동기화 (Phase 0 수동)

스크립트(`sync-obsidian.ts`)가 아직 없어 메인 에이전트가 수동 수행.

1. `Maxi_wiki/BTS/history.md`에 1줄 append.
   ```
   - YYYY-MM-DD #<PR번호> [<type>/<slug>] <PR 제목>
   ```
2. 새 `docs/decisions/*.md` → `Maxi_wiki/BTS/decisions/`에 복사
3. 머지된 `docs/plans/*.md` → `Maxi_wiki/BTS/plans/`에 복사
4. PR 라벨 `learning:<topic>` 있으면 → `Maxi_wiki/BTS/learnings.md` append

> Phase 1에 `scripts/workflow/sync-obsidian.ts` + post-merge hook으로 자동화 예정.

### Step 8. 메모리 갱신

이번 작업에서 새 교훈·사고·완료 상태가 나왔으면 자동 메모리에 기록([[memory-rotation-policy]] 준수 — MEMORY.md는 한 줄 hook만).

## 출력 형식

```
🔄 [8/8] /bts-merge
   ├─ 머지 전 확인: git status clean ✅
   ├─ verify-master-plan: exit 0 ✅ (또는 skip)
   ├─ dashboard regen: 포함 ✅ (또는 skip)
   ├─ merge --squash: #<N> merged
   ├─ worktree 정리 + 5173 kill ✅
   ├─ .git 오염 점검: clean ✅
   └─ Obsidian sync + 메모리 갱신 ✅
```

## 실패 / 엣지 케이스

- **Step 1에서 미커밋 발견** → worktree 제거 전 커밋·푸시. spec/plan 문서 소실 1순위 위험.
- **머지 충돌** → `git pull --rebase origin main`, 충돌 파일 표시, Maxi 개입.
- **`--delete-branch` 에러** → 정상. Step 5 수동 정리로 처리.
- **Step 6 .git 오염** → reset --hard 전 무관 작업 3중 백업.
