# 조립 앱 비-prod 프로파일 부팅 봉합 — 스펙

> slug: assembly-nonprod-bean-wiring · type: backend · PR #321
> 근거 결정. D4/D5/D6/D7 (2026-07-29 Maxi 확정) · decision `1fb93a4e`(A안 채택)

## 배경 — 한 줄

`backend/modules/app`(배포 조립 앱)이 **기본(비-prod) 프로파일에서 부팅되지 않는다.** prod 는 정상이다.
근본 원인은 각 BC 의 `@Profile("!prod")` 스텁이 "각자 독립 컨텍스트" 전제로 설계됐는데, 조립 모듈이 생기며
그 전제가 깨진 것이다. 봉합은 **조립 계층에서만** 한다 (다른 BC 코드 0 변경).

## 사용자 시나리오 (Given-When-Then)

**S1. 개발자가 실 백엔드를 띄운다 (주 시나리오).**
- Given. 개발자가 dev postgres(5433)를 띄우고 프로파일을 지정하지 않았다 (= 기본 프로파일).
- When. `./gradlew -p backend :modules:app:bootRun` 을 실행한다.
- Then. `Started BtsApplication` 이 뜨고 HTTP 요청을 받을 수 있다. `APPLICATION FAILED TO START` 가 없다.

**S2. 운영 부팅은 그대로다 (회귀 금지).**
- Given. `--spring.profiles.active=prod` 로 실행한다.
- When. 조립 컨텍스트가 뜬다.
- Then. 이 PR 이전과 **동일한 빈 구성**이다. 기존 prod 조립 테스트 전량이 그대로 통과한다.

**S3. 각 BC 단독 부팅도 그대로다 (회귀 금지).**
- Given. identity-access / issue-tracking 이 각자 컨텍스트로 뜬다 (단독 테스트 포함).
- When. 비-prod 프로파일이다.
- Then. 각 모듈은 자기 스텁을 그대로 쓴다. **각 BC 소스는 한 줄도 바뀌지 않았다.**

**S4. 개발 환경에서 자동화가 실제로 동작한다 (D7-A).**
- Given. 비-prod 조립 앱이 떠 있다.
- When. 자동화 규칙이 트리거된다.
- Then. 규칙이 **실제로** 이슈를 조회하고 변경한다. 조용한 no-op 이 아니다.

**S5. 관리자 판정이 개발에서도 진짜다 (D5-A).**
- Given. 비-prod 조립 앱이 떠 있다.
- When. 어떤 사용자가 전역 권한이 필요한 동작을 시도한다.
- Then. `system_role_assignments` / `global_permission_grants` 를 실제로 조회해 판정한다. 무조건 true 가 아니다.

**S6. 같은 사고가 재발하면 빌드가 막힌다 (D6-B).**
- Given. 누군가 새 `@Profile("!prod")` 구현을 추가해 조립에서 중복이 생긴다.
- When. 빌드를 돌린다.
- Then. **비-prod 조립 부팅 테스트가 실패한다.** 머지 전에 드러난다.

## 기능 요구사항 (FR)

> **FR 총수 139 불변.** 신규 제품 FR 아님 — 기존 조립 배선의 결함 봉합(D-step 성격).

### FR-A. 중복 6종 해소 — 조립 스캔에서 issue-tracking 스텁 배제 (D4-A)

`BtsApplication` 의 `@ComponentScan excludeFilters` 에 **정확히 아래 6개 클래스만** 추가한다.

| # | 배제 대상 (issue-tracking) | 조립에 남는 빈 | 비-prod 동작 |
|---|---|---|---|
| 1 | `AlwaysAllowIssuePermissionResolver` | identity-access `DevAllowIssuePermissionResolver` | 무조건 허용 (변화 없음) |
| 2 | `AlwaysAllowComponentPermissionResolver` | `DevAllowComponentPermissionResolver` | 무조건 허용 (변화 없음) |
| 3 | `AlwaysAllowCustomFieldPermissionResolver` | `DevAllowCustomFieldPermissionResolver` | 무조건 허용 (변화 없음) |
| 4 | `AlwaysAllowTemplatePermissionResolver` | `DevAllowTemplatePermissionResolver` | 무조건 허용 (변화 없음) |
| 5 | `AlwaysAllowVersionPermissionResolver` | `DevAllowVersionPermissionResolver` | 무조건 허용 (변화 없음) |
| 6 | `NonProdAllowSystemAdminResolver` | identity-access `IdentityAccessSystemPermissionResolver` | **실제 DB 판정** (D5-A) |

