# ADR: 공개 대시보드 오류 응답의 `instance` 정화 — 컨트롤러-로컬 흡수

> 날짜: 2026-07-25
> 상태: 채택 (Accepted)
> 관련 FR: FR-DB-03 (notification BC) — **FR 신설 0 · 카운트 129 불변**
> 선행 ADR: `2026-07-02-fr-db-03-dashboard-share.md` (D1 — 직교 토큰, 원문 1회 노출)
> Plan: `docs/plans/2026-07-25-public-dashboard-404-token-leak.md`
> Spec: `docs/specs/2026-07-25-public-dashboard-404-token-leak.md`
> PR: #310

## 맥락 (Context)

`GET /api/v1/public/dashboards/{token}` 은 BTS 의 비인증(permitAll) 데이터 경로이고,
**원문 공유 토큰이 URI 경로 세그먼트에 들어 있다.**

Spring MVC 의 `RequestResponseBodyMethodProcessor` 는 반환된 `ProblemDetail` 의 `instance` 가 `null` 이면
**요청 URI 로 자동 채운다.** 이 컨트롤러의 오류 핸들러는 `instance` 를 설정하지 않았다.

### 실측 (추정 아님)

MockMvc 슬라이스로 실제 응답 본문을 찍어 **두 통로 모두**에서 유출을 확인했다.

| 통로 | 핸들러 | 상태 | 결과 |
|---|---|---|---|
| M1 | `PublicDashboardController` 컨트롤러-로컬 | 404 | `"instance":"…/share_SUPERSECRETTOKEN_…"` |
| M2 | `DashboardExceptionHandler` advice catch-all | 500 | 동일 |

M2 가 존재하는 이유 — advice 가 `@RestControllerAdvice(basePackages = ["com.bts.notification.dashboard.web"])`
이고 `PublicDashboardController` 가 **바로 그 패키지에 있다.** 기존 슬라이스 테스트는 advice 를 일부러
등록하지 않아(그 파일의 목적이 다름) 이 통로를 **구조적으로 보지 못했다.**

### 왜 문제인가 (심각도 — P1, 과장하지 않음)

요청자는 이미 그 토큰을 안다. 이 응답이 새 정보를 주지는 않는다. 실질 위험은 **평문 토큰의 2차 적재**다 —
응답 본문을 수집하는 에러 트래커/APM, 프록시·CDN 캐시, 사용자가 오류 본문을 버그리포트에 붙여넣는 경우.
그 순간 그것이 **평문 토큰 저장/로깅**(`DEVELOPMENT.md §1.1-1·§1.1-2`)이며,
선행 ADR **D1**("원문은 발급 응답에서 1회만 노출, DB엔 SHA-256 해시만")의 불변식을 깬다.

인증 우회·권한 상승이 아니므로 **P0 가 아니다. P1 — 정보 노출(위생)** 로 판정한다.

### 이미 존재하던 정본

같은 결함 클래스를 automation BC 가 먼저 발견해 봉합해 두었다 —
`AutomationWebhookController.INSTANCE_PATH` · `GitWebhookController.INSTANCE_PATH`.
둘 다 **토큰 세그먼트를 뺀 고정 경로**를 `problem()` 헬퍼 안에서 설정한다.
`PublicDashboardController`(2026-07-02 도입)만 그 처방을 받지 못한 채 **3주 넘게** 남아 있었다.
이 사실 자체가 "개별 봉합은 재발한다" 는 증거다.

## 결정 (Decision)

### D1. 공개 경로의 오류를 **컨트롤러-로컬로 흡수**한다

Spring 은 컨트롤러 클래스의 `@ExceptionHandler` 를 **먼저** 찾고, 없을 때만 `@ControllerAdvice` 로 간다.
이 성질을 이용해 `PublicDashboardController` 에 `@ExceptionHandler(Exception::class)` catch-all 을 두어
**공개 경로의 모든 오류가 컨트롤러 안에서 끝나게** 한다.

기각한 대안.
- **(a) advice 의 `problem()` 이 요청 URI 에 비밀 세그먼트가 있으면 덮는다** — 경로 판별식이 필요하고
  오탐 위험이 있으며, 새 비밀-경로가 생길 때마다 목록을 갱신해야 한다. 판별 책임이 엉뚱한 곳에 놓인다.
