# 프로젝트 요약 화면 + 기본 착지 변경 — Jira 패리티 캠페인 PR ④

> 티어: T2 · type: ui · BC = 없음(`apps/web` 전용) · 마이그레이션 0건 · 신규 의존성 0

## 티어 정정 — 왜 이 파일이 존재하는가

캠페인 계획(`~/.claude/plans/bts-twinkling-simon.md` 분할표)은 이 PR 을 **T1** 로 적었다.
`apps/web` 만 건드리니 경량이라는 판단이었다.

**실측은 T2 다.** `apps/web/src/router.ts` 는 `surfaces.ts` 가 `SEC_FE` 로 분류하는 보안 표면이고,
`CLAUDE.md` 는 「보안 표면은 T2 미만 불가」로 못박는다. 라우트를 추가한다는 것은 가드 행렬에
행을 하나 더 만드는 일이라, 등록만 하고 분류를 빼먹으면 그 경로가 무가드로 열린다.

`tier-floor.test.ts` 가 이것을 잡았다 —
「보안 표면 1건을 건드렸는데 선언이 T0/T1 이다 (plan 파일 부재). 해당 경로: apps/web/src/router.ts」.
자의적 승격이 아니라 규칙이 정한 하한이므로 plan 파일을 만들어 선언을 맞춘다.
**게이트 2 요약에 선언 T1 / 실측 T2 를 나란히 싣는다.**

## Jira 대조

위젯 구성 자체(카드 4종 · 분포 4종 · 2주 특례)는 PR ③ 이 실물 조회로 확정해
`docs/plans/2026-09-03-project-summary-activity.md` §Jira 대조에 표로 남겼다. 여기서 다시 정하지 않는다.
이 PR 이 새로 대조하는 것은 **착지 경로**다.

