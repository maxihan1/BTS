# FR-AT-07 — PR 머지 연동 (Fix Version 자동 설정) · 백엔드 D1~D5

> slug: fr-at-07-pr-merge
> type: backend
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-15

## Brief

**사용자 원문**. `fr-at-07 진행해줘`

**작업 범위**. FR-AT-07(PR 머지 연동 — Fix Version 자동 설정)의 **D1~D5 백엔드**. UI(D6 Webhook URL 생성 페이지) + D7(E2E)은 **후속 PR로 분리**(Maxi 확정) — FR-AT-04·05·06 전부 이 방식으로 진행된 선례.

| 단계 | 내용 | 책임 |
|---|---|---|
| D1 | 도메인 — GitWebhookEvent | backend-engineer |
| D2 | 명세 — GitHub/GitLab Webhook 처리. 커밋 메시지에서 이슈 키 추출 | backend-engineer |
| D3 | 데이터 모델 — (활용. webhook secret 저장) | db-engineer |
| D4 | 백엔드 — `POST /api/v1/webhooks/git` + 서명 검증 | backend-engineer + security-engineer |
| D5 | 백엔드 테스트 — 가짜 페이로드 | backend-engineer |

**classify 결과**. type=backend / agent=backend-engineer / ~~primary_bc=issue-tracking~~ → **automation 정정**
 (classify가 "이슈키" 키워드로 issue-tracking 오판. product doc §2.7 소속은 automation.)

**우선순위**. 높음 | **선행**. §2.1 (WEBHOOK 트리거), §2.2 (액션) | **product doc Plan slug**. `automation/pr-merge`

**의의**. Phase 1의 **마지막 FR**. 완료 시 automation BC 6/7 → **7/7** (BC 완결), 전체 진척 122/123 → **123/123**.

## 열린 질문 (→ /bts-domain·/bts-spec에서 해소)

1. **cross-BC 경계**. Fix Version(`FR-VR`)은 issue-tracking 소유. automation이 이슈의 Fix Version을 설정 = BC 경계 통과. 기존 automation 액션이 이슈를 어떻게 건드리는지(포트 위임 / pgmq 이벤트) 관례 확인 필요.
2. **FR-AT-01 WEBHOOK 트리거와의 관계**. automation에 이미 WEBHOOK 트리거 + 토큰 체계 존재. Git webhook은 별도 엔드포인트인가, 기존 트리거의 특수 케이스인가.
3. **서명 검증 방식**. GitHub(`X-Hub-Signature-256`, HMAC-SHA256) vs GitLab(`X-Gitlab-Token`, 평문 비교) — 두 provider 모두 지원 범위인가.
4. **webhook secret 저장 위치**. D3이 "활용"이라 명시 — 신규 테이블 없이 기존 스키마 재사용 가능한지 확인.
5. **이슈 키 추출 규칙**. 커밋 메시지 / PR 제목 / PR 본문 중 어디까지, 다중 키 매칭 시 동작.
6. **엔드포인트 인증**. `/api/v1/webhooks/git`은 외부 Git 서버가 호출 → permitAll + 서명 검증. 중앙 등록 관례 확인(기존 부채 항목).

## 도메인 정리

> 조사 방식. Explore 에이전트 3종 병렬 (automation 모듈 / Fix Version·cross-BC 포트 / 서명검증·시크릿·permitAll). 아래는 **코드에서 확인된 사실**만. 결정은 §결정 사항에.

- **BC**. automation (`com.bts.automation`, 9번째 모듈, JdbcTemplate, Flyway V300~V399 — 현재 V305까지 사용)
- **classify 정정**. `primary_bc=issue-tracking` → **automation** ("이슈키" 키워드 오판)

### F1. 기존 WEBHOOK 트리거(FR-AT-01)의 실체

