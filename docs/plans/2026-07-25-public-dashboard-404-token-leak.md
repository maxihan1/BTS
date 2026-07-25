# 공개 대시보드 404 응답 토큰 노출 검증 및 봉합

> slug: public-dashboard-404-token-leak
> type: auth (classify 원출력 `qa` → 상향 조정. 사유는 §Brief)
> agent: security-engineer (BC = notification, 대상 = permitAll 익명 경로)
> 생성: 2026-07-25

## Brief

**사용자 원문.**
> PublicDashboardController의 404 응답 본문에 공유 대시보드 토큰이 노출되는지 실제 응답으로 검증하고,
> 갭이 확인되면 봉합 + 회귀 테스트 추가 (notification-dashboard BC)

**출처.** 2026-07-25 세션 체크포인트(`#309` 머지 직후)의 보안 트랙 잔여 2건 중 PR B.
원 지목은 FR-UX-06 PR22(#308) 시각 확인 과정에서 파생된 `PRE_EXISTING` 후속 목록.

**classify 정정.** `classify-task.ts` 원출력은 `type=qa · agent=qa-engineer`.
`"검증"`·`"회귀 테스트"` 키워드가 끌어당긴 오분류로 판단해 `type=auth`로 상향했다.
근거 2가지.
1. `qa-engineer`는 정의상 **`src/` 구현 코드 수정 금지**(`.claude/agents/qa-engineer.md`)인데
   본 작업은 "갭이 확인되면 봉합"을 포함해 프로덕션 Kotlin 수정 가능성이 있다.
2. 대상이 **인증 없이 열린(permitAll) 익명 엔드포인트의 정보 노출**이라 보안 영역이다.
   선례 — #309도 같은 성격(가드 누락)으로 `auth/` 브랜치를 썼다.
상향은 검토 강도를 **높이는** 방향이라 단독 판단으로 진행했다(Maxi 보고 완료).

**사전 조사(착수 전 read-only, 미확정 가설 포함).**
- `PublicDashboardController.kt:80-92` — 404 핸들러가 `pd.instance`를 **설정하지 않는다**
  (`type`·`title`·`detail`·`errorCode`·`timestamp`만 설정).
- 경로 = `GET /api/v1/public/dashboards/{token}` — **원문 토큰이 URI 경로 세그먼트에 있다.**
- Spring Boot **3.3.5** (Spring Framework 6.1.x).
- 기존 슬라이스 테스트 `PublicDashboardControllerTest.kt`는 `$.errorCode`·`$.detail`만 단언하고
  **`$.instance`를 검사하지 않는다** → 새더라도 현 테스트는 초록이다.
- ⚠️ **가설 (미검증)** — Spring MVC가 `ProblemDetail.instance == null`일 때 요청 URI로 자동 채우면
  404 본문에 원문 토큰이 실린다. **소스 리딩으로 단정 금지. red 테스트로 실증한다.**
- ⚠️ **체크포인트 전제 정정 후보** — 원 메모는 "백엔드 기동 필요(dev postgres 5433)"라 했으나,
  `instance` 자동 채움은 Spring MVC 메시지 컨버터 반환값 처리 단계라 **MockMvc 슬라이스가 같은 경로를
  탄다**는 것이 현재 판단. 이 역시 가설이므로 슬라이스 결과와 상위 레벨(부팅) 결과의 일치를 별도 확인한다.

**성공 기준.**
1. 404 응답 본문의 실제 형태를 **증거로** 확정한다(추정 금지).
2. 토큰이 실린다면 봉합하고, 실리지 않는다면 그 상태를 **회귀 테스트로 못박는다**.
   두 경우 모두 산출물이 있다 — "문제 없었음"으로 끝내지 않는다.
3. 판정 오라클(슬라이스 vs 실기동)이 서로 어긋나지 않음을 확인한다.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
