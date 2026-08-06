# FR-UX-13 F16 — 백로그 필터바 + 에픽 패널

> slug: fr-ux-13-f16-backlog-filter-epic
> type: ui (classify 스크립트 `backend` 를 오버라이드 — 아래 §분류 근거)
> agent: frontend-engineer
> primary_bc: agile-planning
> 생성: 2026-08-06

## Brief

### 사용자 원문

FR-UX-13 F16 — 백로그 필터바 + 에픽 패널을 구현한다. 정본은
`docs/plan/product/personalization.md` §4.11 (438행 F16 항목, 447행 아키텍처 노트).
프론트 전용이며 `components/filters/FilterBar.tsx` 의 확장 슬롯 4종
(`leadingSection`/`leadingChips`/`extraActiveCount`/`onReset`)을 그대로 재사용한다.
에픽 패널은 `api/epic-children.ts` · `components/issue/EpicChildrenSection.tsx` 재사용
여지를 먼저 검토한다. 착수 전 `backlog.spec.ts` · `BacklogBoard.test.tsx` 재작성 범위를
산정한다. B3(백엔드 백로그 조회 범위 축소)는 정본상 명시적으로 범위 밖이다.
F16 이 끝나면 FR-UX-13 의 D1~D7 체크박스를 닫는다.

### 분류 근거 (classify 오버라이드)

`scripts/workflow/classify-task.ts` 가 `type=backend` / `agent=backend-engineer` 를 냈으나
`ui` / `frontend-engineer` 로 교정했다. 근거 4겹.

1. 정본 `:439` — "**F16 은 정본상 「프론트 전용」**이라 이미 잘려서 도착한 응답을
   클라이언트에서 다시 거를 뿐 `truncated` 를 내릴 수 없다"
2. 정본 `:447` 아키텍처 — "**프론트 전용 예상**"
3. 정본 `:452-453` — D4 백엔드 "없음 예상", D5 백엔드 테스트 "해당 없음 예상"
4. 직전 F15 (#343) 실측 — **백엔드 0줄** (정본 `:437`)

같은 오분류가 F15 에서도 났고 동일 근거로 교정한 전례가 있다.

### 착수 시점 실측 — 정본 수치 오류 1건

정본 `:447` 은 "착수 전 `backlog.spec.ts`(536행)·`BacklogBoard.test.tsx`(759행) 재작성
범위를 먼저 산정한다"고 적었으나 **두 수치 모두 낡았다**. F15 (#343) 가 두 파일을 크게
키운 뒤 정본이 갱신되지 않았다.

| 파일 | 정본 `:447` | 실측 (2026-08-06) | 배율 |
|---|---|---|---|
| `apps/web/e2e/backlog.spec.ts` | 536행 | **1,521행** | 2.8배 |
| `apps/web/src/components/backlog/BacklogBoard.test.tsx` | 759행 | **1,702행** | 2.2배 |

즉 재작성 산정 대상이 1,295행이 아니라 **3,223행**이다. 이 FR 에서 정본 수치가 실측과
어긋난 **세 번째** 사례다 (F5 줄번호 `:217`/`:214` → 실제 `:102`/`:99`, F15 `:246,248` →
실제 `:178`, 이번 테스트 행수). **정본의 줄번호·행수는 착수 시 전부 재측정한다.**

### 승계 제약 (F15 에서 확정, 이 PR 이 지켜야 함)

- **`truncated=true` 면 스프린트 완료 차단**은 F15 가 도입했고 **F16 이 풀지 못한다.**
  `getBacklog` 는 쿼리 파라미터가 0개라 F16 의 필터는 **이미 잘려서 도착한 응답을 클라이언트에서
  다시 거르는 것**뿐이다. 필터 UI 가 `truncated` 를 내리는 것처럼 보이게 만들면 **가짜 그린**이다.
- **B3 는 범위 밖** (정본 `:439` 명시). 백엔드 0줄을 유지한다.
- 선재 결함 2건(COMPLETED 스프린트 카드 위 드롭 `:441`, 모바일 셸 375px `:442`)도 범위 밖.

### 재사용 후보 (착수 전 실측 필요)

| 대상 | 경로 | 실측 |
|---|---|---|
| 필터바 | `apps/web/src/components/filters/FilterBar.tsx` | 343행 (정본 일치) |
| 에픽 API | `apps/web/src/api/epic-children.ts` | 존재 (17KB) |
| 에픽 섹션 | `apps/web/src/components/issue/EpicChildrenSection.tsx` | 존재 (11KB) |

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