| 항목 | 사실 | 근거 |
|---|---|---|
| 엔드포인트 | `POST /api/v1/automation/webhooks/{token}` → 202 | `automation/adapter/web/AutomationWebhookController.kt:68,86` |
| 토큰 | `SecureRandom` 256bit → base64url 원문, **1회만 노출** | `AutomationRuleService.kt:773-777`, `:210` |
| 저장 | `automation_rules.webhook_token_hash VARCHAR(64)` — **SHA-256 해시(평문 미저장)** | `V300__automation_rules.sql:28,53-55` |
| 검증 | 수신 토큰을 해싱해 조회. 미존재/비활성/삭제 균일 404(존재 숨김) | `AutomationWebhookController.kt:95`, `AutomationRuleRepository.kt:248` |
| payload | `readTree` → `JsonNode` → pgmq `q_automation_execution` → `ActionExecutor` | `:119-124`, `AutomationExecutionEnqueuer.kt:55-62` |
| 상한 | 256KB 이중검사(Content-Length + `readNBytes`) | `:103-116`, `:206` |

**★ payload는 조건 평가에 직접 쓰이지 않는다.** payload에서 `issueKey`만 추출(`extractIssueKey` `:264-268` — 최상위 `issueKey` 또는 `issue.key`)해 `IssueSnapshotPort.fetch`로 **최신 스냅샷**을 받아 조건을 평가한다. payload는 템플릿 컨텍스트 전용(`buildContext` `:271-284`). issueKey 없으면 fail-safe `SKIPPED`.

### F2. ★ 인바운드 웹훅이 prod에서 전부 죽어 있다 (기존 부채, 문서화된 scope-out)

`SecurityConfig`(identity-access)의 permitAll 목록에 **automation·slack 인바운드 경로가 없다**. `anyRequest().authenticated()`(`SecurityConfig.kt:186`)에 걸려 **컨트롤러 도달 전 401**, POST는 CSRF 필터가 먼저 403.

- automation 웹훅 permitAll은 **테스트 전용** `AutomationTestSecurityConfig.kt:49`에만 존재
- slack 인바운드도 동일 — `SlackTestSecurityConfig.kt:64-67` (test 전용)
- **알려진 추적 항목**. `docs/plans/2026-07-11-automation-prod-assembly.md:53` — *"`AutomationWebhookController`가 조립되나 중앙 SecurityConfig 화이트리스트 미포함 → prod 401(FR-AT-01 WEBHOOK 트리거 **사문화**). slack `/slack/events`도 동일 미등록·후속 추적 중."* / `:143` D3=scope-out / `:165` *"인바운드 permitAll 중앙등록은 BTS의 알려진 BC별 배포-시점 후속 패턴"*
- **확장 포인트 없음**. 경로는 `SecurityConfig` companion object에 리터럴 하드코딩(`:209-260`). `PathContributor` 류 인터페이스·컬렉션 주입 grep 0건 → 이 구조가 부채의 원인
- 대조군. notification 익명 공유 GET은 **정상 등록**(`SecurityConfig.kt:180`, GET 고정 + 단일 세그먼트 매처 2겹 방어)

→ **FR-AT-07은 세 번째 인바운드 웹훅이다.** 중앙 등록 없이 머지하면 "Phase 1 마지막 FR"이 prod에서 동작하지 않는다. §결정 D2.

### F3. ★ Fix Version은 기존 포트로 설정할 수 없다

- **모델**. 컬럼이 아니라 **N:M 조인 테이블** `issue_fix_versions`(`V017__issue_version_links.sql`). 이슈는 fixVersion을 **복수**로 가짐(`Issue.fixVersionIds: List<UUID>` `Issue.kt:115`). 소프트삭제 없음(해제=행 DELETE)
- **설정 경로**. `IssueApplicationService.changeFixVersions(actor, key, AppChangeVersionsRequest{versionIds, expectedVersion})` (`:944-969`) — **전체 교체(replace) 시맨틱**(부분 add/remove 없음) + **OCC 필수**(`expectedVersion`, 불일치 시 409)
- **막는 지점**. `IssueMutationPort.setField`의 화이트리스트 6종에 fixVersions **없음** — `AutomationIssueMutationAdapter.kt:255-262` `SUPPORTED_FIELDS = {summary, description, priority, labels, environment, impact}`. `validateSupportedField`(`:202-204`)가 트랜잭션 전 거부
- **상수 추가만으론 불가(구조적)**. `setField`는 `updateIssue(UpdateIssueRequest)`로 위임하는데 fixVersions는 **다른 서비스 메서드**(`changeFixVersions`) 경로 → `buildUpdateRequest`의 `when`(`:218-240`)이 다른 메서드로 분기해야 함
- **조건으로도 참조 불가**. `Condition.FIELD_WHITELIST`(`Condition.kt:84-89`) 9종·`IssueSnapshot`(`:47-57`) 9필드 모두 fixVersions 미포함

