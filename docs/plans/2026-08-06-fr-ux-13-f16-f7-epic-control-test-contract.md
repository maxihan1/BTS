# FR-UX-13 F16 후속 ⑦ — 백로그 에픽 컨트롤 짝 테스트 공유 셀렉터 모듈 승격

> slug: fr-ux-13-f16-f7-epic-control-test-contract
> type: qa
> agent: qa-engineer
> primary_bc: null (BC 무관)
> 생성: 2026-08-06
> FR: **FR-UX-13** (§4.11 후속 ⑦ · 정본 `docs/plan/product/personalization.md`)

## Brief

### 사용자 원문

FR-UX-13 F16 후속 ⑦ — 백로그 에픽 컨트롤의 짝 테스트(`BacklogFilterBar.test.tsx` ↔
`BacklogEpicPanel.test.tsx`)가 셀렉터 헬퍼를 **글자 단위로 동일한 복사본 2벌**로 갖고 있고
동기화 강제가 주석 한 줄뿐이다. 패널이 Radix 메뉴로 바뀌어 role 이 `menuitemcheckbox` 가 되면
패널 테스트만 red 가 되고 **필터바 쪽은 존재하지 않는 role 을 0개 세며 영구 초록**이 된다.
`EPIC_CONTROL_ROLE` 과 `queryEpicControls()` 를 테스트 전용 공유 모듈 하나로 승격해
두 파일이 `import` 만 하게 한다. **프로덕션 코드 0줄.**

### 분류 (Maxi 확정 2026-08-06)

`classify-task.ts` 가 문구에 따라 `backend`↔`qa` 로 뒤집히고 slug 를
`fr-ux-13-f16-7-0`/`vitest` 로, `task_count` 를 `0` 으로 냈다.
「신호 0 → `backend` 기본값」은 사실상 unknown 이므로 **bts-start 단계 Maxi 확인 규칙**을 적용해
`type=qa` · `agent=qa-engineer` · `task_count=3` 으로 확정했다.

### 착수 전 실측 (3라운드 21 에이전트 · 2026-08-06)

**결함 실재 확증.**
- `queryEpicControls()` 가 **두 파일에 복사본 2벌**로 실재
  (`BacklogEpicPanel.test.tsx:83-96` ⟺ `BacklogFilterBar.test.tsx:116-129`, 글자 단위 동일).
- 동기화 강제 장치는 **주석 한 줄뿐**.
- 프로덕션 소비처 **0** — 이 작업은 테스트 파일만 만진다.

**기준선 (실행 확인).**
- `vitest run` (apps/web) → **570 files / 9,231 tests 전량 통과, 실패 0** (소요 591s).
- DnD 유닛 4파일 → 59 passed.
- ⚠️ **정본 `personalization.md:443` 의 「E2E `backlog.spec.ts` 82/82 + 10파일 동반 47/47」은
  실측과 불일치** — `--list` 실측 **35 tests in 1 file**, 피어 10파일 **75 tests**,
  전체 스위트 696 tests / 144 files. 유닛도 정본 「9,214」 대비 실측 **9,231**(파일 570 은 일치).
  이 PR 에서 정정 대상.

**⑤ 는 이 PR 범위가 아니다 (기판정).**
1차 조사는 ⑤(라벨 5종 컴포넌트 모듈 잔류)를 ⑦ 과 한 PR 로 묶자고 했으나,
`create-entry-point-names.test.ts:10-15` 가 그 import 를 **관례의 처방으로 명시**하고 있어
`ALREADY_SEALED` 로 판정됐다. 재론하려면 후속이 아니라 상위 결정 재개정이다.
**따라서 이 PR 은 ⑦ 단독.**

### 선례 교훈 2건 (Obsidian `learnings.md`)

- **#22 (2026-05-26)** — E2E 셀렉터는 i18n 정본을 `import` 한다. 하드코딩 리터럴은
  라벨 변경 시 **silent cascade break** 를 만든다. → 정본 참조 = single source of truth.
- **#16 (2026-05-23)** — mirror data 는 helper 를 **호출**하게 만들어 drift 를 **본질 차단**한다.
  회귀 가드 테스트는 **보조**이고 본질 차단이 우선이다.

→ 두 교훈 모두 「대조 판별식 추가」가 아니라 **「공유 모듈 승격」**을 가리킨다.

### 완료 기준 (비-공허 확인)

공유 모듈의 `EPIC_CONTROL_ROLE` 을 `'menuitemcheckbox'` 로 바꾸고 두 파일을 함께 돌린다.

- **두 파일이 동시에 red** → 성공.
- **한쪽만 red** → 봉합 실패(복사본이 아직 남았다).
- **둘 다 green** → 셀렉터가 어느 단언에도 안 물린 것으로 **더 나쁘다**.

⚠️ 뮤테이션 검증은 **GREEN 을 먼저 커밋한 뒤** 수행한다 — 미커밋 상태에서 `git checkout --` 하면
작업이 날아간다 (`mutation-test-requires-committed-baseline`).

### 실행 환경 규율 (worktree)

`node_modules` 는 main 에서 심볼릭 연결돼 있다. **worktree 에서 `pnpm` 을 절대 실행하지 말 것** —
pnpm 의 deps 검사가 main 의 `.modules.yaml` 을 유령 경로로 박제해 **worktree 제거 후 main 훅이
깨진다**(`worktree-pnpm-verify-deps-symlink`). 검증은 바이너리 직접 호출로만 한다.

```
node_modules/.bin/vitest run <path>
node_modules/.bin/tsc -p tsconfig.app.json --noEmit
node_modules/.bin/eslint src
```

진단 시 **파이프 금지** — `cmd | head` 는 `head` 의 exit 0 을 뱉어 깨진 툴체인을 정상으로 오독시킨다.
`cmd > file 2>&1; echo "EXIT=$?"` 를 쓴다.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
