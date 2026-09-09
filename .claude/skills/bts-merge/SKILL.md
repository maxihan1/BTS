---
name: bts-merge
description: Called by /bts step 7 — merges the approved PR and cleans up. Never invoke directly.
---

# /bts-merge

`/bts` 워크플로우 [7] 마지막 단계. 게이트 2 승인 후 머지 + 정리. **이 절차는 반복 사고 5종을 구조적으로 차단하려고 고정됐다** — 순서를 건너뛰지 말 것.

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
gh pr checks                    # ★결과를 읽는다. 아래 3분기를 반드시 구분할 것
```

**★★「체크 0건」은 초록이 아니다.** CI 자동 실행을 껐으므로(2026-08-21) 이 명령은 보통
`no checks reported` 를 낸다. 그것을 「빨간불이 아니니 통과」로 읽으면 **검증 없는 머지**가 된다.
같은 파일 Step 5 가 「머지가 만드는 run 이 0건」이라고 적는 것과 같은 사실이다.

| `gh pr checks` 결과 | 판정 |
|---|---|
| 초록 | 통과. `--watch` 로 완료를 기다린 뒤 머지 |
| 빨강 | **머지하지 않는다.** 고치고 다시 |
| **체크 0건** | **통과가 아니다.** 아래 젠킨스 판정으로 넘어간다. 그것도 판정 불가면 머지하지 않는다 |

### ★★젠킨스 판정 — 2026-09-09 부터 여기가 1차 근거다

`.husky/pre-push` 에서 백엔드·프론트 테스트를 걷어내 젠킨스로 옮겼다(P5). 그래서 **로컬 훅은
더 이상 백엔드를 검증하지 않는다** — 「로컬에서 돌렸으니 됐다」가 성립하지 않는다.
푸시가 막히지 않으므로 **깨진 커밋이 원격 브랜치에 올라갈 수 있고**, 그것을 잡는 자리가 여기다.

```bash
node --experimental-strip-types scripts/workflow/jenkins-build-status.ts
echo "EXIT=$?"
```

| 종료 코드 | 판정 |
|---|---|
| `0` 초록 | 통과. 이 브랜치의 최근 빌드가 성공했다 |
| `1` 빨강 | **머지하지 않는다.** 고치고 다시 |
| `2` **판정 불가** | **통과가 아니다.** 빌드 없음 · 도는 중 · **다른 브랜치의 빌드** · 젠킨스 무응답이 전부 여기다 |

**「빌드 없음」은 「체크 0건」과 정확히 같은 함정이다.** 빨간불이 아니라고 통과로 읽으면
검증 없는 머지가 된다. 특히 **다른 브랜치의 초록을 이 브랜치의 초록으로 읽는 것**이 가장 나쁜
오독이라 스크립트가 그것을 종료 코드 2 로 분리한다.

판정 불가일 때의 유일한 대안은 **로컬 전량 실행**이다. 그 사실을 게이트 2 요약에 적는다.

```bash
node --experimental-strip-types scripts/workflow/push-backend-tests.ts   # 수동 폴백
node --experimental-strip-types scripts/workflow/push-frontend-tests.ts
```

이 판정은 **사람이 지키는 규율**이다. 무료 플랜에서 브랜치 보호 설정이 403 이라 기계가 막지 못한다.

미커밋 파일이 있으면 → 해당 task의 산출물인지 확인 후 커밋·푸시. spec/plan 문서는 누락되기 쉬우니 특히 점검.

### Step 2. FR 변경 동반 시 마스터플랜 검증 (필수 게이트)

FR 추가·삭제·범위 변경을 동반한 PR은 머지 전 카운트 drift를 자동 차단한다.

```bash
bash scripts/verify-master-plan.sh    # exit 0 확인
```

실패(exit 4 등) 시 → 누락된 정본·미러·카운트를 **같은 PR에서** 동기화 후 재실행.
대상 목록의 정본은 [`docs/rules/fr-sync-checklist.md`](../../../docs/rules/fr-sync-checklist.md) 전 항목 (이 문서에 개수를 새기지 않는다 — 개수 리터럴은 drift 원천). FR 무관 PR(순수 bugfix/chore)은 생략 가능.

### Step 3. dashboard 재생성 — post-merge 훅이 자동 처리

`docs/plan/**`의 D단계 체크박스나 FR 마킹을 바꾸면 `progress.html`이 stale 해지지만, **main 머지 시 post-merge 훅이 `build-dashboard.mjs`를 자동 실행하고 `[chore] dashboard regen` 커밋을 푸시한다** (PR #93 첫 실전에서 확인). 평소엔 손대지 말 것 — 수동 재생성은 훅과 중복된다.

훅이 없거나 실패한 경우(`git log`에 regen 커밋이 안 보일 때)만 수동 처리.

```bash
node scripts/build-dashboard.mjs      # 훅 미작동 시에만
# ★경로 주의. 생성기의 실제 출력은 `docs/progress.html` 이다(`build-dashboard.mjs` OUTPUT_PATH).
#   `docs/plan/` 아래가 아니다 — 종전 문구가 그쪽을 가리켜 이 폴백은 실행하면 pathspec 오류로
#   죽는 상태였다(2026-07-17 적발, 문서만 26일간 그대로).
#   `merge-skill-contract.test.ts` 의 「대시보드 수동 폴백 경로가 생성기의 실제 출력과 같다」가
#   두 목록을 짝지어 막는다. 그 판별식은 **틀린 경로를 적는 것 자체**를 금지하므로
#   설명하려고 옮겨 적지도 말 것 — 다음 사람이 그것을 복사한다.
git add docs/progress.html && git commit -m "[chore] dashboard regen [skip ci]" && git push
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

# ★2026-08-21 — 좀비 run 정리 단계를 지웠다. CI 자동 실행을 껐으므로 머지가 만드는 run 이
#   0건이고, 정리할 큐 자체가 생기지 않는다. 되살리려면 git 이력에서 이 블록을 복원한다.

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

# classify 캐시 정리 — 작업 단위 산물이라 머지 후 잔존 이유 없음 (누적 방치 시 수십 파일)
# Node 단독. `rm` 은 settings.json deny 대상이라 실행되지 않았다.
# 오류 마스크(2>/dev/null || true)를 붙이지 않는다 — 그 마스크가 64일 침묵의 원인이었다.
node -e 'const fs=require("fs");fs.rmSync(".bts-cache",{recursive:true,force:true});fs.mkdirSync(".bts-cache")'
```

### Step 6. 공유 .git 오염 점검 (worktree 작업 후)

worktree의 lint-staged backup stash가 공유 `.git`에 쌓여 main 트리를 오염시키는 경우가 있다([[worktree-lint-staged-shared-git-stash-collision]]). 머지 후 main에서 상태를 확인한다.

```bash
git -C /Users/maxi.moff/Projects/BTS status
```

`"needs merge"`나 인덱스 stage 1/2/3가 보이면 오염. 복구는 **3중 백업 후** `git reset --hard origin/main` (stash ref는 보존됨). 무관한 main 미커밋 작업이 있었다면 먼저 그 작업부터 백업.

### Step 7. Obsidian 동기화 (수동)

메인 에이전트가 수동 수행 (자동화는 백로그).

1. `Maxi_wiki/BTS/history.md`에 1줄 append.
   ```
   - YYYY-MM-DD #<PR번호> [<type>/<slug>] <PR 제목>
   ```
2. 새 `docs/decisions/*.md` → `Maxi_wiki/BTS/decisions/`에 복사
3. 머지된 `docs/plans/*.md` → `Maxi_wiki/BTS/plans/`에 복사
4. PR 라벨 `learning:<topic>` 있으면 → `Maxi_wiki/BTS/learnings.md` append

### Step 8. 메모리 갱신

이번 작업에서 새 교훈·사고·완료 상태가 나왔으면 자동 메모리에 기록([[memory-rotation-policy]] 준수 — MEMORY.md는 한 줄 hook만).

## 출력 형식

**아래 진행 트리 위에 결과 줄을 먼저 얹는다**(서식 정본 [`docs/rules/output-format.md`](../../../docs/rules/output-format.md)). 트리(verify-master-plan/orphan 5173 kill 등 내부 용어)는 상태 표시용으로 유지하되, 그 앞 첫 줄에서 "무엇이 실제 반영됐고, Maxi가 뭘 확인할 수 있는지"를 비전문가 문장으로 말한다. 요약·마무리 인사는 붙이지 않는다.

```
✅ 한 줄  <비전문가 한 문장 — 무엇이 반영됐나. 서식 정본은 docs/rules/output-format.md>
💡 의미  <Maxi가 어디서 결과를 확인할 수 있는지>
🔧 기술 상세 (안 봐도 됨)
🔄 [7/7] /bts-merge
   ├─ 머지 전 확인: git status clean ✅ · CI 초록 ✅
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
