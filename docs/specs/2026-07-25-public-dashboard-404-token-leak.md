# 공개 대시보드 오류 응답 토큰 노출 봉합 — 스펙

> 날짜: 2026-07-25
> slug: public-dashboard-404-token-leak
> type: auth · BC: notification (단일)
> 관련 ADR: `docs/decisions/2026-07-02-fr-db-03-dashboard-share.md` (D1 — 원문 토큰 1회 노출 원칙)
> FR: **신설 0 · 카운트 129 불변** (기존 FR-DB-03 의 결함 봉합)

## 0. 한 줄

`/api/v1/public/dashboards/{token}` 의 **오류 응답 본문에 원문 공유 토큰이 실려 나가는 것**을 막고,
같은 결함이 이 경로에 다시 생기지 못하게 구조적으로 봉인한다.

## 1. 확정된 사실 (실측)

MockMvc 슬라이스로 실제 응답 본문을 찍어 확인했다. **추정 아님.**

```json
// M1 — 404 (컨트롤러-로컬 핸들러)
{"type":"…/dashboard-not-found","title":"Dashboard Not Found","status":404,
 "instance":"/api/v1/public/dashboards/share_SUPERSECRETTOKEN_0123456789abcdef", …}

// M2 — 500 (DashboardExceptionHandler advice catch-all)
{"type":"…/dashboard-internal-error","title":"Dashboard Internal Server Error","status":500,
 "instance":"/api/v1/public/dashboards/share_SUPERSECRETTOKEN_0123456789abcdef", …}
```

**기전.** `RequestResponseBodyMethodProcessor` 는 반환된 `ProblemDetail` 의 `instance` 가 `null` 이면
요청 URI 로 자동 채운다. 이 경로는 **경로 세그먼트에 원문 토큰**이 있으므로 그대로 본문에 실린다.

**검증 비용 정정.** 체크포인트의 "백엔드 기동 필요(dev postgres 5433)" 전제는 과잉이었다.
`instance` 자동 채움은 Spring MVC 반환값 처리 단계라 **MockMvc 슬라이스가 같은 코드 경로를 탄다.**

**★기존 테스트가 못 보는 이유 2가지.**
1. `PublicDashboardControllerTest` 는 `$.errorCode`·`$.detail` 만 단언하고 `$.instance` 를 검사하지 않는다.
2. 같은 테스트가 `DashboardExceptionHandler` 를 **일부러 등록하지 않는다**(테스트 KDoc 명시).
   그런데 프로덕션에서는 이 advice 가 `basePackages = ["com.bts.notification.dashboard.web"]` 이고
   `PublicDashboardController` 가 **바로 그 패키지에 있어** 적용된다 — advice 경로 유출이 구조적 사각이었다.

## 2. 왜 문제인가 (심각도 — 정직한 계량)

**과장하지 않는다.** 요청을 보낸 사람은 그 토큰을 이미 알고 있다. 이 응답이 새 정보를 주지는 않는다.

실질 위험은 **평문 토큰의 2차 적재**다.
- 응답 본문을 수집하는 **에러 트래커/APM**(3자 시스템)에 평문 토큰이 저장된다.
- **프록시/CDN 캐시·응답 로그**에 남는다.
- 사용자가 오류 본문을 그대로 **버그 리포트/지원 티켓에 붙여넣으면** 제3자에게 노출된다.

ADR `2026-07-02-fr-db-03-dashboard-share` **D1** 은 *"원문은 발급 응답에서 1회만 노출, DB엔 SHA-256 해시만
(유출 시 원문 복원 불가)"* 를 불변식으로 세웠다. 오류 본문 반사는 이 불변식을 깬다.
`DEVELOPMENT.md §1.1-1·§1.1-2`(평문 비밀값 저장·로깅 금지) 위반이기도 하다.

**등급 판정.** 인증 우회·권한 상승이 아니므로 P0 가 아니다. **P1 — 정보 노출(위생)** 로 본다.
(체크포인트가 "P0 평문노출" 로 적어 둔 것을 증거 기준으로 하향 조정한다.)

## 3. 이미 존재하는 정본 패턴

같은 결함 클래스를 **automation BC 가 먼저 발견해 봉합**해 두었다. 새로 설계할 필요가 없다.

| 컨트롤러 | 처방 |
|---|---|
| `AutomationWebhookController:212` | `pd.instance = URI.create("/api/v1/automation/webhooks")` |
| `GitWebhookController:416` | `pd.instance = URI.create("/api/v1/webhooks/git")` |

