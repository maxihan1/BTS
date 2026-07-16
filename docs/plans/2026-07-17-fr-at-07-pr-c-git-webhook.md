# FR-AT-07 PR-C — PR_MERGED 트리거 + Git Webhook 인바운드

> slug: fr-at-07-pr-c-git-webhook
> type: auth
> agent: security-engineer
> 생성: 2026-07-17
> 브랜치: auth/fr-at-07-pr-c-git-webhook (base: origin/main 552ef6a5a)

## Brief

**사용자 원문**. "fr-at-07 pr-c 진행해줘"

**범위**. FR-AT-07의 남은 D단계 전부 — PR-A(#274/#275 인바운드 웹훅 prod 도달 + 암호화 키 배포)와
PR-B(#276 Fix Version 설정 통로)가 완료된 상태에서, PR 머지가 실제로 자동화 규칙을 발화시키는
마지막 경로를 잇는다.

- D1. 도메인 — GitWebhookEvent
- D2. 명세 — GitHub/GitLab Webhook 처리 + 커밋 메시지 이슈 키 추출
- D3. 데이터 모델 — webhook secret 저장 (활용, 신규 스키마 여부는 spec에서 확정)
- D4. 백엔드 — `POST /api/v1/webhooks/git` + 서명 검증 + automation permitAll 중앙등록
- D5. 백엔드 테스트 — 가짜 페이로드
- D6. 프론트 UI — Webhook URL 생성 페이지
- D7. E2E

**완료 시**. FR-AT-07 `[x]` 마킹 → automation BC **7/7 완결** (9번째 모듈 전 FR 완료).

**classify 결과 및 덮어쓰기 근거**.
스크립트 원본 판정은 `type=ui / agent=frontend-engineer / slug=fr-at-07-pr-c-pr-merged-git-webhook-automation-per`.
제목의 "UI" 토큰에 끌린 오분류로 판단해 Maxi 확인 후 **type=auth / agent=security-engineer**로 덮어씀.
근거 — 이 PR의 폭발 반경 최대 지점은 인증 없이 열리는 인바운드 엔드포인트(permitAll)와 HMAC 서명
검증이며, UI는 D6 하나. slug도 잘린 채(`-per`) 생성돼 선행 PR 관례(`fr-at-07-pr-b-fix-version`)에 맞춰
`fr-at-07-pr-c-git-webhook`으로 축약.
`.bts-cache/classify.json`은 멀티세션 충돌 이력이 있어 `--cache` 미사용. **본 plan이 분류의 진실 출처**.

**동시 진행 작업**. draft PR #277 (프로젝트 관리 CRUD, `.worktrees/project-management-crud`)이 별도
세션에서 domain 단계 진행 중. 본 작업과 파일 영역 교집합 여부는 spec 단계에서 확인.

## 선행 함정 (메모리 인계 — spec/plan 단계에서 전수 반영)

- `permitall-opens-preexisting-body-buffer-dos` — permitAll은 서명 검증 **전에** 힙에 본문을 적재.
  `@RequestBody String`이면 secret 없이도 미인증 DoS. #275의 `readBoundedSlackBody` 선례 확인 필요.
- `bearer-token-resolver-drains-form-body` — form POST에서 `access_token` 조회가 Tomcat 파싱을
  트리거해 바디가 빈 채로 도달. GitLab/GitHub 페이로드 형식(json vs form-encoded) 확인 필수.
- `negative-guard-needs-body-discriminator` — "여전히 401"류 음성 가드는 vacuous. 위반을 주입해
  fail 확인.
- `prod-assembly-boot-verification-required` — cross-BC `@Component` 추가 시 머지 전 rebase +
  `:modules:app:test` 9BC prod 조립 재검증.
- `preseeded-event-producer-activates-notifications` — 신규 이벤트 발행 전 enum/시드 grep.
- `no-backend-ci-and-assembly-merge-verification-traps` — 백엔드 CI 부재. 로컬 검증이 유일 관문.

## 도메인 정리

> **조사 방식**. PR-A(#274/#275)·PR-B(#276)가 이미 F1~F9 도메인 조사 + D1~D6·DEC-11~14를 확정했으므로
> **재조사가 아니라 "선행 PR 머지 후 그 전제가 아직 유효한가"의 재실측**. Explore 3종 병렬
> (마이그레이션·카운트가드 / SecurityConfig·서명검증 / PR-B산출물·동시PR충돌). 아래는 **코드에서 확인된
> 사실**만 — 전부 파일:줄 인용 대조 완료.

- **BC**. automation (`com.bts.automation`, 9번째 모듈, JdbcTemplate)
- **BC 격리 예외 2건** (D5 확정 — plan §리스크에 사유 명시 필수).
  `identity-access`(SecurityConfig permitAll 등록) · `app`(prod 조립 HTTP 테스트).
  shared-kernel `SecretEncryptor` import는 automation이 이미 `com.bts.shared.issue.*`를 쓰므로 위반 아님.
- **신규 용어**. `PR_MERGED` 트리거 (glossary §트리거 하위 — 등재 필요).
  ~~`GitWebhookEvent`~~ — product doc D1 표기이나 **D1 결정이 "신규 트리거 + 전용 엔드포인트"로 확정**되어
  별도 도메인 엔티티 개념이 불필요해짐. glossary 미등재 유지, spec에서 최종 확인.
- **기존 결정 충돌**. SDD 8.8이 `webhook.received/source:github` + `pr.target_branch_version`(미정의 개념)로
  기술 — **SDD 8.2:22의 `pr.merged`와 모순**. D1/D4 결정에 맞춰 **8.8 정정이 본 PR 몫**
  (PR-B가 `08-automation-engine.md`에 "PR-C 범위로 아직 미구현인 개념 스케치" 주석을 남겨 예약해 둠).

### G1. ★ 마이그레이션 — 스펙의 "V306~V309"는 이미 무효. **V307부터**

| 사실 | 근거 |
|---|---|
| automation 최신 = **V306** (PR-B가 사용, ActionType CHECK 4→5) | `V306__automation_actions_set_fix_versions.sql:15-19` |
| V307~V399는 저장소 어디에도 없음 (진행 중 worktree 2개 포함 전수 검색 0건) | 실측 |
| 대역 충돌 없음 — 다음 대역 시작 V400 | 8개 BC 대역 실측 지도 |
| **V306이 CHECK DROP→ADD 재발행 선례**를 남김 (V302 원본 편집 금지 = 체크섬 드리프트) | `V306:7-8` 주석 |

`trigger_type` CHECK 제약은 **`V300__automation_rules.sql:36-38` 한 곳뿐** (V301~V306에 재정의 없음).
→ PR-C는 V307에서 DROP→ADD 6종. 스키마 추가(git webhook 등록 테이블)와 CHECK 확장은 **논리적 별개**라
V307/V308 분리 검토 (모듈 관례 = 1파일 1스키마 변경, 대역 여유 92개).

⚠️ **`DATA.md:85-94` V번호 표가 8행 중 7행 stale** (automation은 아예 `(예정) —`, 실제 V300~V306).
기존 문서 부채 — surgical 원칙상 automation 행만 갱신할지 spec에서 확정.

### G2. ★★ `TriggerType` 미강제 13지점 — **액션(PR-B)보다 구조적으로 훨씬 취약**

**컴파일러가 잡아주는 곳은 단 1곳** — `TriggerConfig.kt:43-47` (else 없는 exhaustive `when`).
**나머지 13지점은 전부 `==`/`!=` if-비교 → `PR_MERGED` 추가해도 조용히 통과.**

> **★★ 2026-07-17 교정 — 아래 원래 표의 "1순위/2순위" 판정은 틀렸다** (Phase B 백엔드 적대적 검토가
> 코드로 반증, spec §B-2). **`TriggerMatcher`·`AutomationEventWorker`는 PR_MERGED와 무관하다.**
> `q_automation_events`는 **issue-tracking이 소유한 이슈 이벤트 전용 큐**(`AutomationEventWorker.kt:20`
> KDoc)이고 issue-tracking은 GitHub PR 머지를 알 수 없다 → **Git 웹훅은 이 큐에 올라갈 수 없다**.
> PR_MERGED는 컨트롤러가 룰을 직접 조회해 동기 enqueue하는 **제3의 경로**(spec §3.5).
> → `TriggerMatcher` wire 맵에 `pr.merged`를 넣으면 **아무도 발행하지 않는 죽은 코드**가 된다.
> 원래 표를 근거로 작성했던 완료 기준(§9-6 "wire 맵 회귀 가드")도 **폐기**.

| 위험도 | 위치 | 누락 시 증상 | 교정 후 판정 |
|---|---|---|---|
| ~~1순위~~ | `TriggerMatcher.kt:44-46` wire→enum 맵 | ~~웹훅 도착해도 무발화~~ | **해당 없음** — PR_MERGED는 이 경로를 안 탐. **추가 금지**(죽은 코드) |
| ~~2순위~~ | `TriggerMatcher.kt:73` `if (== ISSUE_UPDATED) ... else emptySet()` | ~~targetBranch 필터 무력화~~ | **해당 없음** — targetBranch는 컨트롤러가 인메모리 매칭 |
| **1순위(신)** | `RuleConflictAnalyzer.kt:244-245` | PR_MERGED가 조용히 `true`(동시매칭)로 fall-through → **targetBranch 다른 두 룰에 상시 오탐 경고가 REST 응답에 노출** (S5가 정확히 그 시나리오) | **BLOCKER B4-be — `targetBranchCoFire` 추가 필요** |
| **2순위(신)** | `AutomationRuleService.kt:210,890` `if (== WEBHOOK) mintWebhookToken(...) else null` | PR_MERGED가 `else null` | **`else null`이 정답** — git 토큰은 프로젝트 단위 `git_webhooks` 소유. **"변경 불요"를 spec에 명시**해 구현자의 오추가 방지 |
| 그 외 | `RuleConflictAnalyzer.kt:112,121,243` · `AutomationEventWorker.kt:142` · `AutomationRuleService.kt:212,523,892,1000` | 개별 판정 | 대부분 무관 — 개별 확인 |
| **표에 없던 것** | `AutomationRulesYaml.kt:63`(YAML DTO 기본값) · `AutomationExecutionWorker.kt:437`(`valueOf` 제네릭) | 둘 다 **무해** | 두 검토자가 각각 발견 |

> **★ "13"은 틀렸다 — 세 번째 재현** ([[spec-stated-count-becomes-blindfold]]).
> 라인 단위로 세면 **16줄**이고(맵 리터럴 3줄·when절 쌍 2줄을 각 1 site로 묶어야 13), 두 적대적 검토가
> 각각 표에 **없는** 지점을 하나씩 더 찾았다. PR-B가 "미강제 2개 → 실제 3개"로 남긴 교훈을 인계받아
> 적었는데도 **같은 자리에서 또 틀렸다**. → **impl·리뷰는 이 표의 개수를 세지 말고 패턴 grep으로 직접 확인**.

**프론트는 반대로 타입시스템이 강제** (`tsconfig.app.json` strict + noFallthroughCasesInSwitch).
`automation-rules.types.ts:230-243` switch(default 없음) · `AutomationRuleFormDialog.tsx:69` +
`AutomationRuleList.tsx:53` `Record<TriggerType, string>` → 컴파일 에러로 잡힘. **단 예외 1곳** —
`RuleExecutionTraceRow.tsx:53-59`는 `Record<string, string>` + `:62-64` 원문 fallback → **타입 에러 없이
UI에 "PR_MERGED" 영문 노출**.

> **★ PR-B 교훈 인계** ([[spec-stated-count-becomes-blindfold]], `2026-07-16-...-pr-b-fix-version.md:777`).
> *"스펙이 '미강제 지점 2개'라고 개수를 적었고, 구현·검증·1차 리뷰가 그 개수를 그대로 물려받았다"* (실제 3개).
> → **위 13이라는 숫자도 물려받지 말고 impl·리뷰가 패턴 grep으로 재검증**한다.

### G3. 카운트 가드 — 사고 재현 위험 **없음** (실측으로 기각)

전 모듈(`backend/**` + `apps/web/**`) 카운트 단언은 **2개뿐, 둘 다 automation 내부**.
- `TriggerConfigTest.kt:17` `TriggerType.entries.size shouldBe 5` → **PR-C가 6으로 갱신** (+`:15` describe, `:21-29` 집합 단언)
- `ActionTest.kt:18` — PR-B가 이미 5로 맞춤, PR-C 무관

[[enum-add-breaks-crossmodule-count-guard]]의 "타 모듈 가드까지 깨짐"은 **이번엔 해당 없음** —
`app`·`issue-tracking`·`shared-kernel`이 `TriggerType`을 전혀 참조하지 않음(grep 0건, enum이 BC 내부 캡슐화).
프론트에도 `.options.length` 류 카운트 단언 0건.

### G4. ★ `SchemaMigrationTest`가 `PR_MERGED`를 무효값 프로브로 사용 중

`SchemaMigrationTest.kt:559-563` — `assertThatThrownBy { insertRule("PR_MERGED") }`.
**PR-C가 PR_MERGED를 유효화하는 순간 이 테스트는 반드시 실패** → 프로브를 다른 무효값으로 교체.
짝 테스트 `:553-557`(유효 5종 INSERT 허용) + `:551` 주석 + `:554` 테스트명도 6종 갱신.
(스펙 C-h가 "559-562"라 적었으나 실측 **559-563**.)

### G5. ★ `SLACK_INBOUND_PATHS`는 2곳이 아니라 **3곳**을 구동한다

스펙은 "permitAll + CSRF-ignore 양쪽"이라 적었으나 실측 **3곳** (`SecurityConfig.kt:290` KDoc이 명시).

| 구동처 | 줄 | 누락 시 |
|---|---|---|
| bearer token resolver **skip** | :115-116 | **form POST에서 Tomcat 파싱이 본문 소진 → 컨트롤러가 빈 바디로 401** |
| CSRF-ignore | :152-154 | POST 403 |
| permitAll | :206-208 | 401 |

**★ GitHub 웹훅은 설정에서 `application/x-www-form-urlencoded`를 고를 수 있다** →
[[bearer-token-resolver-drains-form-body]]가 PR-C에 **그대로 적용**. 3곳 누락 증상이 전부 "401"이라 구분 불가.

기타 확정.
- PR-C의 두 경로(`/api/v1/webhooks/git/*`, `/api/v1/automation/webhooks/*`)는 **모두 `/api/**` 하위**
  → `SecurityConfig.kt:209 requestMatchers("/api/**").authenticated()` **보다 위**에 등록 필수(:205 주석이 계약 명시)
- **CSRF는 `antMatcher(method, path)` 필수** — `ignoringRequestMatchers`에 `(HttpMethod, String)` 오버로드가
  없어 문자열 오버로드를 쓰면 **메서드 고정이 조용히 사라지고 컴파일·테스트 모두 통과**
- SecurityConfig에 `automation` 문자열 **0회 등장** — 현재 automation 웹훅은 `:210`에서 401
- `@Suppress("LongMethod")`(:91) 이미 부착 + 임계 1줄 초과 기록(:88-90) → 줄 추가 시 detekt 재확인
- 클래스 KDoc `:44-52`의 permitAll 경로 목록도 동기화 대상

### G6. prod 조립 HTTP 테스트 — 베이스 **상속만** 허용

`ProdAssemblyHttpTestBase.kt` (`app` 모듈, PR-A 산출물). `@SpringBootTest(RANDOM_PORT)` + `@ActiveProfiles("prod")`
+ `TestRestTemplate`. **하위에서 `@SpringBootTest`/`@ActiveProfiles`/`@DynamicPropertySource` 재선언 금지** —
`webEnvironment`가 컨텍스트 캐시 키라 분열 시 **9-BC prod 컨텍스트 2회 부팅 + `@Scheduled` 워커 2벌이 동일
5433 pgmq 큐 동시 폴링**(:37-43 KDoc). PR-C의 신규 프로퍼티(git webhook secret)는 **베이스 `props()`(:67)에 추가**가 유일.

- 사전조건. dev postgres 기동(`docker compose -f infra/docker-compose.dev.yml up -d postgres`, 5433). Testcontainers 미사용
- `--tests ProdAssemblyHttpTestBase*` **금지** (abstract → "No tests found" 빌드 실패)
- **양성 단언 패턴 재사용** — `SlackInboundPermitAllTest.kt:186-215`가 검증기와 무관하게 HMAC을 **직접 재계산**해
  200 + 본문 에코를 받음 = 필터 통과와 서명 검증 통과의 **동시 증명**. 경로별 개별 단언(뭉뚱그리면 매처 오타 미검출)
- **음성 가드 vacuous 함정** ([[negative-guard-needs-body-discriminator]]) — `:140-145`가 위반 주입으로 실증:
  permitAll이 새도 컨트롤러가 **같은 401** → 판별자는 **응답 본문**.
  PR-C는 유리 — automation 컨트롤러가 `ProblemDetail`에 `AUTOMATION_WEBHOOK_NOT_FOUND` 등을 싣고 필터 401은 빈 본문

### G7. 서명 검증 — slack 선례의 4계약 + **GitHub의 구조적 차이**

`SlackSignatureVerifier.kt`(147줄) 계약 4종. ① 미설정=거부(fail-open 금지, 부팅은 통과·검증 시점 거부)
② 예외 아닌 **boolean 수렴**(헤더누락·형식오류·윈도우초과·불일치·미설정 전부 `false`, 컨트롤러가 401 매핑)
③ `MessageDigest.isEqual` **상수시간 비교**(`:86-89`) ④ **서명 대상은 원문 ByteArray, String 왕복 금지**
(`:119-121` — 비-UTF8 바이트가 U+FFFD로 치환돼 HMAC 변조 + 힙 복사본 증폭). base string은 문자열 조립이 아니라
`mac.update()` 스트리밍(`:123-132`).

**★ GitHub과의 구조적 차이 (이식 불가)**. `X-Hub-Signature-256`은 `sha256=hex(HMAC(secret, rawBody))` —
**base string에 timestamp가 없다** → slack의 ±300초 replay 윈도우(`:79`, `REPLAY_WINDOW_SECONDS=300L`)를
**구조적으로 이식할 수 없음**. 스펙 C-6 결론(replay 방어 주장 삭제 + 잔여위험 ADR 명시)이 실측으로 재확인됨.
`X-GitHub-Delivery`는 **서명 대상 밖**이라 dedup은 "정직한 재시도 방어"일 뿐 replay 방어가 아님.

- `Clock` 주입 시 기본값 필요 여부 — slack은 모듈에 `Clock` 빈이 없어 `= Clock.systemUTC()` 기본값 부여(`:53-57`).
  **automation 모듈의 `Clock` 빈 존재 여부 확인 필요**(GitHub은 timestamp를 안 쓰므로 불요일 수도)

### G8. secret 저장 — `SecretEncryptor` 타입 빈은 **3개** (4개 아님), automation이 4번째

**정정**. `mfaSecretEncryptor`는 **다른 타입**(`MfaSecretEncryptor`, `Encryptors.stronger` 자체 복제) →
shared-kernel `SecretEncryptor` 타입 빈은 `oidcSecretEncryptor`·`webhookSecretEncryptor`·`slackSecretEncryptor` **3개**.

**최신·최완성 선례 = `SlackEncryptionConfig.kt:29-58`** — ① `@Bean("이름")` by-name 고정(타입 중복이므로 필수)
② `@param:Value("\${$PROPERTY_KEY:}")` **빈 기본값**(미설정 부팅 통과) ③ 프로퍼티 키를 companion 상수로.
주입은 `@param:Qualifier("...")` (Kotlin use-site target — 신규 코드는 이 표기).
기존 3개 소비처 전부 `@Qualifier` 보유 확인 → 4번째 추가해도 `NoUniqueBeanDefinitionException` 위험 없음.

- **F6 재확인**. `automation_rules.webhook_token_hash`는 SHA-256 **비가역** → HMAC secret 저장 **불가**(재사용 불가 확정)
- **git webhook 등록 테이블 부재 확인** — automation 4테이블(rules/actions/conditions/rule_executions) 어디에도 없음. **신규 필요**
- **저장 선례**. `outbound_webhooks.secret_encrypted`(`V603:6,12` — AES-256-GCM 암호문만, 원문 비저장) ·
  `oidc_provider_configs.client_secret_encrypted`(`V011:54`)

### G9. `.env.prod.example` — 배치 원칙 = 필수는 활성 / 선택적 연동은 주석

PR-A가 §암호화 키 섹션(`:43-64`)에 MFA·OIDC·webhook **3종 활성**. slack 키는 §Slack 연동(`:66-78`)에
**주석 처리** — 즉 원칙은 "필수 3종 활성 / 선택적 연동은 해당 기능 섹션에 주석". 총 4종.
→ git 웹훅 secret이 **필수인지 선택인지**에 따라 배치가 갈림 (spec 확정 대상. G12-4 참조).

- salt는 **반드시 hex**(`Encryptors.stronger`가 hex 디코드) — `openssl rand -hex 32`
- **nginx 변경 불요** — `nginx.conf:40`이 `^/(api|...)`라 `/api/v1/webhooks/git/*` 이미 포함
- compose가 `env_file: ./prod/.env` 전체 주입 → `.env.prod.example` 추가만으로 결선 완료

### G10. 하류 방어심층 — FR-C7이 **조건 없는 룰**에도 걸려야 한다

`ActionExecutor.kt:270-275 extractIssueKey`가 `triggerEvent.issueKey`를 **무검증 신뢰** 확인
(`rule.projectKey` 대조 코드 **없음**). 값은 `dispatchAction`(:137) → `attemptIssueMutation`(:230-249) →
포트로 `rule.actorUserId` 권한과 함께 흘러감. **폭발 반경 = 룰 actor 권한**.

부분 방어 1건 — `isConditionUnmet`(:170-189)이 `issueSnapshotPort.fetch(rule.createdBy, issueKey)`(:177)로
작성자 가시성 검사. **그러나 조건이 설정된 룰 한정** — 조건 없는 룰은 `:176`에서 `return@runCatching true`로
게이트를 그냥 통과. → **FR-C7(룰 projectKey ≠ 이슈키 prefix → SKIPPED)은 조건 유무와 무관하게** 걸려야 함.

`buildContext`(:278-291)도 payload 기반이라 발신자가 `{{issue.*}}`·`actor.id` 조작 가능(`:70-74` KDoc이
"설계상 한계"로 기록). 스펙 C-7 `buildContext(C-k)` 후속 한계 명시와 정합.

### G11. `trigger_config` 파싱 — `targetBranch`가 동형 복제할 3계층

- **`triggerConfig`는 JSONB가 아니라 `String`** (`AutomationRule.kt:51`). DB만 JSONB, 도메인은 JSON 문자열.
  `TriggerMatcher`는 `object` 싱글턴 + **자체 `ObjectMapper()`**(`:49`, Spring 빈 아님)로 매 호출 `readTree`
- ① **검증** `TriggerConfig.kt:82-95 validateIssueUpdated` — 선택(`?: return`)·null 허용·배열·비어있지 않은 문자열
- ② **매칭** `TriggerMatcher.kt:73` → `:78` 교집합. 추가 필터 `AutomationEventWorker.kt:142`
- ③ **프론트** `automation-rules.types.ts:233-237`(`omitManagedKeys`) + `AutomationRuleFormDialog.tsx:393`
- **★ 부재/빈 = 전체 발화**(`TriggerMatcher.kt:93`)라는 **관대한 기본값** — targetBranch에 그대로 적용하면
  "미지정 = 전 브랜치". 스펙 C-5의 "미지정 = 전 브랜치(하위호환)"와 일치하나, **B8이 지적한
  "다중 릴리스 제품의미 붕괴"의 기본값이 관대한 쪽**임을 인지할 것
- `serializeTriggerConfig`의 `baseConfigJson` 병합(`:214-217`)이 미인지 키를 보존 → `targetBranch`를
  **`omitManagedKeys`에 등록**해야 빈 값 처리가 `fields`와 동형
- malformed triggerConfig는 예외 처리 없이 `processMessage`의 `catch`(:99)로 → vt 만료 재전달 →
  `readCt > 5`면 archive (dead-letter)

### G12. 동시 PR #277 — **코드 충돌 0. FR 카운트 축만 겹침 → 안 건드리면 0**

실측. `git diff origin/main...backend/project-management-crud --name-only` → **plan 문서 1개뿐**
(249 insertions). 두 브랜치 merge-base = `552ef6a5a` = 현 origin/main HEAD. 양쪽 worktree clean.

| 영역 | 판정 |
|---|---|
| automation 모듈 | **겹침 없음** (#277이 "건드리지 않아도 되는 이유"로 인용만) |
| SecurityConfig | **겹침 없음** (#277 plan에 `SecurityConfig`/`permitAll` 0 hit — 그쪽은 인가(권한코드), 이쪽은 인증(필터체인)) |
| Flyway V번호 | **겹침 없음** (automation V3xx ↔ issue-tracking V0xx 네임스페이스 분리) |
| **FR 총수** | **★ 겹침 확정** — #277이 FR 5개 신설(FR-PJ-01~04 + FR-PM-10, 신규 프리픽스) → **123→128** |

**→ 방어책. PR-C는 FR 카운트 파일을 아예 안 건드린다** (FR 불변 123이므로 실제로 불필요).
`fr-index.md` 합계 · `README.md` 합계 · `CLAUDE.md 123 FR` **전부 미변경** → 충돌 0.
`fr-index.md:5`의 stale `122` 표기도 **#277이 이미 독립 발견 후 자기 PR에 통합 선언** → **PR-C는 손대지 말 것**.

⚠️ **감시 1건**. #277 plan `:65`의 *"automation 시간 기반 트리거의 409 실패 노이즈 → automation 조기 skip"* —
구현되면 automation 모듈 파일이 열림. #277이 spec/plan 단계로 갈 때 재확인.

### G13. PR-C가 실제로 동기화할 문서 — 생각보다 적다

`verify-master-plan.sh`(175줄) 게이트 A~G 전수 확인 결과. **정본 `PLAN_COUNT`는 실집합에서 자동 산출**
(`:43-44`) — 손으로 세지 않음. **BC 6/7→7/7은 verify 미검사** — `docs/progress.html`은
`build-dashboard.mjs`가 체크박스에서 **자동 산출**(`:47-48`, `:300`) → **수동 편집 금지, 재생성만**.

| 대상 | 작업 |
|---|---|
| `product/automation.md` §2.7 | D1~D7 7개 `[ ]` → `[x]` |
| `product/automation.md:146` | `→ automation BC **6/7 유지**` → 7/7 완결 |
| `product/automation.md:130-146` | PR-C 완료 메모 (PR-B가 `:143-145`에 "PR-C 완료 시점에 마킹"을 예약해 둠) |
| `product/automation.md` §NFR `:163-164` | BC 완료 조건 + 측정표 |
| `README.md:110` | automation 행 진척 `☐` |
| `docs/progress.html` | `node scripts/build-dashboard.mjs` **재생성** (post-merge 훅 상시고장 → 수동 `--no-verify`) |
| SDD `08-automation-engine.md` §8.8 | `webhook.received` → `pr.merged`, `pr.target_branch_version` → 명시 versionId |
| **미변경** | `fr-index.md` 합계 · `README` 합계 · `CLAUDE.md 123 FR` (123 불변 + #277 충돌 회피) |

**★ 유지해야 할 표기** — `automation.md:5 소속 FR. 7개 (AT 7)` · `:22 ## §2 자동화 규칙 (FR-AT, 7개)`.
게이트 D/F'가 실집합 7과 대조하므로 **변경하면 오히려 fail**.

### G14. 기타 갱신 대상 (실측 중 발견)

- **`AutomationWebhookController.kt:34-37` KDoc이 거짓이 됨** — *"자동화 모듈은 아직 배포 조립·중앙
  SecurityConfig 결선이 없으므로… prod SecurityConfig 결선은 후속 ADR(모듈 전조립 시점) 범위다"*.
  조립은 #259에서 완료(`app/build.gradle.kts:57`), **결선이 바로 이 PR** → 갱신 필수
- **`AutomationTestSecurityConfig.kt:49`가 `/**` 와일드카드** — 중앙 등록은 `PUBLIC_DASHBOARDS_PATH`·
  `ICAL_FEED_PATH` 원칙대로 `/*` 단일 세그먼트여야 함 → **divergence 발생**. PR-A ADR `:151`이
  *"automation을 빼면 C-4 divergence도 소멸"*이라 했으나 **PR-C가 되살림** → test config를 `/*`로 정합화.
  같은 파일 `:47 csrf { it.disable() }`이라 **BC 테스트는 중앙 CSRF-ignore 누락을 원리적으로 못 잡음**
  (= app 모듈 조립 테스트가 유일한 관문)
- **`AutomationWebhookController` 처리 순서 결함** — 토큰 조회(`:95`)가 payload 파싱(`:93`) **뒤**라
  유효 토큰 없이도 256KB 파싱 비용 발생. PR-C 신규 컨트롤러는 **조회 선행** 검토
- **`sha256Hex`가 `private` top-level 함수**(`AutomationWebhookController.kt:224-227`) → 같은 파일 밖에서
  재사용 불가. git webhook 토큰 조회가 같은 방식이면 복제 또는 가시성 조정 필요
- **`readBoundedBody` 반환 계약 2종 병존** — automation은 **예외**(`:110-116`), slack `SlackInboundBody.kt:41-50`은
  **null 수렴**(automation을 선례로 명시하되 반환만 변경). PR-C가 어느 쪽을 따를지 결정 필요
- `RuleConflictAnalyzer.kt:330-337 hasObservableSideEffect`의 `||` 불리언 체인 exhaustive 전환은
  **PR-C 범위 밖 미해결 후속** (`pr-b-fix-version.md:766`)

### 관련 ADR

`2026-07-10-fr-at-01-automation-triggers` · `2026-07-11-fr-at-02-automation-actions` ·
`2026-07-11-automation-prod-assembly`(F2 부채 출처) · `2026-07-15-slack-inbound-permitall-central`(PR-A,
§1.4 정식 예외 선례) · `2026-07-16-fr-at-07-pr-b-fix-version-port`(PR-B) ·
`2026-06-10-version-status-and-transitions` · `2026-05-22-issue-key-prefix-policy`.
**본 PR 신규 ADR 필요** — §1.4 정식 예외(automation·git 인바운드 permitAll) + GitHub replay 잔여위험 +
GitLab 보안등급 차이(DEC-13).

### Obsidian 갱신 대기 (Maxi 승인 필요)

- `glossary.md` — `PR_MERGED` 트리거 (§트리거 하위)
- `domain/automation.md` — **stale 확인**. "ANTLR 4로 AQL 파서"라 기재하나 실제는 손수 파서(F9 기록).
  본 PR 범위 밖 별건 정리 후보. 핵심 엔티티에 Trigger 6종 반영은 본 PR 몫

## 스펙

전체 스펙. [docs/specs/2026-07-17-fr-at-07-pr-c-git-webhook.md](../specs/2026-07-17-fr-at-07-pr-c-git-webhook.md)
(마스터 `2026-07-15-fr-at-07-pr-merge.md` §C의 상세화 — §C가 확정한 FR-C1~C12·BLOCKER 9 해소책은 재논의 대상 아님)

**핵심 3줄 요약.**
- **PR 머지가 자동화 규칙을 발화시키는 마지막 경로를 잇는다** — PR-A(도달 가능화)·PR-B(설정 통로) 위에
  `POST /api/v1/webhooks/git/{token}`(서명 검증) + `TriggerType.PR_MERGED`를 얹어 S1을 완성
- **PR_MERGED는 기존 이벤트 큐를 타지 않는 제3의 경로** — 컨트롤러가 룰을 직접 조회해 동기 enqueue
  (`q_automation_events`는 issue-tracking 소유라 Git 웹훅이 올라갈 수 없음)
- **PR-C = 백엔드만**. D6/D7(UI·E2E)은 PR-D. FR-AT-07 완료·BC 7/7 마킹도 PR-D 몫. **FR 123 불변**

**범위 밖 명시**. UI · rate limit · pgmq 아카이브 보존 배치 · 프로젝트당 룰 수 상한 · GitHub replay 방어(구조적 불가)

## Brainstorming Check

✅ **통과 (2회 iteration)**. 1회차 = 적대적 검토 2종 병렬(security + backend, 실코드 대조) →
**BLOCKER 7 / CONCERN 12 / NIT 7**. 2회차 = 전건 반영 + Maxi 확정 DEC-22~24.

**1회차가 잡아낸 가장 큰 것 3가지** (전부 내 오류).
1. **B2-be** — 내가 쓴 완료 기준(§9-6)이 **죽은 코드를 만들라고 지시**했다. 위 G2가 `TriggerMatcher`
   wire 맵 누락을 "1순위 조용한 실패"로 단정했으나 **그 경로 자체가 PR_MERGED와 무관**. 실측을 해놓고
   해석을 틀렸고 스펙이 그대로 물려받았다 → G2 교정 + spec §3.5 신설
2. **B3-sec** — 마스터 스펙의 "구분 대상 = **로그·메트릭**" 문맥을 잘라내 "반드시 구분"만 남겨,
   **404를 포기하면서까지 막은 존재 오라클을 응답 errorCode로 부활**시켰다
3. **B1** — 마스터 §C-4의 확정 3개 중 **1개만** 가져왔다(절단·곱 상한 소실). **두 검토자가 독립적으로
   같은 지적**. 20키 상한만으론 비율만 줄고 무한성은 그대로

**메타 교훈**. 스펙 §9-5가 *"'13'을 물려받지 말고 직접 재검증하라"*고 **스스로 경고하고도** G2 표 자체가
불완전했다(라인 단위 16 + 미기재 2건). [[spec-stated-count-becomes-blindfold]] **세 번째 재현**.

## Plan

> **★ 절대 규칙 §1.4 정식 예외 작업.** `DEVELOPMENT.md §1.1` **규칙 4 "인증 없는 엔드포인트 추가 금지.
> Spring Security 필터 우회 금지."** 를 정면으로 건드린다. 경로군 **2개**(git 신규 + automation 기존)를
> 연다. 선례 절차 = **ADR + 게이트1 승인 + KDoc 예외 사유 명시**(FR-DB-03 익명 대시보드 · FR-CA-02 iCal ·
> PR-A slack 4경로). T1이 그 ADR. **DEC-22 — 승인은 게이트1에서 Maxi가 명시적으로 준다.**
>
> **프레이밍 (PR-A ADR D2 승계 + 차이 명시)**. permitAll은 인증을 **없애는** 게 아니라 **검증 주체를
> 필터 → 컨트롤러로 옮기는** 것이다. **git 경로는 HMAC이 그 자리에 선다. 그러나 automation 웹훅에는
> 서명 검증이 없다**(불투명 토큰 소지 = 인증) → **동일 논거를 automation에 그대로 쓸 수 없다**. T1이 이
> 차이와 잔여위험을 정직하게 기술한다.
>
> **writing-plans 미호출 (deviation)**. 스펙이 이미 task 수준으로 상세하고(§3.5 파이프라인 9단계 ·
> 함정 30여 건이 파일:줄로 고정) 범용 도구는 BTS 고유 함정을 모른다. PR-A·PR-B 동형. 스킬이 요구하는
> 메타 블록(agent/files/depends-on) 형식은 준수.
>
> **task 17개 — 스킬 기준(10) 초과, Maxi 확정 "한 PR"**. 분할하면 PR-B처럼 "만들었지만 안 도는" PR을
> 하나 더 만든다. **S1이 끝까지 도는 것을 검증 가능한 최소 단위**가 이 PR.

### Wave 구조 (bts-impl이 메타로 자동 계산 — 아래는 예상)

```
W1 (8-병렬): T1 ADR · T2 마이그레이션 · T3 TriggerType파급 · T5 encryptor
             T7 서명검증기 · T8 이슈키추출 · T13 방어심층 · T16 프론트Zod
W2 (3-병렬): T4 충돌분석(←3) · T6 도메인/Repo(←2) · T14 회귀테스트(←2,3)
W3 (3-병렬): T9 Service(←6,7,8) · T11 등록API(←6) · T18 dedup정리배치(←6)
W4 (직렬)  : T10 Controller(←7,8,9)
W5 (직렬)  : T12 SecurityConfig(←10,11)   ← BC 격리 예외, DEC-22 승인 전제
W6 (2-병렬): T15 prod 조립 HTTP(←12) · T17 문서(←1,12)
```
longest path = T2 → T6 → T9 → T10 → T12 → T15 (**6 wave**).
> ★ **wave는 Gradle 모듈 컴파일도 직렬화한다** ([[bts-plan-wave-gradle-module-compile]]) — 같은 모듈
> 동시 수정 시 컴파일 충돌. automation 모듈 task가 많아 실제 wave는 더 좁아질 수 있음.
> ★ **병렬 dispatch pre-commit race** ([[parallel-dispatch-precommit-hook-race]]) — 각 implementer는
> **자기 files만 stage**. `git stash` 금지([[subagent-git-stash-worktree-shared-collision]]).
> ★ `./gradlew ktlintFormat` **금지** — 타 task 파일까지 포맷 ([[bts-ktlintformat-docs-commit-traps]]).

---

### Task 1. ADR — git·automation 인바운드 permitAll (§1.4 정식 예외) + 잔여위험 3종

**메타**.
- agent: `security-engineer`
- files: [`docs/decisions/2026-07-17-git-webhook-inbound-permitall.md`]
- depends-on: []

**내용** (TDD 비대상 — 문서).
- **맥락**. PR-A(#274)가 slack 4경로를 열며 automation을 **일부러 제외**(DEC-15) — `AutomationWebhookController:97`이
  무검증 `issueKey`를 enqueue하기 때문. 본 PR이 그 방어심층(FR-C13)과 함께 automation + git을 연다
- **§1.4 예외 정당화**. (a) 외부 시스템(GitHub/GitLab)이 호출하므로 BTS 자격증명 불가 (b) git 경로는
  **컨트롤러의 HMAC 검증**이 인증 담당 (c) 필터가 막으면 서명 검증 코드가 실행조차 안 됨 = 기능 정지
  (d) 폭발 반경은 메서드 고정 + `/*` 단일 세그먼트로 봉인
- **★ automation의 프레이밍 차이 별도 기술** (C5-sec) — automation 웹훅은 **서명 검증이 없다**.
  "필터가 비키는 자리에 더 강한 검증이 선다"는 PR-A 논거가 **성립하지 않는다**. 토큰 소지가 인증이며,
  이는 `PublicDashboardController` 직교 토큰 선례와 같은 등급
- **★ 잔여위험 3종 정직하게 등재**.
  1. **FR-C13은 cross-project만 막는다** — 같은 프로젝트 내 임의 이슈 조작은 **여전히 가능**
     (`ActionExecutor.kt:176`이 조건 없는 룰을 게이트 없이 통과). PR-A DEC-15 우려가 **완전 해소되지 않음**.
     근본 해소는 전 트리거 동작 변경이라 별도 PR
  2. **GitHub replay 구조적 불가** — 서명에 timestamp 없음(`X-GitHub-Delivery`는 서명 대상 밖).
     slack ±300초 윈도우 대응물 없음. dedup은 **정직한 재시도 방어일 뿐**
  3. **GitLab 보안등급 차이** (DEC-13) — 평문 `X-Gitlab-Token`. GitLab이 HMAC을 제공하지 않아
     **우리가 더 강하게 만들 수 없다**. **GITHUB과 동급으로 서술 금지**
  4. rate limit 부재 — **본 PR로 등급 상승**(CPU 소모 → 디스크 고갈, §10)
- **표기**. `§1.4` (저장소 관례 14곳과 일관 — PR-A DEC-17)
- **폴더**. `docs/decisions/` ([[bts-adr-dual-folder-convention]])
- **선례 링크**. `2026-07-15-slack-inbound-permitall-central` · `2026-07-02-fr-db-03-dashboard-share` ·
  `2026-07-09-fr-ca-02-ical-export`

**검증**. `bash scripts/verify-master-plan.sh`. ★ T1 자체 검증에 T15 산출물 참조 금지(T1 시점 미실행).

---

### Task 2. V307/V308/V309 마이그레이션

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/automation/src/main/resources/db/migration/automation/V307__git_webhooks.sql`, `backend/modules/automation/src/main/resources/db/migration/automation/V308__git_webhook_deliveries.sql`, `backend/modules/automation/src/main/resources/db/migration/automation/V309__automation_rules_pr_merged_trigger.sql`]
- depends-on: []

**내용** (TDD — T14가 스키마 테스트).
- spec §6 그대로. **V309는 COMMENT 재발행 필수** (C4-be — `V300:67`이 "CHECK 5종" 문구,
  `V306:21-24` 선례). **V300 원본 편집 금지**(체크섬 드리프트)
- **V번호 실측 근거** (G1) — automation 최신 V306, V307~V399 저장소 전체 0건, 다음 대역 V400
- ★ **머지 직전 V번호 재확인** ([[migration-vnumber-concurrent-branch-collision]])
- ★ `:modules:app:test`는 **5433 영속 DB** ([[app-test-persistent-db-migration-checksum-trap]]) — 적용 후 편집 금지

**검증**. `./gradlew :modules:automation:test --tests SchemaMigrationTest`

---

### Task 3. `TriggerType.PR_MERGED` + 파급 전수 + `TriggerConfig` targetBranch 검증

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/domain/TriggerType.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/domain/TriggerConfig.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/domain/TriggerConfigTest.kt`]
- depends-on: []

**RED**. `TriggerConfigTest` — PR_MERGED가 `targetBranch` 타입/공백을 거부. `entries.size shouldBe 6`.

**GREEN**.
- `TriggerType.PR_MERGED` 추가 (+ `:1` 헤더 주석 "5종"→"6종")
- **★ `TriggerConfig.kt:43-47`에 PR_MERGED 전용 분기 신설** — `:46`의 `-> Unit` 그룹에 얹으면
  **컴파일 통과 + 무검증** → `targetBranch: 123`·오타 키 저장 → 관대한 기본값으로 **B8 부활** (C6-sec).
  `validateIssueUpdated`(`:82-95`) 동형 — 선택(`?: return`)·문자열·비공백
- **★ 파급 전수 grep** ([[spec-stated-count-becomes-blindfold]]) — `grep -rn "TriggerType\."
  backend/modules/automation/src/main`. **plan G2 표의 개수를 세지 말 것**(라인 16 + 미기재 2 전력)
- **★ `TriggerMatcher` wire 맵에 `pr.merged` 추가 금지** (§3.5 — 죽은 코드)
- **★ `AutomationRuleService.kt:210,890` 변경 금지** (C3-be) — `else null`이 정답

**REFACTOR**. KDoc — PR_MERGED가 제3의 경로임을 명시(구현자 오해 차단).

**검증**. `./gradlew :modules:automation:test --tests TriggerConfigTest`

---

### Task 4. `RuleConflictAnalyzer.targetBranchCoFire` (B4-be)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/application/RuleConflictAnalyzer.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/application/RuleConflictAnalyzerTest.kt`]
- depends-on: [3]

**RED**. targetBranch 다른 두 PR_MERGED 룰 → **경고 0건**. 같거나 한쪽 미지정 → 경고 발생.

**GREEN**. `coFire`(`:238-247`)에 `fieldsCoFire` 동형 `targetBranchCoFire` 분기.
> **왜 BLOCKER인가**. 현재 `a.triggerType != ISSUE_UPDATED -> true`(`:245`)라 PR_MERGED가 무조건
> "동시 발화 가능". **S5(release/1.2 + release/2.0)가 정확히 그 시나리오**이고
> `AutomationRuleController.kt:118,240,282`가 `conflicts`를 **REST 응답에 실어 사용자에게 노출** →
> 이 기능이 지향하는 사용 패턴에서 **거짓 경고**.

**검증**. `./gradlew :modules:automation:test --tests RuleConflictAnalyzerTest`

---

### Task 5. `automationSecretEncryptor` 빈 + `.env.prod.example`

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/AutomationEncryptionConfig.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/AutomationEncryptionConfigTest.kt`, `infra/prod/.env.prod.example`]
- depends-on: []

**RED**. 키 미설정 시 **빈 등록은 성공**(부팅 통과) + `encrypt` 호출 시 `IllegalStateException`.

> **★ 경로 주의 (eng-review 실측)** — automation 모듈에 **`config/` 디렉토리가 없다**.
> `AutomationSchedulingConfig.kt`가 패키지 루트(`com/bts/automation/`)에 있는 게 이 모듈 관례.
> `config/` 하위에 만들면 관례 이탈.

**GREEN**. **`SlackEncryptionConfig.kt:29-58` 그대로 복사** (G8 — 최신·최완성 선례).
- `@Bean("automationSecretEncryptor")` **by-name 고정** (타입 빈 4개가 됨)
- `@param:Value("\${$PROPERTY_KEY:}")` **빈 기본값** — `@ConditionalOnProperty` 금지(부팅 파괴)
- 프로퍼티 키를 companion 상수로 (`bts.automation-encryption.{key,salt}`)
- ★ `mfaSecretEncryptor`는 **다른 타입** — SecretEncryptor 타입 빈은 현재 3개(G8 정정)
- `.env.prod.example` — **DEC-19: 선택적 연동** → 파일 끝에 **주석 처리 신규 §Git 웹훅** (slack `:67-78` 동형).
  salt는 **hex** (`openssl rand -hex 32`)

**검증**. `./gradlew :modules:automation:test --tests AutomationEncryptionConfigTest`

---

### Task 6. `GitWebhook` 도메인 + Repository

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/domain/GitWebhook.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/domain/GitProvider.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/GitWebhookRepository.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/adapter/GitWebhookRepositoryTest.kt`]
- depends-on: [2]

**RED**. Testcontainers — `findByTokenHash`가 `deleted_at IS NULL`만 반환. dedup INSERT 충돌 판정.

**GREEN**. JdbcTemplate (automation 관례). `AutomationRuleRepository:248` 동형.

**검증**. `./gradlew :modules:automation:test --tests GitWebhookRepositoryTest`
> ★ 신규 `@Repository`가 test-boot 컨텍스트를 깰 수 있음
> ([[new-bc-first-repository-testboot-context-regression]]) — automation은 기존 Repository가 있어 위험 낮으나 확인.

---

### Task 7. 서명 검증기 (GitHub HMAC / GitLab 평문)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/security/GitWebhookSignatureVerifier.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/security/GitWebhookSignatureVerifierTest.kt`]
- depends-on: []

**RED**. 유효 서명 true / 위조 false / 헤더 누락 false / **secret blank false** / **교차 헤더 false**(EC2·EC3) /
레거시 SHA-1 false(EC4).

**GREEN**. **`SlackSignatureVerifier.kt` 4계약 승계** (G7).
- ① **미설정=거부** (`:72-74` — ★ 1회차가 인용한 `:64-66`은 **stale**, #275로 이동)
- ② **예외 아닌 boolean 수렴** — 컨트롤러가 401 매핑
- ③ **`MessageDigest.isEqual` 상수시간** (`:86-89`)
- ④ **raw ByteArray, String 왕복 금지** (`:119-132` — `mac.update` 스트리밍)
- **★ replay 윈도우 이식 불가** — GitHub 서명에 timestamp 없음. `Clock` 주입 **불요**
- **★ provider는 등록행에서만** — 헤더 추론·폴백 금지 (C-a)
- **KDoc에 GitLab 등급차 명시** (DEC-13)

**검증**. `./gradlew :modules:automation:test --tests GitWebhookSignatureVerifierTest`

---

### Task 8. 이슈 키 추출기

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/application/PrIssueKeyExtractor.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/application/PrIssueKeyExtractorTest.kt`]
- depends-on: []

**RED**. `Closes PROJ-42` 추출 / 키워드 없는 `PROJ-42` **미추출** / **`Closes PROJ-42x`·`PROJ-420` 오추출 0**(EC16) /
소문자 `proj-42` 미추출 / distinct.

**GREEN**. spec §3.4 정규식. **★ 뒤 단어 경계 `(?![A-Za-z0-9-])` 필수** (C2-be — 1회차는 프로즈로만 경고).
**★ `IssueKey.REGEX` 값 복제 + 주석 명시** (`AtlasIssueUrlParser.kt:56` 선례 — BC 격리로 import 불가).

**검증**. `./gradlew :modules:automation:test --tests PrIssueKeyExtractorTest`

---

### Task 9. `GitWebhookService` — 파이프라인 + 3중 상한 + dedup + 단일 트랜잭션

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/application/GitWebhookService.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/application/GitWebhookServiceTest.kt`]
- depends-on: [6, 7, 8]

**RED**. S1 발화 / S3 스코프 위반 0건 / S4 머지 아님 0건 / S5 targetBranch / **EC12 20키·곱100 초과 → 0건** /
**팬아웃 payload `pr.title`·`pr.body` ≤ 2KB** / **EC10 일부 실패 → 전량 롤백(dedup 포함)**.

**GREEN**. spec §3.5 ⑤~⑨ + §3.7 + §3.10 스키마.
- **★ 단일 `@Transactional`** (DEC-23) — `AutomationExecutionEnqueuer.kt:22-25` KDoc이
  "호출자 트랜잭션 안에서 원자적 커밋" 보장. self-invocation 무관(컨트롤러→서비스)
- **★ `TriggerMatcher`/`AutomationEventWorker`/`q_automation_events` 미사용** (§3.5)
- **★ triggerEvent 최상위에 `title`/`body` 두지 말 것** (§3.10) — `buildContext:279`가 `issue` 키 부재 시
  triggerEvent 전체를 issue로 취급 → `{{issue.title}}`이 PR 제목으로 오염. `pr` 하위로 내려 C-k 회피
- **★ 3중 상한** — 20키 / **각 2KB 절단** / **룰×키 100** (B1 — 두 검토자 독립 지적)

**검증**. `./gradlew :modules:automation:test --tests GitWebhookServiceTest`

---

### Task 10. `GitWebhookController`

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/web/GitWebhookController.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/adapter/web/GitWebhookControllerTest.kt`]
- depends-on: [7, 8, 9]

**RED**. 202 / **401 단일 errorCode**(EC1~EC4·EC9·EC15) / 413(EC5) / 415(EC6) / 400(EC7) /
**핸들러 시그니처 화이트리스트**(§9-7).

**GREEN**. `AutomationWebhookController:102-116` 동형.
- **★ `@RequestBody` 금지** — `readNBytes(MAX+1)` + `contentLengthLong` 이중검사
- **★ `@RequestParam`/`@ModelAttribute` 병용 금지**
- **★ 401 응답 본문 단일화** (B3-sec) — `GIT_WEBHOOK_UNAUTHORIZED`. **사유 구분은 로그·메트릭만**.
  errorCode가 갈리면 **404를 포기하며 막은 존재 오라클이 부활**
- **★ 토큰 조회를 payload 파싱보다 먼저** (G14 — 기존 컨트롤러의 순서 결함 답습 금지)
- `consumes = APPLICATION_JSON_VALUE` (415 명시 거부)
- EC9 — **ERROR 로그**(id·projectKey만, 평문/키 금지) + 메트릭

**검증**. `./gradlew :modules:automation:test --tests GitWebhookControllerTest`

---

### Task 11. 등록 API + secret 검증

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/web/GitWebhookRegistrationController.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/web/dto/GitWebhookDtos.kt`, `backend/modules/automation/src/main/kotlin/com/bts/automation/application/GitWebhookRegistrationService.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/adapter/web/GitWebhookRegistrationControllerTest.kt`]
- depends-on: [6]

**RED**. 201 + **토큰 1회 노출** / GET에 token·secret **미포함** / **blank·15자 secret 거부**(B4-sec) /
**비권한자 403** / **미인증 401**.

**GREEN**. `AutomationRuleController.kt:64-67,104` 동형.
- **★ 가드 순서** — `AutomationActorExtractor.extract()`(401) → `assertManageAutomationPermission`(403) →
  조회 ([[auth-extraction-before-resource-lookup]])
- **★ 동형 복제 시 가드 전수 대조** ([[isomorphic-clone-permission-guard-gap]])
- **★ secret `@field:NotBlank` + 최소 16** (B4-sec — GITLAB 평문 비교라 빈 secret은 토큰만으로 우회)
- 토큰 = `SecureRandom` 256bit base64url, **SHA-256 해시만 저장** (`AutomationRuleService:773-777` 동형)
- `MANAGE_AUTOMATION` — 기존 코드 재사용, **신규 권한코드 없음**
  ([[fr-pm-permission-seed-migration-test-coupling]] 무관)

**검증**. `./gradlew :modules:automation:test --tests GitWebhookRegistrationControllerTest`

---

### Task 12. `SecurityConfig` — git + automation 인바운드 **3곳** 등록

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/config/SecurityConfig.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/AutomationTestSecurityConfig.kt`]
- depends-on: [10, 11]

> **★ BC 격리 예외** — identity-access를 건드림. **security-engineer 필수**. DEC-22 게이트1 승인 전제.

**GREEN**.
- **★ 3곳 전부** (G5 — `SecurityConfig.kt:290` KDoc이 경고) — bearer skip(`:115-116`) +
  CSRF-ignore(`:152-154`) + permitAll(`:206-208`). **1회차 FR-C15는 permitAll만 언급했다**(B2-sec)
- **★ GitHub form-urlencoded** → bearer skip 없으면 Tomcat 파싱이 본문 소진 → **빈 바디 401**
  ([[bearer-token-resolver-drains-form-body]])
- **★ `:209 /api/**` authenticated보다 위** (`:205` 주석이 계약 명시)
- **★ csrf는 `antMatcher(method, path)`** — 문자열 오버로드는 메서드 고정이 조용히 사라지며 통과
- **★ `/*` 단일 세그먼트** — `/**` 금지
- 공유 리스트 확장 — **`SLACK_INBOUND_PATHS` 이름이 거짓이 됨** → `INBOUND_WEBHOOK_PATHS` 리네이밍
  검토(NIT-be, 리뷰 확인)
- **클래스 KDoc `:44-52` 동기화** (N4-sec — "permitAll 6경로" 목록)
- **`AutomationTestSecurityConfig.kt:49` `/**` → `/*` 정합화 + git 경로 추가** (G14·NIT-be).
  ★ `:47 csrf { it.disable() }`이라 **BC 테스트는 중앙 CSRF 누락을 원리적으로 못 잡음** → T15가 유일 관문

**REFACTOR**. KDoc `§1.4 정식 예외(ADR 2026-07-17-... · 게이트1 승인)` (PR-A DEC-17 표기).
★ 기존 `§1.4` 14곳은 건드리지 않음(surgical).

**검증**. `./gradlew :modules:identity-access:test :modules:slack-integration:test :modules:automation:test`
회귀 0. ★ detekt — `@Suppress("LongMethod")` 임계 이미 1줄 초과(`:88-90`).

---

### Task 13. FR-C13 방어심층 — `ActionExecutor.execute` 내부

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/application/ActionExecutor.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/application/ActionExecutorTest.kt`]
- depends-on: []

**RED**. 룰 projectKey ≠ 이슈키 prefix → **SKIPPED**. **★ 조건 없는 룰에도 걸림**. **★ replay 경로에도 걸림**.

**GREEN**. **`ActionExecutor.execute` 내부** (DEC-24 — C1-sec).
> **왜 워커가 아닌가**. `execute` 호출자는 **3곳** — `AutomationExecutionWorker.kt:230` ·
> **`RuleExecutionService.kt:160`(동기 replay)** · 미래. `RuleExecutionService.kt:57` KDoc이
> *"워커의 루프 가드를 거치지 않는다"* 명시 → **워커에 넣으면 replay가 무방비**. 오염된
> `rule_executions` 행(웹훅으로 심어진 `OTHER-1` triggerEvent가 `V305:22`에 **영구 보존**)을 관리자가
> replay하면 cross-project 변경 재발.
- **★ 조건 유무 무관** — `:176`(`?: return@runCatching true`)이 조건 없는 룰을 게이트 없이 통과(G10)
- **★ 이건 automation 웹훅(TriggerType.WEBHOOK) 경로에도 적용** — FR-C15의 전제
- ★ `extractIssueKey`는 **`:271-275`** (1회차 인용 `:264-268`은 stale — N3-sec)

**검증**. `./gradlew :modules:automation:test --tests ActionExecutorTest`

---

### Task 14. `SchemaMigrationTest` — 프로브 교체 + 유효 6종 + 신규 테이블 단언

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/test/kotlin/com/bts/automation/SchemaMigrationTest.kt`]
- depends-on: [2, 3]

> **★ `TriggerConfigTest.kt`는 T3 소유** — 이 task가 건드리지 않는다(파일 겹침 = 병렬 충돌).
> `:15`·`:16`·`:17`·`:20`·`:21-29`(5종→6종 카운트·집합·it 문구)는 **전부 T3의 RED/GREEN에 포함**.

**내용**.
- **★ `:561` `insertRule("PR_MERGED")` 프로브를 다른 무효값(예: `"NOT_A_TRIGGER"`)으로 교체** (G4) —
  PR_MERGED가 유효해지는 순간 **이 테스트는 반드시 실패**한다. 스펙 C-h가 예견했고 실측이 확인
- `:553-557` 유효 **6종** 리스트 + `:551` 주석("5종 화이트리스트") + `:554` 테스트명
- **V307/V308 신규 테이블 스키마 단언** (T2 검증) + V309 CHECK 6종 + **COMMENT 문구**(C4-be)
- ★ 테이블 카운트 가드 부재 확인됨(G3) → 신규 테이블이 기존 테스트를 깨지 않음

**검증**. `./gradlew :modules:automation:test --tests SchemaMigrationTest`

---

### Task 15. prod 조립 HTTP 테스트 — 양성·음성·오라클·DB쓰기0·위반주입

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/app/src/test/kotlin/com/bts/app/ProdAssemblyHttpTestBase.kt`, `backend/modules/app/src/test/kotlin/com/bts/app/GitWebhookInboundPermitAllTest.kt`]
- depends-on: [12]

> **★ 이 PR의 유일한 진짜 관문.** BC test config는 csrf disable이라 중앙 CSRF 누락을 못 잡는다.

**RED→GREEN** (T12가 GREEN을 만듦 — RED는 T12 이전 상태 = 401).
- **★ 베이스 상속만** (G6) — `@SpringBootTest` 재선언 시 컨텍스트 분열(9-BC 2회 부팅 +
  `@Scheduled` 워커 2벌이 5433 pgmq 동시 폴링). **신규 프로퍼티는 베이스 `props()`(:67)에 추가**
- **양성 (git)** — HMAC **직접 재계산** → **202** (`SlackInboundPermitAllTest:186-215` 동형).
  3곳 + 서명 검증 동시 증명
- **★ 양성 (automation)** (B2-sec) — FR-C15 경로도 **동일하게** 202 실증
- **★ 음성 — 판별자는 응답 본문** ([[negative-guard-needs-body-discriminator]]).
  `SlackInboundPermitAllTest:140-145`가 위반 주입으로 실증한 대로 **상태코드만으론 vacuous**
- **★ 오라클 부재 실증** (B3-sec) — **EC1 본문 == S2 본문** (timestamp 제외)
- **★ 서명 미검증 요청은 DB 쓰기 0** (C3-sec) — 서명 틀린 요청 N회 후 `git_webhook_deliveries` **행 수 불변**
- **★ 위반 주입** ([[archunit-vacuous-rule-silent-pass]]) — permitAll 목록에서 git 경로 빼고 **fail 확인 후 되돌림**.
  **automation 경로도 별도 수행**
- EC5 413 실서블릿. **MockMvc 금지**
- ★ 사전조건 — `docker compose -f infra/docker-compose.dev.yml up -d postgres` (5433)
- ★ `--tests ProdAssemblyHttpTestBase*` **금지** (abstract → "No tests found")

**★ NFR-1 측정 (eng-review 이슈 2 — Maxi 확정 2A)**.
1회차 마스터 스펙의 **BLOCKER B9가 정확히 이 실수**였다("200ms를 하드 넘버로 적고 검증 항목 0건").
스펙 §9-21이 요구했으나 **어느 task도 자기 일로 적지 않아** 반쪽만 고친 상태였다 → **T15가 책임진다**.
- **DB 왕복 수 단언** — 토큰조회 1 + dedup INSERT 1 + 룰조회 1 + enqueue N×M
- **p95 실측 기록** → T17이 `product/automation.md §NFR` 측정표의 `Webhook 응답 200ms` 빈칸(`___`)을 채움
- **★ 팬아웃 상한 100의 실측 검증 (eng-review 이슈 3 — Maxi 확정 3C)**.
  `AutomationExecutionEnqueuer.enqueue`는 **단건 API**(`jdbcTemplate.queryForObject` 1건 + `log.info`
  1줄/호출, batch 없음) → 룰×키 100이면 **DB 왕복 100회 + 로그 100줄**. 단일 호스트 docker라 ~50ms로
  끝날 수도 있어 **추측하지 않고 잰다**. **200ms 초과 시 같은 PR에서 대응**(상한 하향 또는 pgmq
  `send_batch` 도입) — 후속으로 미루지 않는다

**검증**. `./gradlew :modules:app:test`

---

### Task 16. 프론트 Zod 계약 동기화

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/automation-rules.types.ts`, `apps/web/src/components/automation/AutomationRuleFormDialog.tsx`, `apps/web/src/components/automation/AutomationRuleList.tsx`, `apps/web/src/components/automation/RuleExecutionTraceRow.tsx`, `apps/web/src/api/automation-rules.types.test.ts`]
- depends-on: []

**RED**. `triggerTypeSchema`가 `PR_MERGED` 파싱. `serializeTriggerConfig`가 `targetBranch` 직렬화.

**GREEN**.
- `triggerTypeSchema:13-19` + `:11` 주석 "5종"→"6종"
- `:230-243` switch — **컴파일러가 강제**(strict + noFallthroughCasesInSwitch)
- `:233-237` **`omitManagedKeys`에 `targetBranch` 등록** (미등록 시 `baseConfigJson` 병합이 빈 값 보존 → `fields`와 비동형)
- `AutomationRuleFormDialog.tsx:69` + `AutomationRuleList.tsx:53` `Record<TriggerType,string>` — **타입 에러로 강제**
- **★ `RuleExecutionTraceRow.tsx:53-59`만 `Record<string,string>`** → **타입 에러 안 남, 조용히 "PR_MERGED"
  영문 노출** (G2). 수동 추가 필수
- `:273 validTypes` 배열 (테스트 하드코딩)
- ★ 인라인 mock 파급 grep ([[zod-schema-strengthen-inline-mock-fanout]])
- **UI 없음** — 폼 필드(targetBranch 입력)는 **PR-D**. 계약만 (PR-B 동형)

**검증**. `pnpm typecheck && pnpm test`

---

### Task 17. 문서 동기화

**메타**.
- agent: `backend-engineer`
- files: [`docs/plan/product/automation.md`, `docs/sdd/08-automation-engine.md`, `DATA.md`, `backend/modules/automation/src/main/kotlin/com/bts/automation/adapter/web/AutomationWebhookController.kt`]
- depends-on: [1, 12]

**내용**.
- `product/automation.md` §2.7 — **D1/D2/D4/D5만 `[x]`**. D6/D7·FR-AT-07 완료는 **PR-D** (DEC-18).
  **BC 6/7 유지** (FR-AT-06 선례 동형). PR-C 완료 메모 추가
- **SDD `08-automation-engine.md` §8.8 정정** (F8) — `webhook.received/source:github` → **`pr.merged`**
  (8.2:22와 정합) / `pr.target_branch_version`(미정의) → **명시 versionId** (D4) /
  `conditions: "Closes PROJ-N 패턴 매칭"` → **이슈키 추출은 웹훅 수신부 책임**(조건 모델로 표현 불가)
- **`DATA.md:90`** — `automation (예정) | V300~V399 | —` → **`V300~V309`** (DEC-21 — automation 행만)
- **★ `AutomationWebhookController.kt:34-37` KDoc 갱신** (G14) — *"prod SecurityConfig 결선은 후속
  ADR 범위"*가 **거짓이 됨**. 조립은 #259 완료, **결선이 이 PR**
- **★ 미변경** — `fr-index.md` 합계 · `README` 합계 · `CLAUDE.md 123 FR` (123 불변 + #277 충돌 회피, G12/G13)
- **★ 유지** — `automation.md:5 소속 FR. 7개` · `:22 §2 (FR-AT, 7개)` (게이트 D/F'가 실집합 7과 대조 → 변경 시 fail)

**검증**. `bash scripts/verify-master-plan.sh` + `node scripts/build-dashboard.mjs` 재생성

---

### Task 18. dedup 보존 배치 — `git_webhook_deliveries` 7일 정리

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/automation/src/main/kotlin/com/bts/automation/worker/GitWebhookDeliveryCleanupWorker.kt`, `backend/modules/automation/src/test/kotlin/com/bts/automation/worker/GitWebhookDeliveryCleanupWorkerTest.kt`]
- depends-on: [6]

> **eng-review 이슈 1 (Maxi 확정 1A)**. 스펙 §3.8이 "보존 7일 + 삭제 배치"를 요구했으나 **T1~T17 어디에도
> 없었다**. T2가 정리 배치용 `ix_git_webhook_deliveries_received_at` 인덱스를 만들면서 정작 그 인덱스를 쓸
> 배치가 없는 상태였다. **정리 없으면 무한 증식** — 공격자 없이 정직한 트래픽만으로도 누적된다.
> (이 저장소는 pgmq 아카이브·`rule_executions` 보존 배치가 이미 0건 — 같은 부채를 하나 더 쌓지 않는다.)

**RED**. 7일 경과 행 삭제 / 7일 이내 행 보존 / 삭제 0건이어도 정상 종료.

**GREEN**. `@Component` + `@Scheduled` (기존 워커 동형).
- **★ 결선 실측 완료 (eng-review)** — `AutomationSchedulingConfig.kt:26-27`이 명시. prod 조립 앱
  `BtsApplication`이 **전역 `@EnableScheduling`**을 보유하고 워커는 순수 `@Component`라 **스캔되는 순간
  폴링이 켜진다** → `@EnableScheduling` 신규 결선 **불요**
  ([[module-first-scheduled-worker-detektmain-traps]]의 "모듈 첫 워커" 상황이 **아님**)
- **★ 테스트는 `@Scheduled` 메서드를 직접 호출** — `:20-23`이 명시한 이 모듈 관례. 테스트 컨텍스트는
  `@ConditionalOnProperty(bts.automation.scheduling.enabled)` 기본 OFF라 자동 폴링을 기대하면 안 되고,
  기대하면 셋업과 경쟁해 **flaky**가 된다
- `Clock` 주입 (기존 관례 — 시간 의존 테스트 결정론)
- ★ detekt `detektMain` type-resolved 엄격 ([[module-first-scheduled-worker-detektmain-traps]])

**검증**. `./gradlew :modules:automation:test --tests GitWebhookDeliveryCleanupWorkerTest`

---

## Plan 메타

- **task 수**. **18** (17 + eng-review 이슈 1의 T18). Maxi 확정 — 스킬 기준 10 초과이나 분할 시
  "안 도는 PR" 추가 생성
- **예상 wave**. 6 불변 (T18은 `depends-on: [6]` → W3에 흡수, longest path 무영향)
- **TDD 강제**. yes — 단 T1(ADR)·T2(SQL)·T17(문서)은 **TDD 비대상**. T14는 기존 테스트 교체,
  T15는 T12가 GREEN을 만드는 구조(RED = 현재 401 부채 상태)
- **BC 격리 예외**. identity-access(T12 SecurityConfig) · app(T15 조립 테스트) — DEC-22 게이트1 승인 전제
- **추가 검증**. ktlintCheck · detekt · `:modules:app:test`(9BC prod 조립) · pnpm typecheck/test
- **머지 전 필수**. `origin/main` rebase + `:modules:app:test` 재검증
  ([[prod-assembly-boot-verification-required]]) + V번호 재확인 + `verify-master-plan.sh`

## 리뷰 결과 (← /bts-review-plan 채움)