- **(c) 공개 컨트롤러를 advice 스코프 밖 패키지로 이동** — 폭발 반경이 크고 import 다수가 바뀐다.
  얻는 것에 비해 diff 가 과하다.

**근거.** 공개 경로는 **정화가 기본값**이어야 하는 곳이다. advice 의 일반 응답이 흘러드는 구조 자체가
위험원이다. `GitWebhookController` 가 동일한 이유로 이미 같은 선택을 했다(핸들러를 컨트롤러 안으로 들여옴).
인증 대시보드 경로는 계속 advice 를 쓰므로 진단용 `instance`(요청 URI)를 잃지 않는다.

### D2. `instance` 설정은 **`problem()` 헬퍼 단일 지점**에 둔다

핸들러마다 `instance` 를 기억해서 넣는 구조는 언젠가 빠뜨린다 — 실제로 이 컨트롤러가 3주간 그 상태였다.
모든 오류 응답이 `problem()` 한 함수를 지나게 해서 **빠뜨리는 것이 구조적으로 불가능**하게 만든다.
automation 두 컨트롤러와 동형이라 세 개의 비밀-경로 컨트롤러가 같은 모양이 된다.

**이것이 이 PR 의 실제 값어치다.** 수정 자체는 `instance` 한 줄이지만,
**재발 방지가 테스트가 아니라 구조로 올라갔다.**

### D3. `ResponseStatusException` 의 상태를 **보존하지 않는다** (전부 500 수렴)

리뷰 전 초안은 `HttpStatus.valueOf(ex.statusCode.value())` 로 상태를 보존하려 했다. 기각한다.

- `HttpStatus.valueOf` 는 **비표준 코드에서 예외를 던진다.** 그 호출이 오류 핸들러 **안**에 있으므로
  핸들러 자체가 실패하고, Spring 기본 오류 처리(`/error`)로 넘어가 응답 `path` 에 **다시 원문 토큰이
  실린다** — 막으려던 것을 되살리는 경로다.
- 상태만 보존하고 `title`·`errorCode` 는 "Internal Server Error" 로 고정되어 **응답이 비정합**해진다.
- 이 경로의 `DashboardService.getPublicByToken` 은 `ResponseStatusException` 을 던지지 않는다(도달 불가).
  실익 없는 분기를 위해 구멍을 만들 이유가 없다.

기존 메모리 `catch-all-exceptionhandler-swallows-responsestatusexception` 이 경계한 실패 모드는
**"401 이 500 으로 변질"** 같은 상태 변질이다. 여기서는 상태를 갖는 예외가 애초에 도달하지 않으므로
그 실패 모드가 성립하지 않는다. **다른 상황에 같은 규칙을 기계적으로 적용하지 않는다.**

### D4. 재발 방지 — `@ExceptionHandler` **파생 열거 + 미분류 실패**

`PublicDashboardErrorTokenLeakTest.SEAL` 이 리플렉션으로 컨트롤러의 `@ExceptionHandler` 를 **전수 열거**하고,
기대 표본 맵에 없는 핸들러가 있으면 **실패**시킨다. 새 핸들러를 추가하면 표본을 등재해야만 초록이 된다.

개수를 세지 않고 **열거한다** — `guard-handler-matrix-blindfold` 의 교훈.
`it.each(파생목록)` 이 목록이 비면 0회 실행되고도 초록이 되는 2차 재발을 막기 위해
**열거 개수의 하한**(`hasSizeGreaterThanOrEqualTo(2)`)을 별도로 단언한다.

## 검증 (Verification)

**뮤테이션 4종 + 봉인 위반 주입 1종.** 전부 커밋된 기준선 위에서 수행하고 손으로 원복했다.

