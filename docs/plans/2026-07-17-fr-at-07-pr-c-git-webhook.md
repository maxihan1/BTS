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

| 위험도 | 위치 | 누락 시 증상 |
|---|---|---|
| **1순위** | `TriggerMatcher.kt:44-46` wire→enum 맵 리터럴 3종 | **Git 웹훅 도착해도 아무 룰도 발화 안 함 + 로그도 없음** |
| **2순위** | `TriggerMatcher.kt:73` `if (triggerType == ISSUE_UPDATED) parseFieldArray(...) else emptySet()` | targetBranch 필터가 조용히 `emptySet()` = 필터 무력화 |
| **3순위** | `RuleConflictAnalyzer.kt:244-245` | PR_MERGED가 조용히 `true`(동시매칭 가능)로 fall-through |
| 4순위 | `AutomationRuleService.kt:210,890` `if (triggerType == WEBHOOK) mintWebhookToken(...) else null` | PR_MERGED 토큰 발급 여부가 조용히 `null` |
| 그 외 | `RuleConflictAnalyzer.kt:112,121,243` · `AutomationEventWorker.kt:142` · `AutomationRuleService.kt:212,523,892,1000` | 개별 판정 |

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

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