### F4. cross-BC 포트 관례 (확립됨, ArchUnit 강제)

```
automation ──(port)──▶ shared-kernel ◀──(impl)── issue-tracking
```

| 항목 | 관례 |
|---|---|
| 인터페이스 위치 | **항상 shared-kernel** (`com.bts.shared.<도메인>`) |
| prod 어댑터 | **제공자 BC의 `adapter/outbound/<소비자명>/`** + `@Component @Profile("prod")` |
| 네이밍 | 인터페이스 `XxxPort` / 구현 `XxxAdapter` |
| 테스트 fake | **consumer-owns-stub** — 소비 BC의 `src/test`에 `StubXxxPort` |
| 쓰기 포트 | **fail-closed** — default 구현 없음 → 어댑터 부재 시 부팅 실패 |
| 계약 테스트 | `IssueMutationPortContractTest.kt:24` — 리플렉션으로 "default 없음" 검증 |
| shared-kernel 순수성 | Jackson 타입도 노출 안 함 → `SetFieldCommand.value: String?`(JSON 인코딩 문자열) |

automation→issue-tracking은 **이미 결선**. `ActionExecutor`가 `IssueMutationPort`(쓰기 3종) + `IssueSnapshotPort`(읽기) 주입(`:93,97`), 어댑터 2개 모두 `@Profile("prod")`.

`AutomationIssueMutationAdapter` 설계 포인트 — repository 직행 금지(유스케이스 위임으로 권한/OCC/이벤트 보존) · SecurityContext 안 읽고 `cmd.actorUserId` 신뢰(pgmq 워커 async) · `@Transactional` 대신 `TransactionTemplate`(OCC 재시도의 rollback-only 오염 회피) · dryRun=실행 후 `setRollbackOnly()` · OCC 충돌 시 1회 재시도.

### F5. 서명 검증 — 모범 사례는 있으나 slack 전용

`SlackSignatureVerifier.kt` — HmacSHA256, base string `v0:{ts}:{rawBody}`, **replay 방어 ±5분**(`:110-112`), **상수시간 비교** `MessageDigest.isEqual`(`:104-107`), `Clock` 주입, fail-closed(secret 미설정도 `false`→401).

- **재사용 불가 형태**. `SlackProperties`(slack 전용 signing secret)에 직접 결합. shared-kernel에 인바운드 검증 유틸 **없음**
- **★ raw body 함정**. `@RequestBody String`만 사용. `@RequestParam`/`@ModelAttribute` 병용 시 Spring이 form을 먼저 파싱해 스트림 소비 → `@RequestBody`가 **빈 문자열** → 서명검증 조용히 무력화. 서명 통과 후 수동 form-decode (`SlackCommandsController.kt:24-33,102-115`)
- **DoS 가드**. 서명검증 **이전에** 크기 상한 → 413 (commands 16KB / interactions 64KB)
- 아웃바운드 `WebhookSigner.kt`(search-export-import)는 이미 `sha256=` 접두(GitHub 관례)를 쓰지만 **`verify()` 없음**(sign 전용)

### F6. ★ 시크릿 저장 — D3의 "활용" 전제가 깨진다

HMAC 서명 검증은 **평문 secret**이 있어야 서명을 재계산할 수 있다. 그런데 기존 `automation_rules.webhook_token_hash`는 **SHA-256 해시(비가역)** — HMAC에 쓸 수 없다. 따라서 **기존 컬럼 재사용 불가**, 가역 암호화 저장이 필요.

