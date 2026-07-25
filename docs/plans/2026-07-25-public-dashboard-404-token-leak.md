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

## 도메인 정리

- **BC**: notification (단일). 대시보드 영역 `com.bts.notification.dashboard`.
- **영향 엔티티**: DashboardShareToken (기존, 변경 없음). 신규 엔티티 0.
- **새 용어**: 0건. 기존 용어 `공유 토큰(Dashboard Share Token)`만 사용.
- **기존 결정 충돌**: 없음. 오히려 **기존 결정을 복원하는 작업**이다.

### 근거가 된 기존 결정

`docs/decisions/2026-07-02-fr-db-03-dashboard-share.md`
- **D1** — 공유 토큰은 불투명(opaque) 랜덤 토큰. **원문은 발급 응답에서 1회만 노출**, DB엔 SHA-256 해시만
  (유출 시 원문 복원 불가). 즉 *"원문 토큰이 나가는 응답은 발급 응답 하나뿐"* 이 확립된 불변식이다.
- **Consequences** — "BTS 첫 비인증 읽기 경로 → 계정 열거/토큰 probe 방어를 spec에서 명시".

`DEVELOPMENT.md §1.1-1 / §1.1-2` — 평문 비밀값 저장·로깅 금지.

### 실증 결과 (RED 확정, 2026-07-25)

MockMvc 슬라이스 프로브로 404 응답 본문을 실측했다. **postgres 기동 불요** — `instance` 자동 채움은
Spring MVC 반환값 처리 단계라 슬라이스가 같은 경로를 탄다(체크포인트의 "dev postgres 5433 필요" 전제 정정).

```json
{"type":"...","title":"Dashboard Not Found","status":404,"detail":"...",
 "instance":"/api/v1/public/dashboards/share_SUPERSECRETTOKEN_0123456789abcdef",
 "errorCode":"NOTIF_DASHBOARD_NOT_FOUND","timestamp":"..."}
```
→ `TOKEN_IN_BODY=true`. **원문 공유 토큰이 404 본문에 실려 나간다.**

### ★정본 수정 패턴이 이미 레포에 존재한다

같은 결함 클래스가 **automation BC 에서는 이미 발견·봉합**돼 있었다.

| 컨트롤러 | BC | `instance` 명시 | 상태 |
|---|---|---|---|
| `AutomationWebhookController:212` | automation | `URI.create("/api/v1/automation/webhooks")` | ✅ 봉합 |
| `GitWebhookController:416` | automation | `URI.create("/api/v1/webhooks/git")` | ✅ 봉합 |
| `PublicDashboardController` | notification | **없음** | ❌ **유출 확정** |
| `IcalFeedController` | identity-access | (ProblemDetail 미사용, `ResponseStatusException`) | ⚠️ **미실증** |

`AutomationWebhookController` KDoc(L187-195)이 기전·근거를 이미 문서화해 놓았다 — 인용.
> `instance` 를 반드시 명시한다 — 비우면 Spring 이 원문 토큰을 응답에 싣는다.
> `RequestResponseBodyMethodProcessor` 는 `instance` 가 null 이면 요청 URI 로 자동 채운다. (…)
> 보낸 사람이야 이미 아는 값이지만, 그 본문이 **응답 로그·프록시 캐시·에러 트래커에 적재되는 순간
> 그것이 평문 토큰 저장/로깅**이다(DEVELOPMENT.md §1.1-1·§1.1-2).

→ 본 작업은 **새 설계가 아니라 확립된 패턴의 미적용 구멍을 메우는 것**이다. 수정 형태는 이미 정해져 있다.

### 전수 열거 — `instance` 를 명시 설정하는 곳

main 소스 전수 grep 결과 **정확히 2곳**(위 automation 2건)뿐이고, 나머지 **44개 ProblemDetail 생산자는
전부 Spring 자동 채움에 맡긴다**. 대부분은 경로에 비밀값이 없어 무해하다(이슈키·프로젝트키는 비밀이 아니다).
**경로에 비밀값이 있는데 `instance` 를 안 채우는 곳 = 2곳**(PublicDashboard 확정 · IcalFeed 미실증).