**규칙 한 줄.** *조립 컨텍스트에서 권한 리졸버의 출처는 언제나 identity-access 다.*

### FR-B. ★ 배제 금지 목록 (같은 PR 안에서 명시)

issue-tracking 에는 `AlwaysAllow*`/`NonProdAllow*` 가 **8개** 있는데 중복인 것은 **6개뿐**이다.
아래 2개는 자기 타입의 **유일한 비-prod 구현**이라 배제하면 새 "빈 부재"가 생긴다.

| 배제 금지 | 타입 | 이유 |
|---|---|---|
| `AlwaysAllowFieldPermissionResolver` | `FieldPermissionResolver` | identity-access 짝이 `@Profile("prod")` 뿐 → 비-prod 유일 구현 |
| `AlwaysAllowIssueSecurityDirectory` | `IssueSecurityDirectory` | 동일 |

**따라서 와일드카드/정규식 패턴(`AlwaysAllow*`) 배제를 금지한다.** 클래스를 하나씩 열거한다.

### FR-C. 부재 3종 해소 — 실 구현을 비-prod 에도 연결 (D7-A)

`app` 모듈에 `@Configuration @Profile("!prod")` 하나를 신설하고, 각 prod 구현을 `@Bean` 으로 등록한다.
`@Component @Profile("prod")` 와 **상호 배타**이므로 prod 에서 중복이 생기지 않는다.

| 타입 | 등록할 실 구현 | 생성자 의존 |
|---|---|---|
| `AutomationPermissionResolver` | `IdentityAccessAutomationPermissionResolver` | `ProjectDirectory` · `ProjectMembershipRepository` · `PermissionSchemeRepository` |
| `IssueMutationPort` | `AutomationIssueMutationAdapter` | `IssueApplicationService` · `CommentApplicationService` · `ObjectMapper` · `TransactionTemplate` |
| `IssueSnapshotPort` | `AutomationIssueSnapshotAdapter` | `IssueApplicationService` |

### FR-D. 재발 방지 가드 — 비-prod 조립 실부팅 테스트 (D6-B)

1. 비-prod(기본) 프로파일로 조립 컨텍스트를 실제로 띄우는 테스트를 신설한다.
2. **별도 Gradle `Test` 태스크(= 별도 JVM)** 로 격리 실행한다. 기존 prod 조립 테스트와 같은 JVM 에 두지 않는다.
3. 컨텍스트 로드 성공만으로는 공허하므로, 봉합 대상 **9종 각각의 빈이 정확히 1개**임을 단언한다.

## 비기능 요구사항 (NFR)

- **N1. 다른 BC 소스 변경 0.** `git diff --stat` 에서 `backend/modules/app/**` 와 `docs/**` 밖의 변경이 0이어야 한다.
- **N2. prod 빈 구성 불변.** prod 프로파일의 빈 목록이 이 PR 전후로 동일하다.
- **N3. 마이그레이션 0 · 신규 의존성 0 · FR 수 139 불변.**
- **N4. 기존 테스트 회귀 0.** `:modules:app:test` 전량 + 각 BC 모듈 테스트 전량 통과.

## API 인터페이스 (REST)

**없음.** 엔드포인트 신설·변경·삭제 0건.

## 데이터 모델 변경

**없음.** Flyway 마이그레이션 0건, 테이블/컬럼/권한 enum 변경 0건.

## 엣지 케이스

- **EC-1. 10번째 고장.** 정적 분석으로 찾은 9종을 고친 뒤에도 부팅이 실패할 수 있다 (Spring 은 첫 에러에서 멈추므로
  가려진 항목이 있을 수 있다). **부팅이 성공할 때까지 반복**하고, 새로 나온 항목은 같은 판별식으로 분류해 스펙에 추가한다.
