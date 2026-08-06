# FR-UX-13 F16 후속 ⑦ — 백로그 에픽 컨트롤 짝 테스트 공유 셀렉터 모듈 승격

> slug: fr-ux-13-f16-f7-epic-control-test-contract
> type: **chore** (fast-track — Maxi 확정 2026-08-06)
> agent: qa-engineer (`chore` 기본값 `backend-engineer` 를 plan 메타로 오버라이드)
> primary_bc: null (BC 무관)
> task_count: 4 (착수 중 3 → 4, 아래 §범위 확장)
> branch: `qa/fr-ux-13-f16-f7-epic-control-test-contract` (접두사 표기만 구분류 잔존 — §분류 참조)
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

### 분류 (Maxi 확정 2026-08-06 · 2단계)

`classify-task.ts` 가 문구에 따라 `backend`↔`qa` 로 뒤집히고 slug 를
`fr-ux-13-f16-7-0`/`vitest` 로, `task_count` 를 `0` 으로 냈다.
「신호 0 → `backend` 기본값」은 사실상 unknown 이므로 **bts-start 단계 Maxi 확인 규칙**을 적용해
1차로 `type=qa` · `agent=qa-engineer` · `task_count=3` 으로 확정했다.

**그 뒤 `chore` 로 재확정했다 (Maxi 확정 A안).** 사유 — `type=qa` 는 fast-track 대상이 아니라
`/bts` 정식 경로가 **대화형 스킬 3종**(`grill-with-docs` · `office-hours` ·
`superpowers:brainstorming`)을 요구하는데, 이 작업은 **프로덕션 0줄 · 테스트 파일 2개 +
신규 모듈 1개**라 그릴링할 표면이 없다. 실체가 「기능 변경 없는 정리」라 `chore` 가 분류상으로도
정확하고, **임의 우회를 규칙 안으로 되돌린다** — 이 프로젝트가 반복해 데인
「안전장치가 실패가 아니라 부재로 빠지는 양식」을 피하기 위함이다.

- **정식 생략 (fast-track 규정).** `/bts-domain` · `/bts-spec` · `/bts-review-plan` · **게이트 1**.
- **게이트 2(머지 전 정지)는 생략하지 않는다.** 어떤 타입도 예외 없음.
- **단 `/bts-domain` 은 재분류 전에 이미 수행했고 결과를 아래에 보존한다** — 생략 규정이
  「해서는 안 된다」가 아니라 「안 해도 된다」이므로, 이미 나온 산출물은 버리지 않는다.
  실제로 그 단계가 `menuitemcheckbox` 실재 사례를 찾아냈다.
- **`agent` 오버라이드.** `detectAgent` 는 `chore → backend-engineer` 를 주지만 이 작업은
  `apps/web` 테스트 파일 전용이라 부적합하다. bts 규칙 **「plan task 메타 agent 지정이 우선」**에
  따라 `qa-engineer` 로 지정한다.
- **브랜치 접두사.** `qa/…` 로 이미 푸시돼 PR #345 가 열려 있다. GitHub 은 PR 의 head 브랜치를
  바꿀 수 없어 `chore/…` 로 개명하려면 **PR 재개설**이 필요하다. 접두사는 표기일 뿐이라
  브랜치를 유지하고 **PR 제목만 `[chore]` 로 정정**한다.

### ★ 범위 확장 — 복사본은 2개가 아니라 **8개**다 (착수 중 실측)

정본 §4.11 ⑦ 은 *"셀렉터 헬퍼가 글자 단위로 같지만 **복사본 2벌**"* 이라고 적었다.
실측 결과 헬퍼가 참조하는 **이름 상수 6개도 두 파일에 각각 따로 정의**돼 있다.

| 항목 | `BacklogEpicPanel.test.tsx` | `BacklogFilterBar.test.tsx` | 값 | 드리프트 |
|---|---|---|---|---|
| `EPIC_CONTROL_ROLE` | `:83` | `:116` | `'checkbox'` | 가능 |
| `queryEpicControls()` | `:86-96` | `:119-129` | 본문 동일 | 가능 |
| `EPIC_ALPHA` | `:40` | `:60` | `'ATLAS-100'` | 가능 |
| `EPIC_BETA` | `:41` | `:61` | `'ATLAS-200'` | 가능 |
| `EPIC_UNRESOLVED` | `:43` | `:63` | `'ATLAS-900'` | 가능 |
| `EPIC_ALPHA_NAME` | `:45` | `:65` | `'결제 개편'` | 가능 |
| `EPIC_BETA_NAME` | `:46` | `:66` | `'알림 리팩터'` | 가능 |
| `NO_EPIC_LABEL` | `:26` | `:58` | `backlogLabels.filter.noEpic` | **값은 i18n 정본 파생이라 안전 · 바인딩만 중복** |

