<!-- worktree 안에서 도구를 부르는 방법과 그 사고 배경 — /bts-impl Step 4 의 참조 정본 -->
# worktree 명령 규율 — 배경과 전문

`SKILL.md` Step 4 가 참조한다. **판정에 걸리는 명령 3블록은 `SKILL.md` 본문에 있다** — 여기엔 그 명령이 왜 그 모양이어야 하는지를 둔다.

## §1. `pnpm` 래퍼가 worktree 에서 죽는 이유

BTS 의 모든 실작업은 worktree 안에서 이뤄지는데, 거기서 `pnpm <script>` 를 부르면 pnpm 이
**심볼릭 `node_modules`** 를 보고 의존성 검사를 돌려 `pnpm install` 을 트리거하고
`ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY` 로 죽는다(2026-08-12 실측).

`.husky/pre-commit` 은 이미 같은 이유로 `pnpm exec` 를 쓰지 않고 바이너리를 직접 부르며,
`worktree-hook-wiring.test.ts` 의 `PNPM_WRAPPER` 단언이 그것을 강제한다 — **훅만 고쳐졌고
스킬 문서는 그 사정거리 밖이라 돌지 않는 명령을 지시하고 있었다.** 그 절반 봉합이 이 문서의 존재 이유다.

⚠️ **`pnpm install` 로 우회하지 말 것.** worktree 에서 install 을 돌리면 main 의
`node_modules/.modules.yaml` 을 덮어써 main 을 망가뜨린 전례가 있다(2026-07-17, 3일간 8회 머지).

## §2. 두 목록이 서로를 안 보면 무슨 일이 나나

`package.json` 의 `test:workflow` 와 `SKILL.md` 의 대체 명령은 **같은 파일 목록**을 돌아야 한다.
한쪽만 바뀌면 로컬이 CI 보다 적게 돌면서 초록이 되고, "로컬 초록 → push → CI 빨강" 이 구조적으로
반복된다([[two-lists-never-check-each-other]]).

`worktree-hook-wiring.test.ts` 가 `package.json` 에서 글로브를 **직접 읽어** `SKILL.md` 본문과
대조한다. 그래서 `SKILL.md` 의 글로브 문자열은 장식이 아니라 계약이다 — 축약하거나 `…` 로 줄이면 red.

**★ `pnpm test:workflow` 를 로컬 목록에서 빼지 말 것.** CI(workflow-scripts-ci)가 돌리는 것이
정확히 그 명령이다.

## §3. 종료 코드로 판정하는 이유

「Tests N passed」와 「EXIT=1」은 **같은 실행에서 동시에 참**일 수 있다. vitest 가 unhandled
rejection 을 `Errors 1` 로 보고하면서 `process.exitCode=1` 을 세우는 경우이고, `TODOS.md` 의
「전체 스위트 실행에서 `pnpm test` 가 간헐적으로 exit≠0」 항목이 그것이다.
FR-UX-09 F2 세션 체크포인트가 **통과 건수만 읽고 초록으로 보고**했다가 게이트 2 재검증에서 교정됐다.

파이프가 특히 위험하다. `set -o pipefail` 이 없는 셸에서는 **파이프 마지막 명령의 종료 코드**만
남으므로 `pnpm test 2>&1 | tail -20` 은 재려던 값을 지운다. 에이전트가 출력을 줄여 읽으려 할 때
정확히 이 형태를 쓰기 때문에 위험이 크다.

보고에는 **통과 건수와 종료 코드를 함께** 적는다 — 하나만 적으면 다음 사람이 나머지를 확인했는지 알 수 없다.

## §4. 문서 인덱스 재생성

새 문서(spec/plan/decision)를 만든 작업이면 인덱스를 먼저 재생성한다. 판별식 룰 I·J 가 이것을 검사한다.

```bash
node scripts/build-doc-index.mjs                    # 파일을 쓴다
git status --porcelain docs/INDEX*.md               # 변경이 있으면
git add docs/INDEX*.md && git commit -m "chore: doc index regen — <slug> 등재"
```

worktree 훅이 연결돼 있으면(`/bts-start` Step 3) 이 재생성을 잊은 커밋은 pre-commit 에서 막힌다 —
위 절차는 그 차단을 푸는 방법이다. 배선 강제는 `scripts/workflow/worktree-hook-wiring.test.ts`.