| # | 항목 | 원문·근거 | 출처 | 조회일 | 구분 |
|---|---|---|---|---|---|
| J4-5 | 요약이 프로젝트 기본 착지다 | 프로젝트를 열면 Summary 가 먼저 뜬다 — "Summary" 가 프로젝트 뷰 목록의 첫 항목 | [summary view](https://support.atlassian.com/jira-software-cloud/docs/what-is-the-summary-view/) | 2026-09-03 | Cloud |
| J4-6 | 요약이 사이드바 최상단 뷰다 | 프로젝트 사이드바 뷰 목록에서 Summary 가 Board·Backlog 보다 위 | [navigate to your work](https://support.atlassian.com/jira-software-cloud/docs/navigate-to-your-work/) | 2026-09-03 | Cloud |
| J4-7 | 활동 피드 항목 형태 | 이슈 키 + 행위자 + 변경 요약 + 시각 — 값 상세 해석은 이슈 상세의 몫 | [work item view](https://support.atlassian.com/jira-software-cloud/docs/what-is-the-jira-work-item-view/) | 2026-09-03 | Cloud |

### 의도적 편차

- **X-J4-3 — 아카이브 프로젝트는 요약으로 보내지 않는다.** 설정 화면(danger zone)으로 보낸다.
  선재 규칙 G3 를 유지한 것이다 — 아카이브 프로젝트에는 활성 뷰가 없어 요약이 빈 껍데기가 된다.
- **X-J4-4 — 활동 피드가 값 표시명을 완전히 해석하지 않는다.** 유형·컴포넌트·버전·커스텀필드
  4종을 추가 조회해야 하는데 착지 화면이 감당할 비용이 아니다. 필드명은 정적 맵으로 해석하고
  값은 이슈 상세로 넘긴다(D5).
- **X-J4-5 — 사이드바 서브링크에 「요약」을 넣지 않는다.** J4-6 대조 항목(요약이 사이드바 최상단
  뷰)을 이 PR 에서는 구현하지 않는다. 프로젝트명 링크 자체가 요약으로 가므로 같은 목적지가 두 줄이
  되고, 캠페인 PR ⑨ 가 이 하위 목록을 보드 목록으로 통째로 갈아치운다 — 지금 넣으면 ⑨ 가 지운다.
- **X-J4-6 — 착지 4경로 중 아카이브 분기는 `resolveProjectPath` 하나만 갖는다.** 즐겨찾기·트리·
  생성 후 3경로는 아카이브 여부를 모른 채 요약으로 보낸다. 즐겨찾기의 `Favorite` 에는 `archived` 가
  없어 판정하려면 프로젝트 목록을 추가로 조회해야 하고, 트리·생성 직후는 아카이브 상태가 될 수 없다.
  즐겨찾기만 실제 노출 경로인데 아카이브 프로젝트의 요약은 빈 집계를 보여줄 뿐 오동작하지 않는다.

## Context

PR ③(#435)이 `GET /projects/{key}/summary` · `/activity` 를 냈지만 **소비처가 0** 이었다.
엔드포인트가 있는데 화면이 없으면 사용자에게 보이는 변화는 없다. 이 PR 이 그 결선이다.

동시에 「프로젝트를 연다」가 네 갈래로 갈려 있었다 — 목록 행·사이드바 트리·즐겨찾기·생성 후
이동이 각각 `/board` 로 갔다. Jira 는 프로젝트 진입점이 항상 요약이다.

## 결정

| # | 결정 | 근거 |
|---|---|---|
| D1 | 착지를 `/projects/{key}` 로 **통일** — 목록·트리·즐겨찾기·생성 후 4곳 전부 | Jira 패리티. 넷이 서로 다른 화면으로 가면 「프로젝트를 연다」가 여러 뜻이 된다 |
| D2 | `ProjectTree` 의 하위 직접 링크 「보드」는 **그대로** | 상수를 `PROJECT_BOARD_PATH` / `PROJECT_SUMMARY_PATH` 로 갈랐다. 하나로 묶으면 요약으로 옮기는 순간 하위 목록에서 보드로 갈 방법이 사라진다 |
| D3 | 요약 집계와 활동 피드를 **다른 쿼리 키**로 | 한 키로 묶으면 활동 403 이 카드·분포까지 에러로 만들어 착지 화면 전체가 빈다 |
| D4 | 활동 피드는 `changeItemSchema` **재사용** | 모양이 같은 Zod 스키마를 하나 더 두면 백엔드가 필드를 늘릴 때 한쪽만 따라간다 |
| D5 | 활동 피드 `refs` 는 **빈 객체** | 값 상세 해석(유형·컴포넌트·버전·커스텀필드 4종 추가 조회)은 착지 화면이 감당할 비용이 아니다. `resolveFieldLabel` 은 빈 refs 로도 정적 맵 → 키 폴백으로 안전하다 |
| D6 | `statusCategorySchema` 를 `z.enum` 이 아니라 `z.string()` 으로 | `category` 를 읽는 화면 코드가 0곳인데 enum 으로 좁히면 백엔드가 값을 하나 늘리는 순간 **아무도 안 쓰는 필드 때문에 요약 화면 전체가 파싱 에러**가 된다. 계약은 KDoc 으로 기록하고 확장에는 견디게 둔다 |

## 백엔드 계약 대조

`@JsonInclude(NON_NULL)` 이 걸린 4필드는 **키 자체가 빠져** 온다. Zod `.nullish()` 로 받고
화면이 각각 폴백한다.

| 필드 | 부재의 뜻 | 화면 폴백 |
|---|---|---|
| `statusName` | 워크플로우 스킴 해석 실패 | `statusKey` 를 그대로 |
| `priorityName` | SMALLINT 제약 밖 값 | 숫자를 그대로 |
| `assigneeId` | **미할당 묶음** | 「미할당」 |
| `assigneeName` | 사용자 조회 실패 | 「알 수 없는 사용자 N」 |

**마지막 두 줄을 같은 문구로 뭉치지 않는다.** 뭉치면 조회가 실패한 날 「미할당이 갑자기
늘었다」로 읽힌다. 짝 테스트가 이 구분을 봉인한다.

## 파일

### 신규 — `apps/web/src/`

| 경로 | 역할 |
|---|---|
| `api/project-summary.ts` | Zod 스키마 + fetch 2종 + 델타·빈판정 순수 함수. `cfd.ts` 동형 |
| `hooks/use-project-summary.ts` | 요약·활동 조회 단일 경계. 쿼리 키 2개 분리(D3) |
| `routes/projects.$projectKey.tsx` | 요약 화면. RouteAdapter + props 기반 Page |
| `components/project/summary/SummaryCards.tsx` | 카드 4장 |
| `components/project/summary/DistributionWidget.tsx` | 분포 위젯 공용 — 4종이 한 컴포넌트를 쓴다 |
| `components/project/summary/ProjectActivityFeed.tsx` | 활동 피드. 자체 쿼리 |
| `components/project/summary/summary-view-model.ts` | 순수 변환 전량. Fast Refresh 경고 해소(`project-list-paths.ts` 선례) |
| `i18n/project-summary-labels.ts` | 라벨 단일 출처 |

### 수정

| 경로 | 변경 |
|---|---|
| `src/router.ts` | `projectSummaryRoute` 추가 — 정적 `/projects/new` **뒤**에 둬야 동적 세그먼트가 `new` 를 삼키지 않는다 |
| `src/router.admin-guards.test.tsx` | `ROUTE_CLASS` 에 `PROTECTED_3` 등재 + 개수 38→39 |
| `src/components/project/project-list-paths.ts` | `resolveProjectPath()` → `/projects/{key}` (D1) |
| `src/components/layout/ProjectTree.tsx` | 프로젝트명·접힘레일 링크를 요약으로. 하위 「보드」는 유지(D2) |
| `src/components/favorite/FavoritesMenu.tsx` | PROJECT 즐겨찾기 착지 |
| `src/routes/projects.new.tsx` | 생성 후 착지 |

## TDD 순서 (실제로 밟은 것)

```
1. red   router.admin-guards 에 라우트 키 등재 → stale 3건 red
2. green router.ts 에 projectSummaryRoute 추가 → 개수 봉인이 38→39 를 잡음
3. green api/project-summary.ts + 짝 테스트 15건
4. red   착지 4곳 이전 → 기존 기대값 8건 red (계획 예고 7 + router.shell 카운트 1)
5. green 기대값 갱신
6. red   요약 화면 짝 테스트 13건 → 구현으로 green
```

## 비-공허 확인 (GREEN 커밋 뒤)

뮤테이션 4건 → red 10건, 전건 이름 붙은 실패.

| 뮤테이션 | 잡은 단언 |
|---|---|
| 활동 피드 컴포넌트 제거 | 위젯 격리 2 + 피드 3 |
| 담당자 두 부재를 한 문구로 | 「미할당이 부풀어 보인다」 1 |
| 「변동 없음」 → `'+0'` | 델타 문구 2 |
| 착지를 `/board` 로 되돌림 | 경로 3 |

원복 후 `git diff --stat` 0줄.

## 검증

```bash
cd apps/web && node_modules/.bin/vitest run     # 10,506 passed · EXIT=0
node_modules/.bin/tsc --noEmit                  # EXIT=0
node_modules/.bin/eslint <변경 파일>             # EXIT=0 · 경고 0
cd <worktree-root>
node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'
node scripts/build-doc-index.mjs --check        # drift 0
```

## 남은 위험

- **E2E 미작성.** 착지가 바뀌었으므로 `project-tree.spec.ts`(S2·S4) · `project-crud.spec.ts:141-142`
  가 실제 브라우저에서 어떻게 되는지는 확인하지 않았다. 유닛은 전량 초록이다.
- **브라우저 눈확인 미실시.** 새 화면이라 라이트/다크 양쪽을 봐야 한다.
- 두 항목 다 게이트 2 요약에 그대로 싣는다.
