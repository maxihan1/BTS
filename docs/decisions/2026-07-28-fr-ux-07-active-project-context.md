# ADR — FR-UX-07 신설 + 활성 프로젝트(Active Project) 컨텍스트 모델

> 날짜: 2026-07-28
> 상태: 결정됨 (Maxi 확정)
> 관련 FR: FR-UX-07 (Jira 인터랙션 패리티, 신설) · FR-UX-06 (시각계층 개편, 완료) · FR-PF-01/02 (환경설정·시작페이지)
> 관련 slug: `fr-ux-07-active-project-key`

## 맥락

FR-UX-06 22 PR 체인(#308)이 **시각 계층**(ADS v2 토큰 70종·프리미티브 24종·`_shell` 셸 구조)을 완결했으나 **인터랙션 계층**은 손대지 않았다. Maxi 지적 — "UI/UX가 지라 클라우드 사용성과 많이 다르다".

조사 중 개편 이전의 **실동작 결함**이 먼저 나왔다. `DEFAULT_PROJECT_KEY = 'ATLAS'` 하드코딩(`routes/issues.index.tsx:47,788` · `routes/search.tsx:21,478`) 때문에 이슈 목록으로 가는 **진입로 4개**(사이드바 "이슈" · 명령 팔레트 "내 이슈" · 로그인 후 시작 페이지 · 단축키 `g i`)가 전부 ATLAS 프로젝트만 보여준다. `issuesIndexRoute.validateSearch`(`router.ts:126-158`)에 `projectKey`가 없어 URL로 바꿀 수단조차 없다.

세 가지 미결 결정이 이 PR의 형태를 좌우했다.

1. **FR 등록** — 27 PR 로드맵을 FR-UX-06 확장으로 볼 것인가, 신규 FR로 볼 것인가.
2. **"활성 프로젝트"가 도메인 개념인가** — 그렇다면 무엇이 그것을 결정하는가.
3. **영속 위치** — 서버(`user_preferences`)인가 클라이언트(localStorage)인가.

## 결정

### D1. FR-UX-07 신설 (131 → 132). FR-UX-06 확장은 불가

**근거.**
- `docs/plan/product/personalization.md` §4.4 FR-UX-06의 D1~D7이 **전량 `[x]`** 이고, **D4 백엔드 = "없음 (ADR D5 — 물리 `apps/web`)"** 으로 닫혀 있다. 이번 로드맵은 백엔드 B1(생성 필드)·B2(카드 필드)를 포함하므로 그 판정과 정면 충돌한다.
- 완료 FR의 D단계를 `[x]` → `[~]` 로 되돌리면 `docs/progress.html` 진척률이 역행하고, FR-UX-06을 FR로 만든 이유("진척 가시성 우선")를 스스로 무너뜨린다.
- **신설 근거가 이미 문서에 있다** — `personalization.md:142`(FR-UX-05 §4.3): *"컨텍스트 의존 단축키(`j/k/e/m/s`)는 **후속 FR로 제외**(Maxi 결정 2026-07-05)"*. 이번이 그 후속이다.

**선점 검증(2026-07-28).** `docs/` 전체 `FR-UX-07` **0건** · 열린 PR 0 · worktree 0 · `verify-master-plan.sh` 기준선 EXIT=0 (131/131). 문서에만 있는 `FR-AU-12`·`FR-IS-12`는 **선점 아님** — 전자는 `FR-AU-12 ≡ FR-PM-02`로 흡수 확정(해당 ADR 정정 단락), 후자는 실제로 FR-MV-02로 착지. 둘 다 fr-index 미등록.

> 선점은 `fr-index.md`가 아니라 `docs/specs/`에서 일어난다(learnings 2026-07-17). 그래서 fr-index 뿐 아니라 specs·plans·열린 PR·worktree를 전수 확인했다.

### D2. 논리 BC = personalization / 물리 = `apps/web`

FR-UX-05 D4 · FR-UX-06 D5 선례를 그대로 승계한다. glossary가 이 패턴을 이미 정본화하고 있다 — **퀵 필터**(FR-UX-01) *"물리 BC=agile-planning·논리=personalization"*, **캘린더 피드 토큰**(FR-CA-02) *"personalization 논리 BC / identity-access 물리"*.

> `classify-task.ts`는 이 작업을 `type=backend`/`primary_bc=issue-tracking`으로 오분류했다(같은 오분류 3회째). 변경 파일이 전량 `apps/web`, `backend/` 0건임을 실측해 정정했다.

### D3. 활성 프로젝트(Active Project) = 4단 해소 순서

**활성 프로젝트**는 "사용자가 지금 작업 중인 프로젝트"다. 값은 저장된 상태 하나가 아니라 **해소 함수**로 정의한다.

```
① 현재 URL 의 projectKey        (경로 파라미터 /projects/$projectKey/* 또는 검색 파라미터 ?projectKey=)
② localStorage 마지막 저장값
③ 접근 가능한 첫 프로젝트        (GET /api/v1/projects 응답 순서)
④ 없으면 빈 상태                 (프로젝트 0개 — /projects 로 안내)
```

**근거.**
- **URL 이 최상위여야 하는 이유.** 링크 공유·뒤로가기·새로고침이 같은 화면을 재현해야 한다. 저장값이 URL을 이기면 공유한 링크가 받는 사람에게 다른 프로젝트를 연다.
- **URL 이 프로젝트를 담을 때 저장값을 갱신한다.** 보드(`/projects/ATLAS/board`)를 보다가 사이드바 "이슈"를 누르면 ATLAS 이슈가 나와야 한다 — 이게 "활성"의 의미다.
- ③은 Maxi 확정(2026-07-28). 첫 방문에 빈 화면 대신 곧장 이슈를 보여준다. 프로젝트가 하나뿐인 대다수 사용자에게 정확하다.

### D4. 영속 = localStorage. 서버 `user_preferences` 는 기각

키 `bts.active-project`. `hooks/use-sidebar-collapsed.ts:15-45`(zustand + fail-safe 3중 폴백)를 템플릿으로 복제한다.

**근거.**
- 백엔드 변경 0 — Maxi 결정 2("백엔드 = B1+B2만")와 일치.
- 활성 프로젝트는 **UI 선호값**이지 사용자 데이터가 아니다. `use-sidebar-collapsed`와 같은 성격이며 **절대 규칙 §1.18(토큰 localStorage 저장 금지)과 무관**하다 — 토큰도 PII도 저장하지 않는다.
- 값이 stale해도(프로젝트 삭제·권한 회수) 해소 순서 ③이 자동 복구한다. 서버 영속이면 stale 정리 로직이 별도로 필요하다.

**기각 — 서버 `user_preferences` 확장.**
`user_preferences`(V031 + V032)는 `theme`·`locale`·`date_format`·`start_page`를 담는 실재 테이블이고 PATCH API도 있어 **도메인상으로는 자연스러운 자리**다. 기기 간 동기화라는 실익도 있다. 그러나 Flyway 마이그레이션 + identity-access DTO/service/controller 변경이 필요해 결정 2를 넘는다. **후속 FR 후보로 남긴다** — 채택 시 해소 순서 ②의 소스만 교체되고 ①③④는 불변이라 폭발 반경이 작다.

### D5. `/issues` 는 v1 에서 프로젝트 스코프를 유지한다

지라의 이슈 내비게이터는 프로젝트를 가로지르지만 BTS는 v1에서 못 한다. `IssueApplicationService.kt:1021`이 `assertPermission(actor, BROWSE, IssueScope.Project(projectKey))`로 프로젝트 스코프를 강제하고, `UserCalendarLookupAdapter.kt:25-38`이 *"issue-tracking의 모든 visibility 술어는 `PROJECTS.KEY.eq(projectKey)` 단일 프로젝트 축으로 하드코딩되어 재사용 불가"* 를 명시한다.

프로젝트별 보안등급 집합을 **격리**해 OR 조립해야 하며 합집합으로 두면 fail-open 사고다. 이 작업(로드맵 B3)은 **보안 위험이 로드맵에서 유일**하고 비용이 가장 커 Maxi가 범위에서 제외했다. 따라서 v1의 답은 "프로젝트 무관 조회"가 아니라 **"프로젝트 전환을 쉽게"**(F12 프로젝트 스위처)다.

## 결과

**좋아지는 것.** ATLAS 외 프로젝트 사용자가 이슈 목록·검색을 쓸 수 있게 된다. `?projectKey=` 링크 공유가 가능해진다. FR-PF-02 `my_issues` 시작 페이지가 ATLAS에 담당 이슈가 없는 사용자에게 빈 화면을 주던 파생 결함이 해소된다.

**감수하는 것.** 활성 프로젝트가 기기 간에 안 넘어간다(D4 기각 사유 참조). `/issues`는 여전히 한 프로젝트만 본다(D5).

**남는 것.** cross-project 이슈 조회(B3) · 서버 영속 활성 프로젝트 · 프로젝트 스위처(F12, 이 로드맵 내 후속).

## 관련

- 로드맵 정본 — `~/.claude/plans/ui-ux-sorted-kay.md`
- [FR-UX-06 Jira 재설계 ADR](2026-07-17-fr-ux-06-jira-redesign.md) (§D5 논리≠물리)
- [FR-PR-01 사용자 프로필 배치](2026-07-05-fr-pr-01-user-profile-placement.md) (§D1 동일 패턴)
- [FR-UX-05 키맵](2026-07-05-fr-ux-05-keymap.md) (§4.3 "컨텍스트 단축키는 후속 FR" — D1 근거)