**역할·헬퍼 2개만 옮기면 나머지 6개가 그대로 어긋난 채 남는다** — 이 FR 이 PR #342·#343·#344
3연속으로 맞은 **「봉합이 절반」**의 네 번째 판이 된다. 공유 모듈이 **8개 전부**를 소유해야 한다.

→ `task_count` **3 → 4**.

동기화 강제 장치는 오늘도 **주석 문장 하나뿐**이다
(`"셀렉터를 여기서 바꾸면 BacklogFilterBar.test.tsx 도 같은 PR 에서 함께 고쳐야 한다"`).

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

## 도메인 정리

- **BC. `null` — 미탐지가 아니라 의도된 값이다.** `classify-task.ts` 의 `detectBoundedContext` 가
  `type ∈ {migration, qa, design, chore}` 에 대해 명시적으로 `null` 을 반환한다(「BC 무관」).
  인접 도메인은 `agile-planning`(백로그·에픽)이나 **BC 코드는 0줄 건드리지 않는다.**
- **영향 엔티티. 없음.** 프로덕션 파일 0개. 변경 대상은 테스트 파일 2개 + 신규 테스트 전용 모듈 1개.
- **새 용어. 0건.** F16 ADR `## 신규 용어` 가 같은 판정을 이미 내렸다 — *"「에픽 패널」·「필터바」는
  UI 배치 용어라 유비쿼터스 언어 대상이 아니다"*. 테스트 셀렉터 계약은 그보다 더 멀다.
  `glossary.md` 의 8개 섹션(핵심 엔티티 · 관계/연결 · 워크플로우/자동화 · 권한 · 검색/AQL ·
  인증 · 데이터 무결성 키워드 · 변경 규칙)에 **테스트 관련 섹션 자체가 없다.**
  → `glossary.md` 갱신 **불필요**. `domain/agile-planning.md` 갱신 **불필요**.

### 기존 결정과의 관계 — 충돌 아니라 **이행**

`docs/decisions/2026-08-06-fr-ux-13-f16-backlog-filter-epic.md:84-86` (D-2 파생 결정).

> 가시성은 **양방향 짝 테스트**로 못박았다 — 숨김 단언만 있으면 컴포넌트가 아무것도 안 그려도
> 통과하기 때문이다(**같은 셀렉터**로 「없다」와 「있다」를 둘 다 잰다).

**그런데 「같은 셀렉터」가 오늘 이미 절반만 참이다.** 두 파일이 글자 단위로 같을 뿐 복사본 2벌이라
같음이 **아무것도 보장하지 않는다.** 이 PR 은 ADR 을 무효화하지 않고 **그 문장을 사실로 만든다.**
→ 신규 ADR 불필요. 대신 F16 ADR 에 이행 사실 1줄 보강이 적절하다(`/bts-plan` task 로 편입).

### ★ 도메인 검증이 찾아낸 것 — `menuitemcheckbox` 는 가정이 아니다

정본 §4.11 ⑦ 은 *"패널이 Radix 메뉴로 바뀌어 role 이 `menuitemcheckbox` 가 되면"* 이라는
**가정법**으로 위험을 서술한다. 실측 결과 **그 형태가 같은 저장소에 이미 실재한다.**

| 컴포넌트 | 프리미티브 | 렌더되는 role | 증거 |
|---|---|---|---|
| `BacklogEpicPanel` | shadcn `Checkbox` | `checkbox` | `BacklogEpicPanel.tsx:221` |
| `ColumnSelector` | Radix 메뉴 | **`menuitemcheckbox`** | `ColumnSelector.test.tsx:49,56,64,68,81` · `issues.index.test.tsx:1243,1264` |

