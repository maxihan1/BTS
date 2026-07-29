# ADR — 조립 앱 비-prod 부팅 봉합 (조립 계층 한정)

- 날짜: 2026-07-29
- 상태: 수락 (Accepted)
- 관련: PR #321 · [[no-cross-bc-deployment-assembly]] · [[prod-assembly-boot-verification-required]]
- 선행 ADR: `2026-07-11-automation-prod-assembly`(§fail-closed 범위 정정 동반) ·
  `2026-06-04-system-admin-role`(L65 의도를 조립에서 실현) ·
  `2026-05-22-issue-permission-resolver-port`(스텁 제거 로드맵과 무충돌)

## 맥락 (Context)

배포 조립 앱 `:modules:app` 이 **기본(비-prod) 프로파일에서 부팅되지 않았다.** prod 는 정상이다.

각 BC 의 `@Profile("!prod")` 스텁은 *"identity-access 와 issue-tracking 은 각자 독립
`@SpringBootApplication` 이라 컨텍스트가 분리된다 … 충돌하지 않는다"* 는 전제로 설계됐다
(`DevAllowIssuePermissionResolver` KDoc). **`:modules:app`(#253)이 생기며 그 전제가 깨졌다** —
`com.bts` + `com.atlas.bts` 를 한 컨텍스트로 스캔하므로 두 벌이 함께 등록된다.

### 실측 — 9종 (양방향 전수 열거 + 실부팅 대조군)

**판별식.** 파일 단위 grep 은 KDoc 언급을 세어 과대 계상한다(`@Profile` 포함 main 파일 57개 중 22개가 주석-only).
클래스/오브젝트/`@Bean` **선언에 붙은** `@Profile` 만 세고 상위 타입으로 그룹핑해,
`!prod` 구현 ≥2(중복) **와** `prod` 전용 + 무조건 소비자(부재) 를 **양방향**으로 본다.

| 구분 | 타입 | 주입 지점 |
|---|---|---|
| 중복 | `IssuePermissionResolver` | 35 |
| 중복 | `SystemPermissionResolver` | 12 |
| 중복 | `ComponentPermissionResolver` | 6 |
| 중복 | `VersionPermissionResolver` | 3 |
| 중복 | `CustomFieldPermissionResolver` | 2 |
| 중복 | `TemplatePermissionResolver` | 2 |
| 부재 | `AutomationPermissionResolver` | 3 |
| 부재 | `IssueMutationPort` | 2 |
| 부재 | `IssueSnapshotPort` | 1 |

`@Primary` 는 main 전체에 0건, 충돌 타입에 `@Qualifier` 0건 — Spring 이 승자를 고를 방법이 없다.

**실증 대조군 (1회 부팅).** `CycleTimeService` 생성자 0번 파라미터에서
`IssuePermissionResolver` "expected single matching bean but found 2:
AlwaysAllowIssuePermissionResolver, DevAllowIssuePermissionResolver" → `APPLICATION FAILED TO START`.
DataSource·Flyway·Tomcat 은 정상 통과했으므로 장애는 **빈 결선 문제 단독**이다.

### 왜 여태 안 잡혔나 — 기존 가드가 구조적으로 못 본다

app 모듈의 테스트는 **전부** `ProdAssemblyHttpTestBase` 를 상속해 `@ActiveProfiles("prod")` 로 고정돼 있다.
`2026-07-11-automation-prod-assembly` §fail-closed 의 *"미충족 의존 0"* 선언도 본문 그대로
**"조립+prod 컨텍스트에서"** 로 한정돼 있었고, 그 아래 열거가
`AutomationPermissionResolver → IdentityAccessAutomationPermissionResolver(@Profile("prod")) ✓` 처럼
**prod 에서만 충족되는 항목에 ✓** 를 달았다. 그 ADR 을 무효화하지 않는다 — **검증 범위의 한계**를 여기서 정정한다.

## 결정 (Decision)

봉합은 **조립 모듈(`:modules:app`) 안에서만** 한다. 다른 9개 BC 의 `src/**` 는 0줄 변경이다.
(대안 B·C 는 §대안 참조.)

### D1 — 중복 6종: 조립 스캔에서 issue-tracking 스텁을 배제

`BtsApplication` 의 `excludeFilters` 에 `FilterType.ASSIGNABLE_TYPE` 필터를 추가하고
`AlwaysAllowIssuePermissionResolver` · `AlwaysAllowComponentPermissionResolver` ·
`AlwaysAllowCustomFieldPermissionResolver` · `AlwaysAllowTemplatePermissionResolver` ·
`AlwaysAllowVersionPermissionResolver` · `NonProdAllowSystemAdminResolver` **6개만** 넣는다.

**규칙 한 줄. 조립 컨텍스트에서 권한 리졸버의 출처는 언제나 identity-access 다.**
prod 실구현이 전부 identity-access 에 있고, `SystemPermissionResolver` 는 실구현이 identity-access ·
스텁이 issue-tracking 이라 선택지가 없다 — 6종이 같은 규칙 하나로 설명된다.

**`FilterType.REGEX` 를 쓰지 않는다.** 정규식은 클래스가 개명·이동하면 매칭이 조용히 풀려 중복이
되살아난다. `ASSIGNABLE_TYPE` + 실제 import 라야 개명이 **컴파일 에러**가 된다.

### D2 — ★ 배제 금지 2종 (봉합이 새 결함을 만드는 지점)

issue-tracking 의 `AlwaysAllow*`/`NonProdAllow*` 는 **8개**지만 중복은 **6개**다.
`AlwaysAllowFieldPermissionResolver`(`FieldPermissionResolver`) 와
`AlwaysAllowIssueSecurityDirectory`(`IssueSecurityDirectory`) 는 identity-access 짝이 `@Profile("prod")` 뿐이라
**자기 타입의 유일한 비-prod 구현**이다. 패턴으로 싸잡아 배제하면 곧바로 새 "빈 부재" 2종이 된다.
따라서 **와일드카드/정규식 배제를 금지**하고 클래스를 하나씩 열거한다.

### D3 — `SystemPermissionResolver` 는 비-prod 동작이 바뀐다 (수용)

스텁(`isSystemAdmin` 항상 true)이 빠지므로 비-prod 조립도 `system_role_assignments` /
`global_permission_grants` 를 **실제로 조회**해 판정한다. 이는 `2026-06-04-system-admin-role` L65 의
*"판정기는 단순 DB 조회라 `@Profile` 분리가 불필요 — 모든 프로파일에서 실제 판정한다"* 를 조립에서 실현한 것이다.
시드가 없으면 프로젝트 생성 등이 막히는데, **계정·프로젝트 시드는 별건**으로 추적한다.
이 PR 의 책임은 "부팅된다" 까지다.

### D4 — 부재 3종: 스텁이 아니라 **prod 실구현**을 비-prod 에 등록

`app` 모듈에 `@Configuration @Profile("!prod")` 인 `NonProdAssemblyPortConfig` 를 신설하고
`IdentityAccessAutomationPermissionResolver` · `AutomationIssueMutationAdapter` ·
`AutomationIssueSnapshotAdapter` 를 `@Bean` 으로 생성한다. 실구현의 `@Component` 는 `@Profile("prod")` 라
**상호 배타** — 어느 프로파일에서도 활성 빈은 정확히 1개다.

`IssueMutationPort`/`IssueSnapshotPort` 는 권한 게이트가 아니라 **기능 통로**다. 스텁을 두면 dev 에서
자동화가 조용히 no-op 이 되고, 손검증하는 사람이 그것을 새 고장으로 오인한다 — 이 PR 의 목적
("실 백엔드 손검증 경로를 연다")을 깬다.

**생성자 변경 취약성은 의도한 성질이다.** 조립 모듈이 다른 BC 구현의 생성자를 직접 호출하므로
생성자가 바뀌면 **빌드가 막힌다**. 스텁이었다면 시그니처가 갈라져도 조용히 굴러갔을 것이다.

### D5 — BC 격리 규칙의 명시적 예외

`CLAUDE.md §핵심 패턴` 은 BC 간 직접 import 를 금지한다. **조립 모듈은 그 예외다** — 9 BC 를 한 컨텍스트로
결선하는 것이 이 모듈의 유일한 직무이고, `BtsApplication` 이 이미 `IdentityAccessApplication` ·
`IssueTrackingApplication` 을 import 하는 선례가 있다. 이 ADR 이 그 예외를 성문화한다.

### D6 — 재발 방지 가드는 **실부팅** + **별도 JVM** + **CI 배선**까지가 범위

1. `NonProdAssemblyBootTest` — `@ActiveProfiles` 미지정(기본 프로파일), `webEnvironment = RANDOM_PORT`.
   `NONE` 이면 서블릿·시큐리티 자동설정이 backoff 되어 웹 계층 결함을 통과시킨다.
2. `@Tag("nonprod-assembly")` + 전용 `nonProdAssemblyTest` Gradle 태스크(= 별도 JVM).
   `@ActiveProfiles`·`webEnvironment` 는 **컨텍스트 캐시 키의 일부**라, 같은 JVM 에 두면 9-BC 컨텍스트가
   두 벌 뜨고 `@Scheduled` 워커도 두 벌이 같은 pgmq 큐를 동시 폴링한다.
   태그 분기는 `tasks.withType<Test>` **한 블록**에 모은다(두 블록이면 선언 순서에 의존해 조용히 덮인다).
   `mustRunAfter(test)` 로 순서를 고정한다.
3. 단언은 컨텍스트 로드로 끝내지 않고 **11종(봉합 9 + 배제 금지 2) 각각 빈 1개**를 확인한다.
   목록 정본은 `AssemblyPortContract` — 한 곳만 고치면 두 프로파일에 함께 적용된다.
4. **prod 대칭 단언** — `excludeFilters` 는 프로파일을 가리지 않으므로 같은 단언을 prod 에서도 돌린다.
5. **CI 배선** — `backend-ci.yml` `assembly` 잡에 `:modules:app:nonProdAssemblyTest` 추가.
   태그로 분리된 가드는 배선하지 않으면 **CI 에서 0회 실행**된다.
   `ci-module-coverage.test.ts` 가 그 배선의 존재를 강제한다(하드코딩 목록 판별식).

## 결과 (Consequences)

- 조립 앱이 프로파일 지정 없이 부팅된다 — **실 백엔드 손검증 경로가 백엔드 쪽에서도 열린다**
  (#319 가 뚫은 것은 프론트 쪽뿐이었다).
- **비-prod 조립에서 automation `@Scheduled` 워커 4종이 처음으로 살아난다.** 그전엔 부팅 자체가 실패해
  한 번도 없던 상태다. dev postgres 의 `q_automation_events` 를 폴링한다. 룰이 0건이면 무해 delete 지만
  (`2026-07-11-automation-prod-assembly` §배포 런북), **`bootRun` 과 테스트를 동시에 돌리면** 두 벌이 된다.
  `mustRunAfter` + 테스트 KDoc 경고로 막는다.
- 비-prod 조립의 전역 권한 판정이 실제 DB 조회가 된다(D3) — 시드 없이는 프로젝트 생성이 막힌다.
- `2026-07-11-automation-prod-assembly` §fail-closed 의 "미충족 의존 0" 은 **prod 한정**이었음이 명시된다.
- FR 총수 **139 불변** · 마이그레이션 **0** · 신규 의존성 **0** · 다른 BC `src/**` **0줄**.
- `CHANGELOG.md` 는 변경하지 않는다 — BC 요약 표는 FR 단위 제품 산출 요약이고 이 PR 은 FR 0 · 조립 계층
  결함 봉합이다. (리뷰가 반박할 수 있도록 판단 근거를 여기 남긴다.)

## 대안 (기각)

- **B — 스텁 자체를 제거(근본 원인 제거).** 같은 역할의 스텁이 두 BC 에 중복 존재하는 뿌리를 지운다.
  기각 사유 — 두 BC 동시 수정 + 각 BC 단독 부팅 테스트까지 폭발 반경이 크다.
  `2026-05-22-issue-permission-resolver-port` L115 가 *"제거 시점은 dev/staging 도 새 adapter 검증 완료 후"* 로
  이미 예고한 방향이므로, 이 ADR 의 조립 한정 배제는 그 로드맵을 앞당기지도 막지도 않는다.
- **C — 개발도 prod 프로파일로 실행.** 코드 변경 0. 기각 사유 — 사용자·프로젝트 시드가 0건이라 부팅돼도
  로그인이 불가해 **손검증 목적 자체를 못 채운다.** PEM 키 파일 준비도 별도로 필요하다.
- **`@Primary` 로 승자 지정(D1 대안).** 두 빈이 모두 살아 있어 `List<T>` 주입 지점이 생기면 다시 갈린다.
  배제가 더 단정적이다.
- **정적 빈 정의 검사만으로 가드(D6 대안).** DB·서버 없이 빠르지만, 우리가 이해한 Spring 동작을 검사할 뿐
  Spring 자체를 검사하지 못한다. 고치는 증상이 정확히 "안 켜진다" 이므로 실부팅만이 직접 증거다.
