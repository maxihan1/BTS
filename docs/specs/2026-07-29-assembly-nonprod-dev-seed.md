# 조립 앱 비-prod dev 시드 — 스펙

> slug: `assembly-nonprod-dev-seed` · type: `auth` · BC: `identity-access`(데이터) / `:modules:app`(코드)
> 선행: #321 조립 앱 비-prod 부팅 봉합 · 작성 2026-07-29

## 0. 배경 — 무엇이 문제인가

#321 로 조립 앱(`:modules:app`)이 비-prod 프로파일에서 부팅된다(`Started BtsApplicationKt in 8.13s`).
그러나 **계정 0건**이라 로그인이 불가능해 손검증 경로가 닫혀 있다.
#321 D5 로 `SystemPermissionResolver` 가 비-prod 에서도 **실제 DB 판정**을 하므로, 시드가 손검증의 선행 조건이다.

### 실측된 현재 상태 (추정 아님)

| 계층 | 테이블 | 상태 | 근거 |
|---|---|---|---|
| 사용자 | `users` | 비어 있음 | `V001__users.sql` 시드 0건 |
| 비밀번호 | `local_credentials` | 비어 있음 | `V003__local_credentials.sql` 시드 0건 |
| 인증 공급자 | `authn_providers` | **불필요** | `AuthnProviderConfigRepository.isEnabled():40-44` — disabled 행 부재 = 활성 |
| 시스템 역할 | `system_role_assignments` | 비어 있음 | `V012` 시드 0건 |
| 권한 스킴 | `permission_schemes` | **시드 완료** | `V008:51,62` + 미매핑 프로젝트 fallback(`V008:37`) |
| 워크플로 스킴 | `workflow_schemes` | **시드 완료** | `V201:118,137` 표준 4종 |
| 프로젝트 | `projects` | 비어 있음 — **실 UI 경로 존재** | `ProjectCreateApplicationService:56-66` · 화면 `/projects/new`(#300) |

### 이미 존재하는 시드 자산 (조립에서만 꺼져 있음)

- `identity-access/src/main/resources/data-dev.sql` — `alice` / `password`, 결정적 UUID `…0001`, 실 Argon2id 해시, `ON CONFLICT DO NOTHING`
- `issue-tracking/src/main/resources/data-dev.sql` — `ATLAS` 프로젝트 1건
- **비활성 사유** — `app/application.yml:47` *"dev seed(data-dev.sql) 는 모듈마다 존재해 classpath 충돌 → 조립 앱에선 비활성"*.
  두 모듈 모두 `data-locations: classpath:data-dev.sql` 로 **같은 리소스 이름**을 쓰는데 조립은 두 jar 가 한 클래스패스라
  `classpath:` 가 하나만 해석한다.
- 두 파일 어디에도 `system_role_assignments` 행이 **없다**.
- 조립 앱 기본 프로파일은 `dev` 가 **아니다**(무프로파일). `application-dev.yml` 은 `dev` 활성 시에만 병합된다.

## 1. 확정 결정 (Maxi)

| ID | 결정 | 근거 |
|---|---|---|
| **D4** | 시드 깊이 = **최소** — `users` + `local_credentials` + `system_role_assignments` 3행. **프로젝트는 시드하지 않는다** | 로그인 성립 최소집합이 실측으로 2행이고, 프로젝트는 실 UI(`/projects/new`)로 만들면 생성 경로 자체가 손검증 대상이 된다. identity-access 단일 BC 라 BC 격리 충돌 0 |
| **D5** | 주입 방식 = **`:modules:app` 전용 `ApplicationRunner` + `@Profile("!prod")`** | #321 A안(조립 계층 문제는 조립 모듈에서 봉합) + D7(스텁 금지, 실구현 경로) 동시 충족. 실 Argon2 인코더 호출이라 해시 리터럴이 저장소에 남지 않는다 |
| **D6** | 기존 `data-dev.sql` **2개는 손대지 않는다** (단독 모듈 실행용으로 유지) | 순서 보장 미검증(시드가 Flyway 보다 먼저 돌 위험) · 기존 파일은 프로젝트까지 넣어 D4 와 어긋남 · 권한 행 부재. 다른 BC 0줄 원칙도 함께 지킨다 |
| **D7** | 시드 신원 = **username `alice` 고정**. ~~UUID `…0001` 고정~~ **철회** (G1) | 새 dev 신원을 만들면 기존 dev 관례(`alice`)와 갈라진다. UUID 결정성은 실 생산 경로로 달성 불가 — §Brainstorming Check G1 참조 |
| **D8** | 비밀번호 = `${BTS_DEV_SEED_PASSWORD:password}` | 기본값이 기존 `data-dev.sql` 이 이미 문서화한 것과 **동일**하므로 새 비밀이 도입되지 않는다. 필요 시 환경변수로 교체 가능 |

## 2. 사용자 시나리오 (Given-When-Then)

- **S1 첫 부팅.** Given 빈 데이터베이스, When 조립 앱을 비-prod 로 기동, Then `users`·`local_credentials`·`system_role_assignments` 각 1행이 생기고 기동 로그에 시드 사실이 남는다.
- **S2 로그인.** Given S1 완료, When 브라우저에서 `alice` / `password` 로 로그인, Then 200 + 세션 발급, 대시보드 진입.
- **S3 재부팅 멱등.** Given S1 완료 상태, When 앱을 다시 기동, Then 행 수 불변(각 1행), 기존 행 값 무변경, 로그는 "이미 존재" 취지로 남는다.
- **S4 프로젝트 생성 도달.** Given S2 완료, When `/projects/new` 진입, Then `whoami.canCreateProject == true` 로 생성 폼이 열리고 프로젝트가 실제로 만들어진다.
- **S5 prod 격리.** Given prod 프로파일, When 조립 앱 기동, Then 시드 관련 빈이 컨텍스트에 **0개**이고 `users` 는 **0행**을 유지한다.
- **S6 부분 시드 보정.** Given `users` 에 alice 는 있으나 `local_credentials` 가 없는 상태(모듈 SQL 만 일부 적용 등), When 기동, Then 없는 행만 채워지고 있는 행은 건드리지 않는다.

## 3. 기능 요구사항 (FR)

| ID | 요구사항 |
|---|---|
| FR-1 | 비-prod 기동 시 dev 사용자 1건(`alice`, 결정적 UUID)을 보장한다 |
| FR-2 | 해당 사용자의 `local_credentials` 를 **실 Argon2 인코더**로 생성해 보장한다. 해시 리터럴을 소스에 두지 않는다 |
| FR-3 | 해당 사용자에게 `SYSTEM_ADMIN` 역할 1건을 보장한다 |
| FR-4 | **멱등** — 이미 존재하는 행은 수정하지 않고, 없는 행만 삽입한다(부분 상태 포함) |
| FR-5 | prod 프로파일에서는 시드가 **한 줄도 실행되지 않는다** |
| FR-6 | 시드는 Flyway 마이그레이션 **완료 이후**에만 실행된다 |
| FR-7 | 기동 로그로 시드 수행/스킵을 관측할 수 있다. **비밀번호 평문은 절대 기록하지 않는다** |

## 4. 비기능 요구사항 (NFR)

| ID | 요구사항 | 측정 |
|---|---|---|
| NFR-1 | 다른 9개 BC 의 `src/**` 변경 0줄 | `git diff --stat` 경로 검사 |
| NFR-2 | 마이그레이션 0건 · 신규 의존성 0건 | 파일 목록 대조 |
| NFR-3 | 비밀번호 평문 미로깅 (`DEVELOPMENT.md §1.1` 규칙 2) | 로그 캡처 단언 |
| NFR-4 | 부팅 시간 증가 허용 한도 = 기존 대비 +1초 이내 | `Started BtsApplicationKt in Xs` 대조 |

## 5. API 인터페이스 / 데이터 모델 변경

**없다.** REST 엔드포인트 0건 추가, DDL 0건. 기존 테이블에 행만 넣는다.

## 6. prod 격리 — 구조적 판별식 (핵심 설계)

`@Profile("!prod")` 문자열 하나에 의존하지 않는다. 문자열은 오타·프로파일 추가 시 **조용히** 뚫린다.

| 층 | 장치 | 뚫렸을 때 증상 |
|---|---|---|
| L1 | 빈 등록 `@Profile("!prod")` | 빈 자체가 prod 컨텍스트에 없음 |
| L2 | 실행 진입부 런타임 단언 — 활성 프로파일에 `prod` 가 있으면 **예외로 기동 실패**(조용한 스킵 금지) | L1 우회 시 즉시 관측 |
| L3 | **양방향 봉인 테스트** — prod 조립 컨텍스트에 시드 빈 **0개** + `users` **0행**(음성) / 비-prod 조립에 빈 1개 + 3행(양성) | 한쪽만 검사하면 절반만 닫힌다 |

L3 이 핵심이다. 메모리 `seal-closes-only-half-by-default` — 삭제·추가 **양방향 주입**으로 판별력을 실증한다.
L2 를 "조용히 return" 으로 만들면 **음성 단언이 공허해진다**(빈이 없어도 통과, 빈이 있어도 통과) — 메모리
`negative-guard-needs-body-discriminator` 가 경고한 형태다.

## 7. 엣지 케이스

| ID | 상황 | 기대 동작 |
|---|---|---|
| E1 | `alice` 가 이미 존재 (모듈 `data-dev.sql` 로 유입) | 무변경. 중복 삽입 시도 0 |
| E2 | `users` 는 있는데 `local_credentials` 부재 | 없는 것만 삽입 (부분 보정) |
| E3 | `system_role_assignments` 이미 존재 | `UNIQUE(user_id, role)` 충돌 회피, 무변경 |
| E4 | 시드가 Flyway 보다 먼저 실행 | 발생 불가여야 한다 — FR-6 순서 보장으로 차단, 테스트로 실증 |
| E5 | prod 프로파일 오설정으로 빈이 살아남음 | L2 가 기동 실패시킨다 (조용한 스킵 아님) |
| E6 | 기존 `alice` 의 비밀번호가 다름 | **덮어쓰지 않는다.** 멱등 원칙 우선 — 로그로만 고지 |
| E7 | `BTS_DEV_SEED_PASSWORD` 미설정 | 기본값 `password` 사용 (기존 `data-dev.sql` 과 동일) |
| E8 | DB 연결 실패 | 기존 부팅 실패 동작 그대로. 시드가 새 실패 모드를 만들지 않는다 |

## 8. 제약 조건

1. **prod 오염 0** — 최우선. 다른 모든 요구사항에 우선한다.
2. **다른 BC `src/**` 0줄** — #321 이 달성한 기준을 유지한다.
3. **스텁 금지** — 실 인코더·실 도메인 규칙을 통과한 데이터여야 한다 (#321 D7).
4. **CI 배선까지가 범위** — 새 테스트를 `backend-ci` 의 `assembly` 잡에서 실제로 돌게 배선한다.
   태그로 기본 `test` 에서 뺀 뒤 배선을 빠뜨리면 **CI 0회 실행**된다 (#321 BLOCKER · `TODOS §151` 동형).

## 9. 측정 가능한 완료 기준

- [ ] 비-prod 조립 부팅 후 **실제 브라우저에서 `alice` 로그인 성공** (눈확인)
- [ ] 로그인 후 `/projects/new` 도달 + 프로젝트 실제 생성 성공 (D4 의 「실 UI 경로」 실증)
- [ ] prod 조립 부팅 시 시드 빈 0개 · `users` 0행 (음성 대조군)
- [ ] 재부팅 2회 후 행 수 각 1건 불변 (멱등 실증)
- [ ] 뮤테이션 주입 전량 RED — 최소 5종 (①`@Profile` 제거 ②L2 단언 제거 ③멱등 조건 제거 ④권한 행 미삽입 ⑤순서 보장 파괴)
- [ ] `app:test` + 신규 가드 테스트 실패 0 · ktlint + detekt `--rerun-tasks` SUCCESSFUL
- [ ] `backend-ci` assembly 잡에서 신규 가드가 **실제 실행**됨을 CI 로그로 확인
- [ ] `verify-master-plan.sh` EXIT 0 · FR 카운트 139 불변

## 10. 범위 밖 (명시적 제외)

- 프로젝트·이슈·추가 사용자 시드 (D4 최소 결정)
- 기존 `data-dev.sql` 2개의 통합/삭제 (D6)
- `classpath*` 로 모듈 시드를 되살리는 설정 변경 (D6 에서 기각)
- prod 시드 전략 (해당 없음 — 영구 제외)

## 워크플로우 편차 기록

- **`office-hours` 미호출.** 제품 범위 결정이 0건이고(시드 깊이·주입 방식·격리 방식은 Maxi 가 D4/D5/D6 로 이미 확정),
  남은 것은 전부 기술 설계라 기술 스펙을 직접 작성했다. 선례 — #309·#310 동일 사유 등재.
- **`design-shotgun` / `design-consultation` 미호출.** UI 변경 0건.

## Brainstorming Check

**방식.** 스펙의 기술 **가정을 반증**하는 형태로 수행했다 — "실 도메인 경로가 존재한다", "순서가 보장된다",
"결정적 UUID 를 쓸 수 있다" 세 가정을 코드로 검증했다. 대화형 `superpowers:brainstorming` 대신 이 형태를 쓴 이유는
미해결이 제품 요구가 아니라 **구현 가능성**이었기 때문이다. gap **5건** 발견, 전량 스펙에 반영했다.

### G1 — 「결정적 UUID」는 실 생산 경로로 달성 불가 (수정 필요 → D7 정정)

`UserRepository.create():236` 이 내부에서 `UUID.randomUUID()` 를 쓴다. id 를 받는 오버로드가 없다.
따라서 `…0001` 고정과 「실구현 경로 사용」(#321 D7)은 **동시 충족 불가**다.
identity-access 에 id 지정 메서드를 추가하면 「다른 BC `src/**` 0줄」(제약 2)이 깨진다.
**해소** — UUID 결정성 요구를 **철회**한다. 신원 고정은 `username = "alice"` 로 충분하며,
멱등 판정도 `findByUsername` 으로 성립한다.

### G2 — `CreateLocalAccountService` 는 쓸 수 없다 (설계 함정 회피)

이름만 보면 정확한 진입점처럼 보이나 **의미가 다르다** — `create():78-81` 이
`temporaryPasswordGenerator.generate()` 로 **무작위** 비밀번호를 만들고 `mustChange = true` 로 저장한다.
쓰면 (a) 시드 비밀번호를 우리가 정할 수 없고 (b) 로그인 직후 비밀번호 변경 화면으로 강제 이동해
**손검증 경로를 여는 목적 자체를 깬다**(`V019__local_credentials_must_change_password.sql`).
**해소** — `UserRepository.create` + `LocalCredentialService.store(userId, plain, mustChange = false)`
조합을 쓴다. 둘 다 프로덕션 컴포넌트이므로 #321 D7(스텁 금지)은 그대로 충족된다.

> 교훈 형태 — 「같은 이름의 서비스가 있다」는 「그 서비스가 내가 원하는 일을 한다」가 아니다.
> 메모리 `comment-backend-is-import-byproduct-read-only` 와 동형이다.

### G3 — 평문 수명 관리 책임이 시드 코드에 있다 (요구사항 추가)

`LocalCredentialService.store` 는 전달받은 평문 `CharArray` 를 해시 후 **wipe** 한다
(KDoc §평문 수명, `DEVELOPMENT.md §1.1`). 시드는 환경변수/기본값 `String` 을 `CharArray` 로 바꿔 넘기므로
**원본 String 이 힙에 남는 구간**이 생긴다. → **FR-8 신설**.

### G4 — 실행 순서는 이미 구조적으로 보장된다 (가정 확인, 테스트로 못 박을 것)

`FlywayAssemblyConfig.assemblyFlywayMigrator():45-65` 가 `InitializingBean.afterPropertiesSet()` 에서
마이그레이션을 **즉시** 수행한다 = 컨텍스트 refresh **중** 완료. `ApplicationRunner` 는 refresh **후** 실행.
⇒ FR-6 은 설계상 이미 참이다. 다만 **"참인 것"과 "회귀를 막는 것"은 다르다** — 누군가 시드를
`@PostConstruct` 나 `InitializingBean` 으로 옮기면 조용히 깨진다. 순서 뮤테이션을 완료 기준에 유지한다.

### G5 — 시드 실패 시 부팅을 죽일 것인가 (스펙 미정 → D9 신설)

두 실패를 **구분**해야 한다.
- **prod 격리 위반**(L2) → **기동 실패**. 조용한 스킵은 음성 단언을 공허하게 만든다.
- **시드 자체 실패**(DB 제약 위반 등) → **ERROR 로그 + 부팅 계속**. dev 편의 기능이 부팅을 막으면
  #321 이 연 「비-prod 에서 켜진다」를 되돌리는 회귀가 된다.

⇒ **D9 확정** — 실패 종류에 따라 반대로 처리한다. 이 비대칭을 KDoc 에 명시하고 테스트로 양쪽을 덮는다.

### 추가 요구사항 (G3·G5 반영)

| ID | 요구사항 |
|---|---|
| FR-8 | 시드에 쓰인 평문 비밀번호는 사용 직후 wipe 한다. `String` 보관 구간을 최소화한다 |
| FR-9 | 시드 자체 실패는 ERROR 로그 후 **부팅을 계속**한다. prod 격리 위반만 기동을 실패시킨다 |

### 반증에 사용한 실측 지점

| 가정 | 판정 | 근거 |
|---|---|---|
| 실 계정 생성 서비스가 있다 | **부분 참** | `CreateLocalAccountService` 존재하나 의미 불일치 (G2) |
| 실 해시 구현을 호출할 수 있다 | **참** | `LocalCredentialService.store():80-92` — 실 Argon2id |
| 시스템 역할 쓰기 경로가 있다 | **참** | `SystemRoleAssignmentRepository.assign():28` |
| 멱등 판정용 조회가 있다 | **참** | `UserRepository.findByUsername():40` · `findRolesByUser():39` |
| 결정적 UUID 를 쓸 수 있다 | **거짓** | `UserRepository.create():236` = `UUID.randomUUID()` (G1) |
| Flyway 가 시드보다 먼저 돈다 | **참** | `FlywayAssemblyConfig:45-65` `InitializingBean` (G4) |

✅ **통과** (1회 iteration, gap 5건 발견 후 전량 반영 — D7 정정 · D9 신설 · FR-8/FR-9 추가)