- **EC-2. 배제 필터가 조용히 무효화.** `FilterType.REGEX` 로 배제하면 클래스 이름/패키지가 바뀔 때 매칭이 조용히
  풀려 중복이 되살아난다. → **`FilterType.ASSIGNABLE_TYPE` + 실제 import** 를 쓴다. 이름이 바뀌면 컴파일 에러가 난다.
- **EC-3. `ObjectMapper` 빈 모호성.** FR-C 의 `AutomationIssueMutationAdapter` 가 `ObjectMapper` 를 받는다.
  조립 컨텍스트에 `ObjectMapper` 빈이 2개 이상이면 새 충돌이 생긴다. 착수 시 실측 확인 필수.
- **EC-4. 생성자 변경 취약성.** FR-C 는 다른 BC 클래스의 생성자를 조립 모듈이 직접 호출한다. 생성자가 바뀌면
  **조립 모듈 컴파일 에러**로 드러난다 (조용한 런타임 실패가 아님) — 이것이 이 방식을 택한 이유다.
- **EC-5. 가드가 공허해질 위험.** FR-D 의 테스트가 "컨텍스트만 뜨면 통과"면 무의미하다. 봉합을 되돌리는
  **뮤테이션(일부러 위반 주입)으로 RED 를 실증**해야 한다. 9종 각각에 대해 확인한다.
- **EC-6. 시드 0건.** D5-A 채택으로 비-prod 에서도 실제 권한 판정이 이뤄지므로, 계정·프로젝트 시드가 없으면
  프로젝트 생성 등이 막힌다. **이 PR 의 책임 밖**이며 "부팅된다"까지가 완료 기준이다 (별건으로 추적).
- **EC-7. 컨텍스트 이중 부팅.** 비-prod 테스트를 기존 prod 테스트와 같은 JVM 에 두면 9-BC 컨텍스트가 2벌 떠
  `@Scheduled` 워커가 같은 pgmq 큐를 동시 폴링한다 (`ProdAssemblyHttpTestBase` KDoc L37~43 경고).
  → FR-D 2 의 별도 Gradle 태스크가 이를 원천 차단한다.

## 제약 조건

- 변경 허용 경로. `backend/modules/app/**` · `docs/**` (+ 필요 시 `CHANGELOG.md`)
- **변경 금지.** 다른 9개 BC 모듈의 `src/**` 전량
- 절대 규칙 `DEVELOPMENT.md §1` 준수 · 신규 소스 파일 첫 줄 한글 역할 주석 (CLAUDE.md §6)
- TDD red→green→refactor 강제 (`test:` 커밋이 `feat:` 커밋보다 먼저)
- 머지 전 `bash scripts/verify-master-plan.sh` 통과

## 측정 가능한 완료 기준

1. `./gradlew -p backend :modules:app:bootRun` (프로파일 미지정) → `Started BtsApplication` 로그 확인, 실패 0
2. FR-D 테스트가 신설되고, **9종 각각에 뮤테이션을 주입했을 때 전부 RED**
3. `:modules:app:test` + 신설 비-prod 태스크 전량 GREEN
4. 각 BC 모듈 테스트 전량 GREEN (회귀 0)
5. `git diff --stat origin/main` 에서 `backend/modules/app/**`·`docs/**` 밖 변경 **0줄**
6. prod 조립 테스트(`ProdAssemblyHttpTestBase` 하위 전량) GREEN — S2 회귀 금지 실증
7. `ktlintCheck` + `detekt` 통과 (app 모듈 baseline 갱신은 허용)
8. `bash scripts/verify-master-plan.sh` 종료 0

## Brainstorming Check

✅ 통과 (1회 iteration) — **gap 1건 발견 후 보강.**
issue-tracking 의 `AlwaysAllow*`/`NonProdAllow*` 는 8개인데 중복은 6개뿐이라, 패턴 배제 시
`AlwaysAllowFieldPermissionResolver` · `AlwaysAllowIssueSecurityDirectory` 가 함께 제거돼
**새 "빈 부재" 2종이 생긴다**(봉합이 새 결함을 만드는 양식). → FR-B 로 배제 금지 목록을 명시하고
와일드카드 배제를 금지했다.