### 구조적 재발 위험 (설계 결정 필요)

automation 2건은 2026-07 중순에 고쳤는데 `PublicDashboardController`(2026-07-02 도입)는 **3주 넘게
같은 결함으로 남아 있었다**. 개별 봉합만으로는 재발한다는 증거다
(memory `guard-handler-matrix-blindfold` — "개수 말고 행렬 전수열거 + 판별자").
신규 비밀-경로 엔드포인트가 `instance` 를 비우면 **자동으로 실패하는 장치**가 필요한지 게이트 1에서 판정.

### grill-with-docs

**미실행.** 신규 엔티티·용어·도메인 결정이 0건이고, 기존 ADR(D1)이 이미 불변식을 확정해 놓은
"기존 결정 복원" 작업이라 도메인 문답의 산출물이 없다. #309 선례(D4 Maxi 승인) 동형.
→ **Maxi 확인 대상** (아래 게이트 질의에 포함).

- **관련 ADR**: `docs/decisions/2026-07-02-fr-db-03-dashboard-share.md` (신규 ADR 생성 없음 — 신규 결정 0.
  단, 재발 방지 장치를 도입하기로 하면 그건 신규 결정이라 ADR 필요)

## 스펙

전체 스펙. [docs/specs/2026-07-25-public-dashboard-404-token-leak.md](../specs/2026-07-25-public-dashboard-404-token-leak.md)

핵심 3줄 요약.
- `/api/v1/public/dashboards/{token}` 의 **오류 응답 본문에 원문 공유 토큰이 실려 나간다** —
  404(컨트롤러-로컬)와 500(advice catch-all) **두 통로 모두 실측 확인**.
- 수정은 `instance` 를 **토큰 세그먼트를 뺀 고정 경로**로 설정하는 것. automation BC 2건에 **이미 있는 정본 패턴**.
- 404 만 막으면 500 이 그대로 새므로 **경로 단위로 흡수**한다(설계 옵션 (b), 게이트 1 판정 대상 + ADR).

범위. notification BC 단일 · FR 129 불변 · 마이그레이션 0 · 프론트 0 · 기능 변경 0.
심각도. **P1 정보노출(위생)** — 체크포인트의 "P0" 표기를 증거 기준으로 하향(요청자는 이미 토큰을 알고 있고,
실질 위험은 에러 트래커·프록시 캐시·버그리포트로의 **평문 2차 적재**다).

## Brainstorming Check

✅ 통과 (1회 iteration, gap 8건 — 3건 즉시 실증 해소 · 4건 스펙 반영 · 1건 별건 등재). 상세는 스펙 §Brainstorming Check.

즉시 해소한 3건.
- **G3** 프론트 `instance` 소비처 → 전수 grep 으로 **0 확정**(추정이었던 것을 사실로 승격).
- **G4** 도달 가능 오류 통로 → 서비스 본문 판독으로 **6통로 중 M1·M2 2개만 도달 가능** 확정.
- **G7 일부** `PublicDashboardNotFoundException` 은 고정 메시지라 **토큰을 품지 않음** 확인(로그 안전).

스펙에 반영한 4건.
- **G1** 재발방지 장치의 판별식 명시 + `it.each` 무음통과 방지용 **개수 하한 단언**.
- **G2** "컨트롤러-로컬이 advice 보다 우선" 단정을 **RED 실증 태스크로 전환**(틀리면 설계가 무너지는 지점).
- **G5** 토큰 검사를 문자열이 아니라 **raw 바이트**로 — 인코딩 설정에 좌우되지 않게.
- **G6** 검사 대상에 **응답 헤더** 추가.

별건 등재 1건.
- **G8** 프로브 출력의 한글 `detail` 깨짐 — MockMvc 읽기 인코딩 아티팩트로 **추정(미확정)**. G5 로 본 작업엔 무해.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