둘 다 **토큰 세그먼트를 뺀 고정 경로**를 넣는다. KDoc(AutomationWebhookController L187-195)이 기전·근거를
이미 문서화했다. 본 작업은 그 처방의 **미적용 구멍을 메우는 것**이다.

## 4. 사용자 시나리오 (Given-When-Then)

**S1. 만료된 공유 링크를 연다 (404)**
- Given 발급 후 만료된 공유 토큰 `T` 가 있다
- When 익명 사용자가 `GET /api/v1/public/dashboards/T` 를 호출한다
- Then 404 가 반환되고, **응답 본문 어디에도 `T` 가 없다**
- And `detail`·`errorCode` 는 기존과 동일하다(무효/만료/부모삭제 수렴 불변 — 열거 차단 유지)

**S2. 서버 내부 오류가 난다 (500)**
- Given 유효한 토큰 `T` 로 조회 중 서비스가 분류되지 않은 예외를 던진다
- When 익명 사용자가 `GET /api/v1/public/dashboards/T` 를 호출한다
- Then 500 이 반환되고, **응답 본문 어디에도 `T` 가 없다**

**S3. 정상 조회는 그대로다 (200)**
- Given 유효한 토큰 `T`
- When 조회한다
- Then 200 + 기존과 **완전히 동일한** 스냅샷 본문. 내부 식별자 부재 회귀가드도 그대로 통과

**S4. 인증 대시보드 경로의 진단 정보는 잃지 않는다**
- Given `/api/v1/dashboards/{id}` 등 **비밀값이 경로에 없는** 인증 경로에서 오류가 난다
- When 오류 응답이 나간다
- Then `instance` 는 **기존대로 요청 URI** 를 유지한다(진단 가치 보존, 회귀 0)

## 5. 기능 요구사항 (FR)

> 신규 FR ID 신설 없음. 기존 FR-DB-03 의 결함 봉합이므로 **FR 카운트 129 불변**이고
> `verify-master-plan.sh` 대상 카운트 파일은 **한 줄도 건드리지 않는다.**

- **R1.** `/api/v1/public/dashboards/{token}` 이 낼 수 있는 **모든** 오류 응답 본문에 원문 토큰이 없어야 한다.
  404 단건이 아니라 **경로 전체**가 대상이다.
- **R2.** 오류 응답의 `instance` 는 **토큰 세그먼트를 뺀 고정 경로** `/api/v1/public/dashboards` 로 설정한다
  (automation 정본 패턴 동형).
- **R3.** 200 정상 응답·`detail`·`errorCode`·상태코드는 **한 글자도 바뀌지 않는다**(순수 봉합, 기능 변경 0).
- **R4.** 인증 대시보드 경로(`/api/v1/dashboards/**`)의 오류 응답 `instance` 는 **기존 동작을 유지**한다.
  공개 경로만 고정값으로 덮는다.
