# ADR: 인증 요청의 `/error` 경로 토큰 유출 봉합 — `include-path: never` 전역 제거

> 날짜: 2026-07-26
> 상태: 채택 (Accepted)
> 관련 FR: 없음 — **FR 신설 0 · 카운트 129 불변** (보안 위생, 기능 변경 0)
> 선행 ADR: `2026-07-25-public-dashboard-error-instance-sanitization.md` (#310) — 본 결정은 그 **잔여 위험 #2** 의 해소
> 형제 ADR: `2026-07-26-nginx-access-log-token-masking.md` (#311, N1) — 같은 유출 표면의 웹서버 로그 축
> Plan: `docs/plans/2026-07-26-authenticated-error-path-token-leak.md`
> PR: #312

## 맥락 (Context)

BTS 에는 **경로 세그먼트에 원문 비밀 토큰을 싣는** permitAll 경로가 4개 있다.

| 경로 상수 (`SecurityConfig`) | 실리는 비밀값 |
|---|---|
| `PUBLIC_DASHBOARDS_PATH` (L209) | 대시보드 공유 토큰 |
| `ICAL_FEED_PATH` (L213) | 캘린더 피드 토큰 |
| `INBOUND_WEBHOOK_PATHS` (L219-221) | git 웹훅 토큰 · automation 웹훅 토큰 |

Spring Boot 의 `BasicErrorController` 가 내는 기본 에러 본문은 `path` 필드에 **요청 URI 원문**을 담는다.
`ErrorProperties.includePath` 기본값이 `ALWAYS` 이고(`message`·`stacktrace` 와 달리 opt-in 이 아니다),
레포에 `server.error.*` yml 오버라이드는 0건이었다.

### 기존 안전 전제와 그 표본

중앙 `SecurityConfig` 는 `/error` 를 permitAll 에 넣지 말라는 20줄 주석(FR-AT-07 PR-C T15 도입)을 달고
이렇게 단언했다.

> `/error` 가 이 `anyRequest()` 에 걸려 authenticated 인 덕분에 `BasicErrorController` 가 실행되지 못하고
> 필터가 빈 401 을 준다 — 즉 **지금의 안전은 이 한 줄에 얹혀 있다**.

**그 실측의 표본은 전부 익명 요청이었다.** `GitWebhookInboundPermitAllTest` 의 T15-6·T15-7·T15-8 은
`Authorization` 헤더를 붙이지 않는다. 인증 요청에서도 같은지는 측정된 적이 없다.

### 실측 (추정 아님)

prod 9-BC 조립 부팅(실 Tomcat RANDOM_PORT + `@ActiveProfiles("prod")`) + `POST /api/v1/auth/login` **실 로그인
JWT** 로 두 축을 측정했다. 둘 다 원문 토큰이 그대로 나왔다.

| 축 | 요청 | 관측된 응답 본문 |
|---|---|---|
| N2-A | 인증 `GET /api/v1/webhooks/git/{token}` (405) | `{"status":405,"error":"Method Not Allowed","path":"/api/v1/webhooks/git/<원문토큰>"}` |
| N2-B | 인증 `GET /ical/feed/{token}.ics` (404) | `{"status":404,"error":"Not Found","path":"/ical/feed/<원문토큰>.ics"}` |
| N2-C | **익명** `GET /api/v1/webhooks/git/{token}` | 빈 401 — 유출 없음 |

**델타는 정확히 인증 여부 하나다.**

### 기전

`SessionCreationPolicy.STATELESS`(L135) 에서 Spring Security 6 의 `BearerTokenAuthenticationFilter` 는
인증 성공 시 `SecurityContext` 를 `RequestAttributeSecurityContextRepository`(STATELESS 기본 저장소)에
저장한다. 그 저장소는 이름 그대로 **요청 attribute** 에 담으므로 **같은 요청의 ERROR 디스패치에서 복원된다.**
복원되면 `/error` 의 `authenticated()` 는 통과하고 `BasicErrorController` 가 살아난다.

즉 `/error` 가 authenticated 라는 사실은 **인증된 요청을 막지 못한다.**

### 왜 문제인가 (심각도 — P1)

요청자는 이미 그 토큰을 안다. 이 응답이 새 정보를 주지는 않는다. 실질 위험은 **평문 토큰의 2차 적재**다 —
응답 본문을 수집하는 에러 트래커/APM, 프록시·CDN 캐시, GitHub 웹훅 delivery 기록(응답 본문을 저장·표시한다),
사용자가 오류 본문을 버그리포트에 붙여넣는 경우. 그 순간 그것이 **평문 토큰 저장/로깅**
(`DEVELOPMENT.md §1.1-1·§1.1-2`)이다.

인증 우회·권한 상승이 아니므로 **P0 가 아니다. P1 — 정보 노출(위생)** 로 판정한다. #310 과 같은 등급이다.

## 결정 (Decision)

### D1. `server.error.include-path: never` 로 **전역 제거**한다

조립 `application.yml` 의 `server.error.include-path` 를 `never` 로 고정해
`BasicErrorController` 응답에서 `path` 필드를 없앤다.

기각한 대안.
- **(a) 비밀-경로 목록으로 판별해 그 경로에서만 제거** — ADR 2026-07-25 §D1 이 같은 이유로 기각한 형태다.
  목록은 새 비밀-경로가 생길 때마다 갱신돼야 하고, 빠뜨리면 조용히 샌다. 실제로 #311(N1)에서 초기 열거가
  SPA 라우트 `/dashboards/shared/{token}` 을 누락했다 — **목록이 눈가리개라는 증거를 이 트랙이 스스로 만들었다.**
- **(b) `/error` 를 permitAll 로 열고 커스텀 `ErrorAttributes` 로 정화** — 얻는 것 없이 익명에게 상태코드
  오라클을 새로 열어 준다. 봉합이 목적인데 표면을 넓힌다.
- **(c) 커스텀 `ErrorAttributes` 빈으로 `path` 를 제거** — 프레임워크가 제공하는 프로퍼티가 정확히 같은 일을
  한다. 같은 결과를 코드로 재구현할 이유가 없다(`CLAUDE.md §2 Simplicity First`).

**근거.** `path` 는 **전역으로 위험하고 국소적으로만 유용한** 필드다. 소비처가 0건임을 전수 확인했으므로
(아래 §결과) 전역 제거의 비용이 실질적으로 0이다. 판별식을 두면 판별 책임이 엉뚱한 곳에 놓이고
새 비밀-경로마다 재발한다.

### D2. `/error` 의 `authenticated()` 는 **유지**한다 (defense-in-depth)

`path` 제거로 이 줄의 필요성이 사라진 것처럼 보이지만 유지한다. `path` 제거는 본문 한 필드를 없앤 것이고,
permitAll 로 열면 (1) 익명에게 상태코드 오라클이 생기고 (2) N3(잠복 전역 advice — `ProjectArchivedExceptionHandler`,
`pd.instance` 미설정으로 #310 완전 동형) 같은 다른 통로가 익명에게도 열린다.

### D3. 잘못된 안전 단언을 **주석에서 정정**한다

`SecurityConfig` 의 "지금의 안전은 이 한 줄에 얹혀 있다"는 표본이 익명 한정이었다. 그 문장을 남겨 두면
다음 사람이 같은 오판을 한다. **정정 문구 + 실제 근거(`include-path: never`) 지목**을 같은 자리에 넣는다.

**이것이 이 PR 의 두 번째 값어치다.** 수정 자체는 yml 한 줄이지만, 그 한 줄이 지켜야 할 불변식이
어디에 적혀 있는지를 코드가 스스로 가리키게 만들었다.

### D4. 재발 방지 — 인증·익명 **대조군을 같은 파일에** 둔다

`AuthenticatedErrorPathTokenLeakTest` 는 3축을 함께 갖는다. 인증 2축(405·404)이 봉합을 검증하고,
익명 1축이 대조군이 되어 **델타가 인증 여부 하나임**을 파일 안에서 자립적으로 못 박는다.

N2-A 는 `Allow` 헤더 존재를, N2-B 는 404 상태코드를 별도 단언한다. 이 양성 증거가 없으면 요청이
DispatcherServlet 에 도달하지 못한 경우(필터 401)에도 "토큰 없음"이 성립해 단언이 **공허해진다**.
405 는 `DefaultHandlerExceptionResolver` 가 `sendError` 직전에 `Allow` 를 세팅하고 그 헤더는 ERROR
디스패치 응답에도 살아남는다(T15-7 이 확립한 판별자를 반대 방향으로 재사용).

### D5. ★ 이 봉합이 **기존 가드의 눈을 가린다** — N2-D 로 대체한다

리뷰(C1)가 지적하고 **뮤테이션으로 확정**한 사항이다. `/error` 를 permitAll 로 여는 회귀는 지금까지
"응답 본문에 원문 토큰이 있는가"로 잡혔다(T15-6·T15-7 의 `doesNotContain`). `include-path: never` 로
`path` 를 없앤 뒤에는 그 뮤테이션의 본문에 **토큰이 없어** 해당 축들이 전부 초록으로 남는다.
즉 봉합이 감시 장치를 무력화한다.

**리뷰가 제안한 처방(익명 405 축에 `body().isEmpty()` 추가)은 이 회귀를 잡지 못한다 — 실측으로 기각했다.**
그 요청은 REQUEST 디스패치에서 `BearerTokenAuthenticationEntryPoint` 가 `setStatus(401)` 로 끝내므로
`sendError` 가 아니고 **ERROR 디스패치를 아예 타지 않는다.** `/error` 의 권한 설정이 그 요청과 무관하다.

판별자가 되려면 요청이 (1) REQUEST 디스패치에서 **인가를 통과**하고 (2) 컨트롤러 밖에서 오류가 나
`sendError` 로 넘어가야 한다. **익명 + permitAll 경로 + 404** 가 정확히 그 조합이며, 익명 iCal 피드가
그 형태다(GET permitAll → 컨트롤러 도달 → 미등록 토큰 `ResponseStatusException(404)` → `@ExceptionHandler`
부재 → `sendError`). 이것이 **N2-D**다.

**교훈.** 봉합은 표면을 없애는 동시에 그 표면을 감시하던 판별자도 없앤다. 봉합 PR 은 자기가 무력화한
가드를 찾아 대체 판별자를 세워야 한다. 그것을 확인하는 유일한 방법은 뮤테이션이다.

## 검증 (Verification)

**뮤테이션 1 — 봉합 자체.** `include-path: never` 부재 상태가 곧 직전 RED 커밋(`805cae3bd`)이고 그때
인증 2축이 red, 익명 1축이 green 이었다. 줄을 넣자 3축 green(`0ff02c69f`). 판별력이 정확히 그 한 줄에 붙어 있다.

**뮤테이션 2 — `/error` 를 permitAll 로 여는 회귀** (`SecurityConfig` 에 `requestMatchers("/error").permitAll()`
주입, 컴파일 반영을 `javap` 로 확인).

| 상태 | 결과 | 판정 |
|---|---|---|
| N2-D 도입 **전** | 40테스트 **전량 초록** | 봉합이 기존 가드를 무력화했음이 실증됨 (§D5) |
| N2-D 도입 **후** | **N2-D 단독 red**, 나머지 40 초록 | 판별력이 정확히 그 축 하나에 붙음(과잉결합 아님) |
| 뮤테이션 원복 | 41테스트 전량 초록 | 기준선 복귀 |

**회귀.** `:modules:app:test` **8클래스 41테스트 전량 통과**(실패 0 · 에러 0 · 스킵 0) — XML 집계로 확인.

⚠️ **형제 permitAll 가드의 판별력 상실을 함께 기록한다.** `GitWebhookInboundPermitAllTest` T15-6·T15-7 은
`/error` permitAll 회귀를 더 이상 잡지 못한다(위 뮤테이션 2 의 첫 행). 초안 ADR 은 "형제 가드가 `path` 에
기대고 있지 않음이 증명된다"고 적었으나 **정반대였다** — 기대고 있었고, 그래서 판별력을 잃었다.
`SlackInboundPermitAllTest` 는 같은 유형인지 개별 대조하지 않았다(미확인).

## 결과 (Consequences)

- **기능 변경 0.** 상태코드·`errorCode`·컨트롤러 ProblemDetail 본문 전부 불변. 사라지는 것은
  `BasicErrorController` 폴백 본문의 `path` 필드 하나다.
- **소비처 0건 확인.** `apps/web/src` 전수 grep 결과 이 필드를 읽는 프로덕션 코드가 없고, 백엔드에도
  커스텀 `ErrorAttributes` 가 없다(전역 제거가 다른 조립과 충돌하지 않는다).
- **마이그레이션 0 · 신규 의존성 0 · cross-BC 로직 변경 0 · FR 카운트 129 불변.**
- **진단성 영향.** 컨트롤러가 잡지 못한 405/404/415 응답에서 "어느 경로였는지"가 본문에서 사라진다.
  요청자는 자기가 부른 URL 을 알고, 서버 측 진단은 접속 로그(#311 로 토큰만 마스킹된 상태)가 담당하므로
  실질 손실은 작다.

### 잔여 위험

1. **N3 — 잠복 전역 advice.** `ProjectArchivedExceptionHandler.kt:38` 이 선택자 없는 `@RestControllerAdvice` +
   `@Order(HIGHEST_PRECEDENCE)` + `ProblemDetail` + `pd.instance` 미설정(#310 완전 동형). 현재 throw 지점이
   쓰기 가드뿐이라 도달 불가지만 **배선은 완료돼 있다.** 별도 PR.
2. **N4 — `WorkflowSchemeController` GET 권한 갭.** 본 결정과 무관한 별개 트랙. Maxi 정책 결정 선행.
3. **`Referrer-Policy` 헤더 부재.** 공유 뷰가 외부 리소스를 참조하면 브라우저가 제3자에게 토큰 담긴
   Referer 를 보낸다. #311 의 Referer 마스킹은 *우리 로그* 만 덮는다. #311 후속으로 등재된 상태.
4. **경로 기반 토큰 설계 자체.** 토큰이 URI 에 있는 한 브라우저 히스토리에는 계속 남는다.
   선행 ADR `2026-07-02-fr-db-03-dashboard-share` D1 이 택한 설계이며 본 결정의 범위 밖이다.
5. **★ `IcalFeedController` 만 단일 방어선이 됐다** (리뷰 C3). 경로 토큰 4개 중 3개는 컨트롤러-로컬
   `problem()` 헬퍼로 `instance` 를 고정한 **코드 레벨 봉인**을 갖는다(`PublicDashboardController` ·
   `AutomationWebhookController` · `GitWebhookController`). `IcalFeedController` 는 `@ExceptionHandler` 가
   없고 identity-access 모듈에 전역 advice 도 없어, 이 경로의 토큰 비노출은 이제 `include-path: never`
   **한 줄에만** 얹혀 있다. #310 §D2 의 "재발 방지를 구조로" 원칙과 방향이 반대인 비대칭이다.
   **인지하고 수용한다** — 4번째 봉인 추가는 별도 PR. (전역 제거가 있는 한 실제 유출은 없다.)
6. **BC 독립 부팅 사각** (리뷰 C5). `:modules:identity-access:bootRun` 으로 띄우는 dev 환경은 자기 모듈
   yml 을 쓰므로 이 설정을 받지 못하고, `IcalFeedController` 가 바로 그 모듈에 있다. **운영 무영향**
   (프로덕션 산출물은 조립 앱 하나 — `infra/prod/Dockerfile.backend` 가 `--spring.profiles.active=prod` 로
   조립 앱을 띄운다). dev 위생 항목으로 등재만 한다.
7. **`spring.mvc.problemdetails.enabled` 를 켜면 `instance` 자동채움이 부활한다.** 현재 레포 전체 미설정
   (기본 false)이다. 켜면 Spring 내장 핸들러가 404/405/415 를 `ProblemDetail` 로 응답하며 `instance` 에
   요청 URI 를 자동으로 채워 `include-path: never` 를 우회한다. 다행히 N2-A/B 가 특정 필드가 아니라
   **본문 전체**에서 토큰을 찾으므로 그때도 red 가 된다(단언 설계의 실질적 강점).
8. **교차모델 검증 부재.** `codex` CLI 미설치로 outside voice 를 돌리지 못했다. 구현자가 자기 계획을
   리뷰한 편향이 남는다(#308·#309·#310·#311 에 이어 5연속). 해소하려면 `npm install -g @openai/codex`.
   **단, 본 PR 은 `superpowers:code-reviewer` 독립 리뷰를 붙였고 그 리뷰가 §D5(C1)를 발견했다** —
   구현자 단독이었으면 놓쳤을 결함이다. 동시에 그 리뷰의 처방은 뮤테이션으로 기각됐다(§D5).
   **리뷰도 실측으로 검증해야 한다는 것이 이 PR 의 부수 교훈이다.**

## 함정 기록 (다음 사람을 위해)

**PAT 로 측정하면 거짓 음성이 난다.** 같은 베이스를 쓰는 형제 조립 테스트들은 인증에 PAT(`Bearer pat_…`)를
쓴다 — MFA 게이트·세션 시드를 피할 수 있어 합리적이다. 그러나 `PatAuthenticationFilter` 는
`SecurityContextRepository.saveContext` 를 **호출하지 않는다**(레포 전체 `saveContext` 호출 0건). 그래서 PAT
요청은 ERROR 디스패치에서 컨텍스트가 복원되지 않아 **우연히** 안전하다. 이 축을 PAT 로 측정하면
"새지 않는다"는 결론이 나오고 그것은 기전을 빗나간 관측이다. **누가 "일관성" 명목으로 PAT 필터에
`saveContext` 를 추가하면 그 순간 PAT 도 유출 경로가 된다** — `include-path: never` 가 그때도 막지만,
필터 변경 자체를 무해하다고 보지 말 것.

**`users` 시드에 `display_name` 을 반드시 넣을 것.** 컬럼은 nullable 이지만 `UserRowMapper`
(`UserRepository.kt:548`)가 non-null 로 매핑해 NULL 이면 `findByUsername` 이 NPE 를 던지고 로그인이 죽는다.
형제 조립 테스트들은 `(id, username)` 만 넣는데 PAT 인증이라 `findByUsername` 을 타지 않아 드러나지 않았다.
그대로 복사하면 로그인 축이 있는 테스트만 조용히 깨진다.
