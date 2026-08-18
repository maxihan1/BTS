# FR-NT-02 이메일/Webhook 채널 — 알림 채널 완성 (백엔드 D1·D4·D5)

> slug: fr-nt-02-email-webhook-channels
> type: backend
> agent: backend-engineer
> primary_bc: notification
> 생성: 2026-06-14

## Brief

FR-NT-02(알림 채널)의 인앱 채널은 이미 머지됨(PR #126 백엔드 + PR #137 프론트/E2E).
이번 PR은 **이메일 채널**을 기존 `NotificationChannelSender` 추상 위에 구현한다.
**Webhook 채널은 별도 후속 FR로 분리**(아래 결정 참조). FR-NT-02 전체는 Webhook 후속이라 부분완료 유지.

**범위 (이메일 채널, 백엔드 D1·D4·D5 이메일 부분)**.
1. **EmailChannelSender** — `supports(EMAIL)`, `JavaMailSender`(Spring Mail)로 발송.
   Testcontainers MailHog로 SMTP 수신 검증(product §2.2 D5 정본 방식).
2. `UserLookupPort`에 `recipientUserId → email` 조회 메서드를 **default 구현으로 추가**
   (기존 fake ~35개 보호, fail-safe 빈 응답). 실 구현은 identity-access `UserLookupAdapter`가 override.
3. product §2.2 D1·D4·D5의 **이메일 부분 반영** + Webhook을 별도 FR로 분리하는 결정을
   ADR/product에 동기화. FR-NT-02 전체는 부분완료 유지(`[~]`), FR 카운트 불변(122).

**Webhook 분리 결정 (Maxi 확정 2026-06-14)**.
- Webhook 채널은 `NotificationChannelSender`(per-user 모델)가 아니라
  **`WebhookRequested` 이벤트 디스패처**로 구현하기로 방향 확정(옵션 B).
- 그러나 코드 실측 결과 `WebhookRequested`(전환 post-action `CallWebhookPostAction` 발행)는
  **현재 어느 pgmq 큐에도 발행되지 않음** — 전환 API 응답(`TransitionResponseDto.events`)에만 존재.
- 제대로 구현하려면 ① project-workflow BC에 전환 emitEvents pgmq 발행 파이프라인(4종 post-action 공통),
  ② notification BC에 소비+HTTP POST 디스패처가 필요 → cross-BC, 범위 큼.
- 따라서 **Webhook = 전용 후속 FR**로 분리(전환-이벤트 발행 파이프라인 설계 포함). 이번 PR 범위 밖.

**기존 코드 사실(실측)**.
- `Channel` enum(`domain/Channel.kt`)에 `EMAIL` 이미 정의됨 — enum 추가 불필요.
- `NotificationWorker.kt:191`이 `channelSenders.firstOrNull { it.supports(recipient.channel) }`로
  디스패치, sender 리스트는 Spring 자동 주입(`List<NotificationChannelSender>`) → **새 sender 빈만 등록**(워커 수정 불요).
- `NotificationPolicy.channel`이 채널 결정 → `EventRecipientResolver`가 `match.channel`을
  `ResolvedRecipient.channel`로 전달 → 정책에 channel=EMAIL이면 수신자가 EMAIL로 생성됨(resolver 수정 불요).
- `deliver()`(`NotificationWorker.kt:213`)가 send 예외를 **best-effort로 삼킴**(로그+PENDING fallback) →
  이메일도 동일 패턴(능동 재시도 없음, SDD에 채널별 재시도 명세 없음).
- `users` 테이블에 `email` 컬럼 존재(V001). `UserLookupPort`(shared-kernel) 기존 cross-BC 포트 재사용.

**범위 밖**. Webhook 채널(별도 FR), Slack 채널(slack-integration BC), Teams 채널.

## 도메인 정리

- **BC**: notification (단일 BC, cross-BC import 없음)
- **영향 엔티티**: 없음 (기존 `Notification` aggregate / `NotificationChannelSender` 추상 재사용)
- **새 용어**: 없음 (Channel·EMAIL·NotificationChannelSender 모두 기존 용어). glossary 갱신 불요.
- **신규 컴포넌트**: `EmailChannelSender`(notification.channel) — `InAppChannelSender` 형판 동형.
- **cross-BC 포트 확장**: `UserLookupPort`(shared-kernel)에 이메일 조회 메서드 1개를 default로 추가.
  실 구현은 identity-access `UserLookupAdapter`가 override. learnings `interface-extension-default-method`
  준수(추상 추가 금지 — 기존 인라인 fake ~35개 컴파일 보호, default는 fail-safe 빈 응답).
- **기존 결정 충돌**: 없음. ADR [2026-06-12-notification-inapp-channel-delivery](../decisions/2026-06-12-notification-inapp-channel-delivery.md)
  결정 1·결과영향이 "이메일 채널 = 동일 추상 위 후속 PR"로 명시 → 계획된 연장.
- **결정 deviation (이번 PR로 ADR 보완)**: ADR 결정 2가 FR-NT-02 채널 = 인앱+이메일+Webhook(3종)이라 했으나,
  Webhook은 per-user 알림 모델과 맞지 않고(URL 출처 부재) 전환-이벤트 디스패처로 재설계 필요 +
  발행 파이프라인이 cross-BC라, **Webhook을 전용 후속 FR로 분리**(Maxi 확정 2026-06-14).
  ADR amendment + product 동기화 필요(스펙/plan 단계에서 처리).
- **관련 ADR**: [2026-06-12-notification-inapp-channel-delivery](../decisions/2026-06-12-notification-inapp-channel-delivery.md)(보완 대상),
  [2026-06-11-notification-policy-bc-bootstrap](../decisions/2026-06-11-notification-policy-bc-bootstrap.md)
- **재시도 정책**: 채널별 능동 재시도 없음. pgmq at-least-once + dead-letter(ADR 결정5) + send 실패 시
  PENDING fallback(`deliver()` best-effort)으로 일관. SDD §9에 채널별 재시도 명세 없음 확인.

## 스펙

전체 스펙. [docs/specs/2026-06-14-fr-nt-02-email-webhook-channels.md](../specs/2026-06-14-fr-nt-02-email-webhook-channels.md)

핵심 요약.
- `EmailChannelSender`(supports(EMAIL)) — `MimeMessageHelper`(UTF-8)로 수신자 이메일에 발송, 제목=notification.title.
- `UserLookupPort.findEmailById(userId): String?` default 메서드 추가(identity-access adapter override).
- 실패(이메일 부재/SMTP 다운)는 예외→`deliver()` best-effort PENDING(워커/재시도/스키마 무변경).
- MailHog Testcontainers로 SMTP 발송→수신 + 한국어 제목 보존 검증.

## Brainstorming Check

✅ 통과 (1회, 직접 적대적 점검). gap 4건 보강 — 한국어 인코딩(MimeMessageHelper), from 주소, SMTP 타임아웃, FAILED 미사용 명시. 미해결: 신규 mail 의존성(절대규칙#17, 게이트1 승인).

## Plan

### Task 1. UserLookupPort.findEmailById default 메서드 추가 (shared-kernel)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/user/UserLookupPort.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/user/UserLookupPortDefaultTest.kt`]
- depends-on: []

**RED**:
- `UserLookupPortDefaultTest`에 케이스 추가 — `exists`만 구현한 anonymous `UserLookupPort`의 `findEmailById(randomUUID)`가 `null`을 반환(default fail-safe, 기존 fake 보호).
- 실패: `findEmailById` 메서드 없음(컴파일 에러).

**GREEN**:
- `UserLookupPort`에 `fun findEmailById(userId: UUID): String? = null` 추가(추상 아님, default). KDoc — fail-safe 의미 + production은 UserLookupAdapter override 명시(learnings `interface-extension-default-method`).

**REFACTOR**:
- KDoc 정리. 기존 `findDisplayNamesByIds`와 톤 일치.

**검증**: `./gradlew :backend:modules:shared-kernel:test --tests *UserLookupPortDefaultTest`

### Task 2. UserLookupAdapter.findEmailById override + 통합테스트 (identity-access)

**메타**.
- agent: `backend-engineer` (identity-access 파일 — gate 2에서 security-engineer 검토. 단순 read 쿼리, auth 로직 아님)
- files: [`backend/modules/identity-access/.../user/UserLookupAdapter.kt`, identity-access UserLookupAdapter 통합테스트(기존 파일 확장 또는 신규)]
- depends-on: [1]

**RED**:
- Testcontainers 통합테스트 — users 행 시드 후 `findEmailById(id)`가 그 email 반환, 미존재 id는 `null`.
- 실패: adapter가 default(null) 사용 → 시드한 email 불일치.

**GREEN**:
- `UserLookupAdapter`에 `findEmailById` override — `SELECT email FROM users WHERE id = ?`(NamedParameterJdbcTemplate, 기존 쿼리 스타일 일치). 0행이면 null.

**REFACTOR**:
- 쿼리 상수화 + KDoc.

**검증**: identity-access 통합테스트 그린 (`./gradlew :backend:modules:identity-access:test --tests *UserLookup*`)

### Task 3. EmailChannelSender + 단위테스트 + mail 의존성 + JavaMailSender 설정 (notification)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/build.gradle.kts`, `backend/modules/notification/.../channel/EmailChannelSender.kt`, `backend/modules/notification/src/main/resources/application.yml`, `backend/modules/notification/.../channel/EmailChannelSenderTest.kt`]
- depends-on: [1]

**RED**:
- `EmailChannelSenderTest`(단위, MockK) — `JavaMailSender`/`UserLookupPort` mock.
  - SEND-1. email 존재 시 `MimeMessage` 생성·발송, 제목=title, from=설정값, to=조회 email.
  - SEND-2. email 부재(port null) → 발송 안 함 + 예외 throw(deliver PENDING 계약).
  - SUPPORTS-1/2. supports(EMAIL)=true, supports(IN_APP)=false.
- 실패: `EmailChannelSender` 없음.

**GREEN** (Maxi 확정: `spring-boot-starter-mail` + Boot autoconfig 사용).
- build.gradle.kts에 `org.springframework.boot:spring-boot-starter-mail` 추가(신규 의존성=절대규칙#17, 게이트1 승인됨). JavaMailSender 타입 + angus-mail 구현 동반.
- **별도 MailConfig 빈 만들지 않음** — `NotificationTestBootApplication`이 `@SpringBootApplication`(autoconfig 활성, Flyway/DataSource만 제외)이라 `MailSenderAutoConfiguration`이 `spring.mail.host` 설정 시 `JavaMailSender`를 자동 생성한다. 별도 빈 정의 시 **중복 빈 충돌** → autoconfig에 위임(CONCERN-1 자연 해소: 빈 항상 존재 → 직접 주입, 부재 분기 없음).
- `application.yml`에 `spring.mail.host`(기본값 예: `localhost`)·`port` + `spring.mail.properties.mail.smtp.connectiontimeout`/`timeout`/`writetimeout`(예 5000, NFR-6) + `bts.notification.email.from`(기본 `no-reply@bts.local`, FR-7) 추가. 기본 host로 어느 컨텍스트든 빈 생성 → 부팅 견고성(NFR-2).
- `EmailChannelSender`(@Component) — `JavaMailSender` + `UserLookupPort` **직접 주입** + `@Value("\${bts.notification.email.from}")`. `supports(EMAIL)`. `send()`: port.findEmailById → 없으면 throw(PENDING), `MimeMessageHelper`(UTF-8)로 제목/본문 작성 후 발송(SMTP 실패=예외 전파→PENDING). 이메일 주소는 로그 마스킹(NFR-4).

**REFACTOR**:
- destination/from 상수, KDoc, InAppChannelSender 톤 일치.

**검증**: `./gradlew :backend:modules:notification:test --tests *EmailChannelSenderTest`

### Task 4. MailHog Testcontainers 통합테스트 (notification)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/test/.../EmailChannelSenderIntegrationTest.kt`(신규), `backend/modules/notification/src/test/.../NotificationDeliveryTestcontainersConfig.kt`(MailHog 컨테이너 추가) 또는 신규 config]
- depends-on: [3]

**RED**:
- MailHog `GenericContainer("mailhog/mailhog:v1.0.1")`(SMTP 1025/HTTP 8025), `@DynamicPropertySource`로 `spring.mail.host/port` 주입.
- 통합테스트 — `channel=EMAIL` Notification을 EmailChannelSender로 발송 → MailHog HTTP API `/api/v2/messages`에서 수신 확인 + **한국어 제목 보존**(NFR-5) + (end-to-end 경로면 notifications 행 `SENT` 전환). email 부재 시 PENDING(S2).
- **eng-review CONCERN-2 반영**: MailHog가 저장한 Subject 헤더는 MIME encoded-word(`=?UTF-8?...?=`)일 수 있다. 한국어 단언은 **디코드 후**(`jakarta.mail.internet.MimeUtility.decodeText` 또는 본문 part 비교) 수행 — 인코딩 형태로 단언하면 실제 깨짐을 못 잡는 거짓그린.
- 실패: EmailChannelSender 발송 경로 미완/인코딩 깨짐.

**GREEN**:
- Task 3 구현으로 통과. 필요한 테스트용 `UserLookupPort` fake 빈(알려진 email 반환) 제공.

**REFACTOR**:
- MailHog API 폴링 헬퍼 추출, 컨테이너 reuse 설정.

**검증**: `./gradlew :backend:modules:notification:test --tests *EmailChannelSenderIntegrationTest` (단독 재실행으로 flaky 확정, 메모리 `concurrent-testcontainers-suite-flaky`)

### Task 5. 문서 전수 동기화 (ADR 보완 + product + verify)

**메타**.
- agent: `backend-engineer`
- files: [`docs/decisions/2026-06-12-notification-inapp-channel-delivery.md`(보완), `docs/plan/product/notification-dashboard.md`(§2.2 이메일 부분), 필요 시 `docs/plan/fr-index.md`/`docs/plan/README.md`/SDD §9]
- depends-on: [1, 2, 3, 4]

**RED/GREEN(문서)**:
- ADR 2026-06-12 결정2에 amendment 단락 — Webhook을 전용 후속 FR로 분리(URL 출처/전환-이벤트 발행 파이프라인 미설계 근거), 이번 PR=이메일 채널.
- product §2.2 — 이메일 채널 완료 반영(D1·D4·D5 이메일 부분 진행 표기), Webhook은 별도 FR 주석. FR-NT-02 전체 부분완료(`[~]`) 유지.
- FR 카운트 불변(122) 확인 — 신규 FR ID는 이번에 mint하지 않음(Webhook FR는 후속 spec에서).

**검증**: `bash scripts/verify-master-plan.sh` 통과(종료 0).

## Plan 메타

- task 수: 5
- 모듈 컴파일 의존(메모리 `bts-plan-wave-gradle-module-compile`): shared-kernel(T1) → identity-access(T2)·notification(T3,T4). T3는 자체 build.gradle에 mail 의존 추가 + 포트(T1) 사용 → depends-on [1].
- 예상 wave: W1[T1] → W2[T2, T3] → W3[T4] → W4[T5] (T2·T3 다른 모듈/파일이라 병렬 가능, 단 T1 선행 컴파일).
- TDD 강제: yes (T1·T2·T3·T4). T5는 문서.
- 신규 의존성(mail): 절대규칙#17 — 게이트1 Maxi 승인 대상.
- 추가 검증: ktlint/detekt(신규 파일 baseline 밖), ArchUnit BC 격리, 기존 notification 통합/단위 회귀.

## 리뷰 결과

### eng 집중 독립 리뷰 (2026-06-14) — backend plan, autoplan 생략(메모리 bts-review-plan-autoplan-overkill)

7개 점검 포인트 + 일반 엔지니어링 적대적 리뷰.

- **BLOCKER: 없음.**
- **CONCERN-1 (②) — 해소**: `MailConfig` 빈 무조건 생성 + `ObjectProvider<JavaMailSender>` 병용은 모순(부재 분기 죽은코드·테스트불가). → 무조건 생성 + 직접 주입으로 단일화, SEND-3 제거(Task 3 반영 완료).
- **CONCERN-2 (⑤) — 해소**: MailHog Subject는 MIME encoded-word. 한국어 검증은 디코드 후 단언(Task 4 반영 완료, 거짓그린 방지).
- **NOTE (①)**: mail 의존성 신규 = 절대규칙#17 → 게이트1 Maxi 승인. spring-context-support+angus-mail 버전은 Boot dependency-management(starter-websocket 경유) 관리 확인. spring-boot-starter-mail 대안도 가능 — 구현 중 컴파일/버전 이슈 시 전환.
- ✅ **③ 실패→PENDING 계약**: send() throw→deliver() best-effort PENDING, dedup이 재호출 차단(반복 throw 없음). 인앱과 일관.
- ✅ **④ 포트 default 확장**: `findEmailById = null` default가 기존 fake ~35개 보호(findIdsByUsernames/findDisplayNamesByIds 선례 동일).
- ✅ **⑥ 무변경 제약**: 워커/resolver/평가기/스키마 변경 없음. sender 빈 + 포트 default + MailConfig + 의존성만.
- ✅ **⑦ ArchUnit BC 격리**: notification→identity-access 직접 import 없음, 이메일 조회는 shared-kernel 포트 경유.
- **추가 NOTE**: notification 모듈 mail 프로퍼티(host/port/from) 기본값은 모듈 resources에, 실값은 배포 조립 시점 주입(현 표준 test-assembled, 메모리 no-cross-bc-deployment-assembly). 통합테스트는 @DynamicPropertySource. notification 통합테스트에 test `UserLookupPort` fake 빈(알려진 email) 제공 필요(Task 4).