| # | 뮤테이션 | 결과 | 판정 |
|---|---|---|---|
| A | `problem()` 의 `instance` 줄 삭제 | 9건 중 **6 red** (M1·M2·E4·R2·G2·SEAL) | 단일 지점이라 전 통로가 함께 무너진다 |
| B | `handleUnclassified` 전체 삭제 (advice 로 되돌림) | **4 red** (M2·R2·G2·SEAL) | 500 경로 가드만 정확히 반응 |
| C | `handleNotFound` 를 헬퍼 미경유 직접 조립으로 되돌림 | **4 red** (M1·E4·R2·SEAL) | 404 경로 가드만 정확히 반응 |
| D | `INSTANCE_PATH` 값을 `…/x` 로 변경 | **2 red** (R2·G2), M1/M2/E4/SEAL **green** | 값 고정만 반응. 토큰 부재 단언이 경로 문자열에 과잉 결합되지 않았다 |
| 봉인 | 미등재 `@ExceptionHandler` 추가 | 8건 중 **1 red** (SEAL 단독) | 추가 방향의 재발을 실제로 잡는다 |

**D 가 판별력의 핵심 대조군**이다. 전부 red 였다면 테스트가 과잉 결합된 것이고, R2 가 green 이었다면
값 고정이 작동하지 않는 것이다. 둘 다 아니었다.

## 결과 (Consequences)

- **기능 변경 0.** 상태코드·`errorCode`·`detail`·200 본문 전부 불변. 바뀌는 것은 `instance` 필드 값 하나.
- **프론트 영향 0.** `apps/web/src` 전수 grep 결과 `instance` 필드를 읽는 프로덕션 코드가 없다.
- **마이그레이션 0 · 신규 의존성 0 · cross-BC 변경 0 · FR 카운트 129 불변**
  (`verify-master-plan.sh` 대상 파일 무변경).
- **컨트롤러가 advice 에서 사실상 분리된다.** catch-all 이 모든 예외를 잡으므로 advice 의 400 매핑 3종은
  이 컨트롤러에 도달하지 않는다. 원래도 도달 불가였으므로(`token` 이 `String` 이라 타입 변환 실패 없음,
  GET 이라 본문 없음) 동작 차이는 없다.
- **`instance` 를 명시하는 컨트롤러가 2 → 3개**가 되어 패턴이 굳는다.

### 잔여 위험

1. **경로 기반 토큰 설계 자체.** 토큰이 URI 에 있는 한 브라우저 히스토리·리퍼러·웹서버 접근 로그에는
   계속 남는다. 이는 선행 ADR D1 이 택한 설계이며 **본 결정의 범위 밖**이다. 본 결정은 **응답 본문**만 다룬다.
2. **`IcalFeedController`(identity-access BC) 미봉합.** 경로에 토큰이 있고
   `ResponseStatusException(404)` 을 쓴다. Spring 기본 `/error` 응답의 `path` 필드로 샐 가능성이 있으나
   **실증하지 않았다.** BC 격리로 본 PR 범위 밖 — 별도 PR 로 등재.
3. **비-GET 요청의 응답 형태 미확인.** SecurityConfig 가 GET 고정 permitAll 이라 비-GET 은 인증 체인으로
   떨어진다. 그 응답은 identity-access BC 소관이라 확인만 하고 등재.
4. **교차모델 검증 부재.** `codex` CLI 미설치 + 세션의 에이전트 dispatch 금지로 outside voice 를 돌리지
   못했다. 구현자가 자기 계획을 리뷰한 편향이 남는다(#308·#309 동형).

### 확인된 무해 항목

- **로그 유출 없음 (E7).** 500 경로가 `log.error(…, ex)` 로 스택을 찍지만, 협력자가 토큰을 예외에 싣지
  않는다 — `ShareTokenMinter.hash` 는 `throw`/`require`/`check` 0건이고,
  `AnonymousLayoutSanitizer.sanitize` 는 total 함수로 모든 예외를 잡아 `[]` 로 fail-closed 하며 로그에도
  `e.javaClass.simpleName` 만 남긴다. 리포지토리는 **해시**를 받으므로 최악의 DB 예외도 원문이 아닌 해시를 싣는다.
- **`PublicDashboardNotFoundException`** 은 고정 메시지("공유된 대시보드를 찾을 수 없습니다.")만 갖고
  토큰을 품지 않는다.

## 함정 기록 (다음 사람을 위해)

**Kotlin 은 블록 주석이 중첩된다** (Java 와 다름). KDoc 안에 경로 와일드카드를 `/` 다음 `*` 형태로 쓰면
새 주석이 열려 `Syntax error: Unclosed comment` 로 컴파일이 깨진다. 이 PR 작업 중 실제로 1회 발생했다.
경로 와일드카드는 산문으로 풀어 쓴다.
