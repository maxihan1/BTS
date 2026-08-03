# worktree 훅 배선 — pre-commit 이 실작업 커밋에서 침묵으로 죽는 구조 봉합

> slug: worktree-hook-wiring
> type: chore
> 생성: 2026-08-03

## Brief

`workflow-scripts-ci` 실패 메일이 PR 마다 반복해서 왔다. 원인 조사 결과 인덱스 drift 였고,
그것을 막으라고 #329 가 넣어둔 pre-commit 훅이 **BTS 의 모든 실작업 커밋에서 한 번도 실행되지
않았다**는 것이 실측으로 확인됐다.

## 근본 원인 — 훅이 실패하는 게 아니라 존재하지 않는다

```
$ git worktree add --detach <probe>
$ git -C <probe> config --get core.hooksPath
.husky/_                       ← 상대 경로. worktree 루트 기준으로 해석된다
$ ls -a <probe>/.husky/
.  ..  post-merge  pre-commit  ← _/ 가 없다
$ ls <probe>/.husky/_/pre-commit
No such file or directory      ← git 은 훅을 못 찾으면 경고 없이 건너뛴다
```

세 사실이 겹쳐서 생긴다.

1. husky 는 `core.hooksPath` 를 **상대 경로** `.husky/_` 로 설정한다.
2. `.husky/_/` 는 `pnpm install` 이 만드는 shim 이라 `.gitignore` 대상 — 커밋되지 않는다.
3. worktree 는 커밋된 파일만 checkout 하므로 `_/` 가 없다. git 은 조용히 스킵한다.

`CLAUDE.md §핵심 패턴` 이 "`.worktrees/<slug>` 안에서만 Edit/Write" 를 강제하므로,
훅이 지켜야 할 커밋 **전량**이 훅이 꺼진 곳에서 만들어진다.

### 대가 (실측)

PR #333 커밋 순서. 문서 3건을 만든 커밋부터 인덱스 재생성 커밋 전까지 5커밋 구간이 red 였다.

| 커밋 | 내용 | CI |
|---|---|---|
| `fddc9fc5` | plan 스텁 → `docs/plans/` 생성 | 🔴 |
| `3522c883` | 도메인 정리 → `docs/decisions/` 생성 | 🔴 |
| `6321a6c6` | 스펙 → `docs/specs/` 생성 | 🔴 |
| `487d4612` | plan | 🔴 |
| `f8f6904d` | plan 리뷰 | 🔴 |
| `72edbd90` | **doc index regen** | 🟢 |

#331 도 같은 양식으로 4회 실패했다. 실패 메일 6통이 전부 이 원인이다.

### 부수 결함 — 로컬과 CI 가 서로를 안 보는 두 목록

`/bts-impl` Step 4(PR push 전 최종 점검)가 돌리는 명령은 `./gradlew test ktlintCheck detekt`
와 `pnpm typecheck lint test` 뿐이었다. CI 가 돌리는 `pnpm test:workflow` 가 로컬 목록에 없다.
[[two-lists-never-check-each-other]] 의 지배 양식이다. 훅을 살려도 이쪽이 남으면
"로컬 초록 → push → CI 빨강" 은 계속된다.

## 처방 — 3층

| 층 | 무엇 | 어디 |
|---|---|---|
| 1 | worktree 생성 시 `.husky/_` 심볼릭 링크 연결 | `bts-start` Step 3 |
| 2 | 최종 점검에 `pnpm test:workflow` + 인덱스 재생성 절차 | `bts-impl` Step 4 |
| 3 | 위 둘의 배선을 기계로 강제 | `scripts/workflow/worktree-hook-wiring.test.ts` |

## 체크리스트

- [x] `.gitignore` 의 `.husky/_/` → `.husky/_` (끝 슬래시 제거)
- [x] 판별식 작성 → RED 4건 확인
- [x] `bts-start` Step 3 훅 연결 배선
- [x] `bts-impl` Step 4 검사 목록 정합 + 인덱스 재생성 절차
- [x] CI 트리거 양쪽에 `.gitignore` · `.husky/**` 추가
- [x] 판별식 92/92 GREEN
- [x] 훅이 실제로 drift 를 막는지 뮤테이션 확인
- [x] 인덱스 재생성 + 커밋

## Context Notes — 결정과 근거

### 왜 심볼릭 링크인가 (`git config core.hooksPath` 절대경로 대신)

| | 심볼릭 링크 | `core.hooksPath` 절대경로 |
|---|---|---|
| 적용 범위 | worktree 국소 | 공유 `.git/config` → 전역 |
| 반복 필요 | worktree 생성 때마다 | 1회 |
| `pnpm install` 내성 | **있음** (링크는 그대로) | **없음** — husky 가 상대경로로 되돌린다 |

husky 는 매 `pnpm install` 마다 `core.hooksPath` 를 `.husky/_` 로 다시 쓴다. 절대경로 방식은
그때 **조용히** 원복되고, 원복 사실은 다음 CI 빨간불까지 드러나지 않는다 — 지금 고치려는 결함과
같은 양식이다. 링크는 husky 재실행과 무관하므로 이쪽을 택했다.

### `.gitignore` 끝 슬래시를 뗀 이유

`.husky/_/` 는 **디렉토리만** 매칭한다. worktree 가 갖는 것은 심볼릭 링크(git 이 파일로 본다)라
매칭되지 않아 매 작업이 `?? .husky/_` 를 달고 다닌다. 실측으로 확인하고 `.husky/_` 로 고쳤다.

### 판별식이 "코드블록 안에 세 요소" 를 요구하는 이유

문구를 통째로 요구하면 표현만 바꿔도 깨지고, 한 단어만 요구하면 산문에 스쳐도 통과한다.
`ln -s` · `.husky/_` · `.worktrees/` 세 요소가 **한 펜스 블록 안에** 있을 것을 요구해 둘 다 피한다.
양성 대조군에서 "산문에만 있는 언급"이 통과하지 않음을 확인한다.

### 훅 본체까지 검사하는 이유

연결만 강제하고 훅 내용을 안 보면, 누가 `build-doc-index.mjs --check` 줄을 지웠을 때
배선 단언은 초록인 채 아무것도 막지 못한다. [[seal-blinds-existing-guard]] 의 재발 방지.

### 남긴 것

`bts-codereview` 단계는 건드리지 않았다. 훅이 살아나면 그 단계의 봉합 커밋도 자동으로
같은 검사를 받으므로 별도 배선이 중복이다.