즉 「목록에서 여러 개 고르기」라는 **같은 성격의 UI 두 개가 서로 다른 a11y role 로 이미 공존한다.**
가정된 미래가 아니라 현재 상태다 — ⑦ 의 위험 서술은 오히려 **과소평가**였다.

- **이 PR 의 범위 판정에 미치는 영향.** role 은 `docs/design/jira-parity-contract.md` 에
  **명시된 바가 없다**(에픽 언급 0건). 즉 제품 계약이 아니라 **선택한 프리미티브의 부산물**이다.
  → 공유 모듈은 **테스트 공간에 두는 것이 맞다**(프로덕션 계약 승격 불필요). 원안 유지.
- **범위 밖 후속 후보 1건 신규.** 「에픽 패널과 컬럼 셀렉터가 같은 성격인데 role 이 갈린다」는
  접근성·Jira 패리티 관점의 **디자인 일관성 문제**다. 이 PR 은 테스트 배선만 고치므로 건드리지
  않는다. §4.11 후속 목록에 등재 후보로 남긴다.

### 이 단계의 지위 — `chore` 재분류로 **정식 생략 대상**이 됐다

`/bts-domain` 은 `classify.type ∈ {bugfix, chore}` 에서 **단계 전체가 규정상 생략**된다.
즉 위 내용은 **의무가 아니라 이미 확보한 덤**이다.

경위 — 재분류 전(`type=qa`) 에는 이 단계가 의무였고, 그 안의 `grill-with-docs`(대화형 도메인
그릴링) 한 종만 임의 우회한 상태였다. 임의 우회를 계속 쌓는 대신 **fast-track 이라는 규정된
경로로 되돌린 것이 A안의 요지**다. 결과적으로 우회는 0건이 됐다.

**산출물은 버리지 않는다.** 실제로 이 단계가 정본이 가정법으로 적은 위험(`menuitemcheckbox`)이
**실재함**을 찾아냈다. 생략 규정은 「해서는 안 된다」가 아니라 「안 해도 된다」이므로 보존한다.

**게이트 2 요약에 명시할 것** — fast-track 으로 생략된 단계 목록
(`/bts-spec` · `/bts-review-plan` · 게이트 1)과, `/bts-domain` 은 생략 대상이나 **실제로는
수행됐다**는 사실.

## 스펙 — **fast-track 정식 생략** (`type == chore`)

`/bts-spec` 은 `classify.type ∈ {bugfix, chore}` 에서 **단계 전체가 규정상 생략**된다.
사유(스킬 정본) — *"버그 수정은 스펙이 「기존 동작 복원」으로 자명. office-hours/brainstorming
비용 > 효익"*. 이 작업은 **기능 변경 0 · 프로덕션 0줄**이라 같은 논거가 그대로 적용된다.

대신 스펙이 담았을 3가지를 여기에 못박는다.

**대상 (What).** 위 §범위 확장 표의 **8개 항목 전부**를 테스트 전용 공유 모듈 1개로 승격하고,
두 테스트 파일은 `import` 만 한다. 프로덕션 파일 0개.

**완료 기준 (Done).** 아래 §완료 기준의 비-공허 확인 3분기.

**범위 밖 (Not).**
- ⑤ 라벨 5종 i18n 이관 — `ALREADY_SEALED`(`create-entry-point-names.test.ts:10-15` 가 그
  import 를 관례의 **처방**으로 명시). 1차 조사의 「⑤+⑦ 한 PR」 권고를 실측으로 기각.
- 에픽 패널 ↔ 컬럼 셀렉터의 a11y role 불일치 — 도메인 단계 신규 발견이나 **디자인 일관성**
  문제이지 테스트 배선 문제가 아니다. §4.11 등재 후보로만 남긴다.
- 프로덕션 `BacklogEpicPanel.tsx` 의 role 변경 — 이 PR 은 **현재 role 을 사실로 고정**할 뿐
  바꾸지 않는다.

## Brainstorming Check — **fast-track 정식 생략** (`type == chore`)

`/bts-spec` Phase B 이므로 위와 함께 생략된다. 이 단계가 찾았어야 할 gap 은 **착수 중 실측이
이미 하나 찾아냈다** — 정본이 「복사본 2벌」이라 적은 것이 실제로는 **8개**였다(§범위 확장).

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