BTS 관례 — `SecretEncryptor`(shared-kernel, `shared/crypto/SecretEncryptor.kt`, **AES-256-GCM** `Encryptors.stronger`, 매 호출 random IV, hex 출력). BC마다 **다른 키/salt로 도메인 격리** + 빈 이름 명시 + `@Qualifier` by-name 주입(같은 타입 빈 4개 조립 → 이름 충돌 방지).

| 빈 이름 | 프로퍼티 | 사용처 |
|---|---|---|
| `slackSecretEncryptor` | `bts.slack-encryption.{key,salt}` | 봇 토큰 |
| `oidcSecretEncryptor` | (동형) | OIDC client secret |
| `webhookSecretEncryptor` | (동형) | 아웃바운드 웹훅 |
| `mfaSecretEncryptor` | — | TOTP secret |

- 키 미설정 시 **빈은 항상 등록**(`@ConditionalOnProperty` 금지 — 부팅 파괴), `encrypt/decrypt` **호출 시점**에 `check(configured)` → `IllegalStateException` (교훈 [[use-time-validated-env-passes-boot-fails-on-use]])
- 예외 메시지에 평문/키 미포함이 계약. salt는 **hex**

### F7. 이슈 키 추출

- **정본 정규식**. `IssueKey.REGEX = "^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*$"` (`issue/domain/IssueKey.kt:31`) — prefix 2~10자, number 1 이상(0 불가)
- **본문 텍스트에서 이슈 키를 스캔하는 기존 코드는 없다.** 멘션 파서는 `@username` 전용, 링크(FR-LK)는 명시적 이슈 간 링크로 텍스트 파싱 안 함
- 유일한 선례는 **URL 파싱** — `slack/unfurl/AtlasIssueUrlParser.kt:57`. BC 격리로 `IssueKey.REGEX`를 **값 복제**하고 그 사실을 주석에 명시(`:56`). 끝 `$` 앵커 없음(trailing slash/query 처리 목적)
- → automation도 같은 값 복제 패턴을 따르게 됨(BC 격리상 issue-tracking import 불가)
- 관련 교훈 [[flexmark-inline-extension-anchor-text-loss]] — `find()`는 미앵커 시 텍스트 유실

### F8. SDD 8.8 ↔ product doc §2.7 명세 충돌

| 출처 | 명세 |
|---|---|
| `docs/sdd/08-automation-engine.md:146-162` | 트리거 `type: webhook.received, source: github, event: pull_request.merged` — **기존 WEBHOOK 트리거의 특수화**. 액션 `set-field / field: fix_version_ids / value: ["{{ pr.target_branch_version }}"]` |
| `docs/plan/product/automation.md:122-126` | D1 "도메인 — **GitWebhookEvent**"(신규 개념) · D4 "**`POST /api/v1/webhooks/git`**"(신규 전용 엔드포인트) |

**SDD 8.8은 그 밖에도 현행과 어긋난다** — 조건을 JSONLogic/SpEL로 기술(`8.3`)하나 실제는 손수 조건 트리 + `FIELD_WHITELIST`. `conditions: PR 본문에 "Closes PROJ-N" 패턴 매칭`은 **현행 조건 모델로 표현 불가**(FIELD_WHITELIST가 `issue.*` 9종 전용) → 이슈 키 추출은 **조건이 아니라 웹훅 수신부**의 책임이어야 함.
`pr.target_branch_version`(대상 브랜치 → 버전 매핑)은 **어디에도 정의된 바 없음**.

### F9. 기존 결정 충돌 / 신규 용어

- **관련 ADR**. `2026-07-10-fr-at-01-automation-triggers.md` · `2026-07-11-fr-at-02-automation-actions.md` · `2026-07-11-automation-prod-assembly.md`(F2 부채 출처) · `2026-06-10-version-status-and-transitions`(Version 상태) · `2026-05-22-issue-key-prefix-policy`
- **신규 용어 후보**. `GitWebhookEvent`(product doc D1 표기) — glossary 미등재. 채택 여부는 §결정 D1에 종속
- **Obsidian `domain/automation.md`가 stale**. AQL 파서를 "ANTLR 4"로 기재하나 실제는 손수 파서. 본 PR 범위 밖(별건 정리 후보)

## 결정 사항 (← Maxi 확정 필요)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