- **R5. (재발 방지)** 공개 경로에 **새 오류 응답 통로가 추가되면 테스트가 실패**해야 한다.
  "핸들러를 하나 더 만들었는데 아무도 모르는" 상태를 구조적으로 불가능하게 만든다.

  **판별식 (G1 해소 — 개수가 아니라 파생 열거).**
  `PublicDashboardController` 의 `@ExceptionHandler` 메서드를 **리플렉션으로 전수 열거**하고,
  각 메서드가 선언한 예외 타입을 실제로 발생시켜 HTTP 응답을 받은 뒤 **본문·헤더에 토큰 부재**를 단언한다.
  기대 목록에 없는 핸들러가 발견되면 **미분류로 판정해 실패**시킨다(#309 의 "미분류 라우트 = 실패" 동형).
  → 새 핸들러를 추가하면 기대 목록도 함께 갱신해야만 초록이 된다.

  ⚠️ **`it.each(파생목록)` 무음통과 방지** — 파생 목록이 비면 테스트가 0건 실행되고도 초록이 된다
  (memory `guard-handler-matrix-blindfold` 2차 재발). **열거된 핸들러 개수의 하한을 별도 단언**한다.

## 6. 설계 옵션 — 게이트 1 판정 대상

문제의 핵심은 **`DashboardExceptionHandler` advice 가 공개 경로와 인증 경로를 함께 커버**한다는 점이다.
automation 은 컨트롤러 전용 헬퍼라 고정값을 박으면 끝이었지만, 여기서는 그대로 하면 R4 를 깬다.

| 옵션 | 내용 | 평가 |
|---|---|---|
| **(a)** advice `problem()` 이 요청 URI 에 비밀 세그먼트가 있으면 덮는다 | 경로 판별 로직 필요 | ❌ 판별식이 복잡·오탐 위험. 새 비밀경로마다 목록 갱신 필요 |
| **(b) 권장** 공개 컨트롤러에 **로컬 핸들러로 전 오류를 흡수** + 로컬 핸들러 전부 `instance` 고정 | 컨트롤러-로컬이 advice 보다 우선 적용 | ✅ automation 동형·BC 로컬·R4 자동 충족·R1 을 경로 단위로 커버 |
| **(c)** 공개 컨트롤러를 advice 스코프 밖 패키지로 이동 | `basePackages` 재편 | ❌ 패키지 이동은 폭발 반경이 크고 import 다수 변경 |

**권장 = (b).** 근거.
- `GitWebhookController` L150-158 이 **동일한 이유로 이미 같은 선택**을 했다
  (*"핸들러 메서드 안으로 들여와 컨트롤러 로컬 핸들러가 직접 응답하게 한다"*).
- 공개 경로는 **정화(sanitize)가 기본값**이어야 하는 곳이다. advice 의 일반 응답이 흘러드는 구조 자체가 위험원.

⚠️ **(b) 채택 시 주의 2가지.**
1. 로컬 catch-all 은 `ResponseStatusException` 을 삼킬 수 있다
   (memory `catch-all-exceptionhandler-swallows-responsestatusexception`) → advice 와 동일한 rethrow 가드 필요.
2. 로컬 catch-all 이 advice 의 500 매핑을 대체하므로 **errorCode 가 기존과 동일한지** 대조 필수
   (기존 `NOTIF_DASHBOARD_INTERNAL_ERROR` 유지 — drift 금지).

**(b) 는 신규 설계 결정이므로 ADR 을 남긴다** (Maxi D3 답변에 명시).

## 7. 비기능 요구사항 (NFR)

- **N1.** 성능 영향 0 — `URI.create` 상수 1회, 오류 경로에서만 발생.
- **N2.** 기존 테스트 전량 통과. `notification` 모듈 테스트 수가 줄어들지 않는다(개수 대조로 확인).
- **N3.** ktlint·detekt 통과. detekt baseline 신규 등재 0 을 목표로 한다.
- **N4.** 마이그레이션 0 · 신규 의존성 0 · 프론트 변경 0 · cross-BC 변경 0.

## 8. API 인터페이스 (REST)

**엔드포인트 변경 없음.** 응답 **본문 필드 1개(`instance`)의 값만** 바뀐다.

| | 변경 전 | 변경 후 |
|---|---|---|
| `instance` | `/api/v1/public/dashboards/<원문토큰>` | `/api/v1/public/dashboards` |
| 그 외 전 필드 | — | **불변** |

**프론트 소비 영향 — 0 (실증 완료, G3).** `apps/web/src` 전수 grep 결과 프로덕션 코드에서 `instance`
필드를 읽는 곳이 **없다**(히트는 전부 테스트 픽스처이거나 `instanceof`·`instanceA` 같은 무관 식별자).
따라서 값 변경이 화면에 미치는 영향은 없다.

## 9. 데이터 모델 변경

**없음.** 마이그레이션 0.

## 10. 엣지 케이스

- **E1.** 초장문·이상문자 토큰 → 여전히 404 수렴, 본문에 토큰 없음(기존 MALFORMED 테스트 확장).
- **E2.** 무효 토큰 / 만료 토큰 / 부모 삭제 → **셋의 응답이 서로 완전히 동일**해야 한다(열거 차단 회귀가드 유지).
- **E3.** 비-GET 메서드(POST 등) → SecurityConfig 가 GET 고정 permitAll 이라 인증 체인으로 떨어진다.
  **이 응답은 identity-access BC 소관**이라 본 PR 범위 밖. 다만 **토큰 노출 여부는 확인해 결과를 등재**한다.
- **E4.** 토큰이 URL 인코딩된 문자를 포함 → 디코딩된 값이 본문에 실리지 않는지 확인.
- **E5.** 200 정상 응답에는 `instance` 자체가 없다(ProblemDetail 아님) → 회귀 없음.
- **E6 (G6).** **응답 헤더**도 검사 대상이다. 본문만 보면 헤더로 새는 경로를 놓친다.
  토큰 부재 단언을 본문 + 전 헤더값에 적용한다.
- **E7 (G7).** M2(500) 경로는 `log.error("NOTIF_DASHBOARD_500 internal_error", ex)` 로 **예외 스택을 찍는다.**
  `PublicDashboardNotFoundException` 은 고정 메시지라 토큰을 품지 않음을 확인했으나(실증),
  `shareTokenMinter.hash()`·`layoutSanitizer.sanitize()` 등 하위 컴포넌트가 **토큰을 예외 메시지에 넣으면
  로그에 평문이 남는다.** 해당 경로가 토큰을 예외에 싣지 않는지 확인하고 결과를 등재한다.

### 도달 가능한 오류 통로 — 전수 확정 (G4)

`DashboardService.getPublicByToken` 본문을 읽어 확정했다. 던지는 예외는 `PublicDashboardNotFoundException`
**하나뿐**이고(미존재·만료·부모삭제 3분기가 모두 이 하나로 수렴), 그 외 하위 컴포넌트의 예기치 못한 예외는
전부 advice catch-all 로 간다.

| 통로 | 핸들러 | 도달 가능? | 근거 |
|---|---|---|---|
| M1. `PublicDashboardNotFoundException` → 404 | 컨트롤러-로컬 | ✅ | 서비스가 직접 던짐(실측 확인) |
| M2. 분류되지 않은 예외 → 500 | advice catch-all | ✅ | 하위 컴포넌트 예외가 모두 여기로(실측 확인) |
| `MethodArgumentTypeMismatchException` → 400 | advice | ❌ | `token` 이 `String` 이라 타입 변환 실패가 없다 |
| `HttpMessageNotReadableException` → 400 | advice | ❌ | GET 이라 요청 본문이 없다 |
| `DashboardDomainException` → 400 | advice | ❌ | `getPublicByToken` 경로에서 던지지 않는다 |
| `MethodArgumentNotValidException` → 400 | advice | ❌ | `@Valid` 대상 파라미터가 없다 |

→ **봉합 대상 = M1 + M2 두 통로.** 나머지는 도달 불가라 변경 불요(단, R5 판별식이 미래 추가를 감시한다).

## 11. 제약 조건

- **C1. BC 격리** — notification BC 단일. `IcalFeedController`(identity-access)는 **본 PR 범위 밖**,
  후속 PR 로 등재(Maxi D2=A 확정).
- **C2. FR 카운트 불변** — 129. `verify-master-plan.sh` 대상 파일 무변경.
- **C3. 기능 변경 0** — 상태코드·errorCode·detail·200 본문 전부 불변.
- **C4. TDD 강제** — `test:` 커밋이 `feat:` 커밋보다 먼저.
- **C5.** 조사용 프로브 파일 `PublicDashboard404BodyProbeTest.kt` 는 **정식 회귀 테스트로 전환하거나 삭제**한다.
  임시 파일을 PR 에 남기지 않는다.

## 12. 측정 가능한 완료 기준

1. ✅ 공개 경로의 **오류 응답 통로를 전수 열거**하고, 각각에 대해 **본문 + 전 헤더**에 토큰이 없음을
   HTTP 레벨 테스트로 단언. (단순 개수가 아니라 **열거 + 미분류 실패** — memory `guard-handler-matrix-blindfold`)
   **토큰 검사는 `contentAsString` 이 아니라 raw 바이트(`contentAsByteArray`)로 한다** (G5) —
   문자 인코딩 설정에 좌우되지 않고 **실제로 회선에 나가는 것**을 재기 위함이다.
2. ✅ **뮤테이션 검증** — `instance` 설정 줄을 지우면 테스트가 **실제로 red** 가 된다(vacuous 아님 증명).
   기준선 EXIT=0 을 먼저 확인한 뒤 수행한다(memory `verify-logic-vs-verify-guard`).
3. ✅ 200 정상 경로 기존 단언 전량 통과 + 내부식별자 부재 회귀가드 유지.
4. ✅ 인증 대시보드 경로 오류 응답의 `instance` 가 기존과 동일함을 테스트로 고정(R4 회귀가드).
5. ✅ `:modules:notification:test` 통과 + **테스트 개수가 이전 대비 감소하지 않음**
   (memory `gradle-batched-task-partial-test-run` — 개수 실측 대조).
6. ✅ ktlintCheck · detekt 통과.
7. ✅ ADR 생성 (설계 옵션 (b) 채택 근거).
8. ✅ E3(비-GET 응답)·`IcalFeedController` 조사 결과를 **후속 항목으로 문서 등재**.
9. ✅ **G2 실증** — 컨트롤러-로컬 핸들러가 advice 보다 우선 적용된다는 것을 테스트로 확정한다.
   (advice 를 함께 등록한 컨텍스트에서 로컬 핸들러 응답이 나오는지 관측. 단정 금지 — 이게 틀리면 설계 (b) 가 무너진다.)
10. ✅ 조사용 프로브 `PublicDashboard404BodyProbeTest.kt` 를 정식 테스트로 전환하거나 삭제(C5). PR 에 임시 파일 0.

## 13. 범위 밖 (명시)

- `IcalFeedController`(identity-access BC) — 별도 PR.
- 나머지 44개 `ProblemDetail` 생산자 — 경로에 비밀값이 없어 무해. 변경하지 않는다.
- 경로 기반 토큰 설계 자체(주소창·리퍼러·접근로그 노출) — ADR D1 이 택한 설계. 본 PR 은 **응답 본문**만 다룬다.
- 전역(repo-wide) 재발 방지 장치 — BC 경계를 넘으므로 별도 논의.

## 워크플로우 편차 (사유 등재)

- **`grill-with-docs` 생략** — Maxi D3=A 승인. 신규 엔티티·용어·결정 0건. #309 선례 동형.
- **`office-hours` 대신 기술 스펙 직접 작성** — 메모리 `bts-spec-office-hours-mismatch`(2026-05-29 결정).
  제품 범위 결정이 아니라 기존 결함 봉합이라 office-hours 산출 형식이 맞지 않는다.
- **`superpowers:brainstorming` 을 분석 체크리스트로만 사용** — 해당 스킬은 백지→설계 대화형이라
  확정된 스펙의 sanity check 와 형식 불일치(자체 spec 신규 작성 + `writing-plans` 체이닝 시도).
  bts-spec 지시("재작성 금지, gap 만 보고")를 우선 적용. 상세는 §Brainstorming Check.
- **에이전트 dispatch 없이 메인이 직접 수행** — 세션 지시. #309·#308 선례 동형.
  ⚠️ **한계 — 구현자가 자기 코드를 리뷰하는 편향이 남는다.** 게이트 2 에서 Maxi 가 이를 알고 판정한다.

## Brainstorming Check

✅ **통과 (1회 iteration, gap 8건 발견 — 3건 즉시 실증 해소 · 4건 스펙 반영 · 1건 별건 등재)**

`superpowers:brainstorming` 은 백지에서 설계를 만드는 대화형 스킬이라 **이미 확정된 스펙의 sanity check
용도와 형식이 맞지 않았다**(자체 spec 을 `docs/superpowers/specs/` 에 새로 쓰고 `writing-plans` 로 넘어가려 한다
— BTS 는 `bts-plan` 이 그 자리). 메모리 `bts-spec-office-hours-mismatch` 와 동일한 미스매치.
→ BTS 지시("brainstorming 은 스펙을 재작성하지 않는다, 발견된 gap 만 보고")를 우선해 **해당 스킬의 분석
체크리스트**(placeholder / 내부모순 / 범위 / 모호성)만 적용했다. **워크플로우 편차로 등재한다.**

| # | 종류 | 발견 | 처리 |
|---|---|---|---|
| G1 | 모호성 | R5 "재발 방지 장치" 의 구체 형태 미명시 | §5 R5 에 **판별식 + 개수 하한 단언** 명시 |
| G2 | 미검증 가정 | "컨트롤러-로컬 catch-all 이 advice 보다 우선" 을 근거 없이 단정 | **RED 에서 실증**하는 태스크로 전환(§12-9) |
| G3 | 미검증 가정 | 프론트 `instance` 소비처 "예상 0" | ✅ **전수 grep 실증 → 0 확정** (§8) |
| G4 | 전수성 | 도달 가능 오류 통로가 M1+M2 뿐인지 미확인 | ✅ **서비스 본문 판독 → 6통로 중 2개만 도달 가능 확정** (§10) |
| G5 | 누락 | 토큰 검사를 문자열로 하면 인코딩 설정에 좌우됨 | **raw 바이트 검사**로 변경 (§12-1) |
| G6 | 누락 | 응답 **헤더**가 검사 대상에서 빠짐 | E6 추가 · §12-1 에 헤더 포함 |
| G7 | 누락 | 500 경로 `log.error(…, ex)` 스택에 토큰이 실릴 여지 | E7 추가 (확인 후 등재) |
| G8 | 관측 | 프로브 출력에서 한글 `detail` 깨짐 관측 | MockMvc 읽기 인코딩 아티팩트로 **추정**(미확정). G5(바이트 검사)로 본 작업에는 무해화. **별건으로 등재** |
