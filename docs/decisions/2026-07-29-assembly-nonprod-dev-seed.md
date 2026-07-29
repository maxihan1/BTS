# ADR — 조립 앱 비-prod dev 시드 (2026-07-29)

- 상태. 채택
- 관련. #321(조립 앱 비-prod 부팅 봉합) · #300(프로젝트 생성 API·UI) · `2026-06-04-system-admin-role`
- 무효화. `2026-05-22-issue-key-prefix-policy` §본 PR 적용 범위 2항(`data-dev.sql` 의 ATLAS 시드)

## 맥락

#321 로 조립 앱(`:modules:app`)이 비-prod 프로파일에서 부팅된다. 그러나 **계정이 없어 로그인할 수 없어**
손검증 경로가 닫혀 있었다. #321 D5 로 `SystemPermissionResolver` 가 비-prod 에서도 **실제 DB 판정**을 하므로
시드가 손검증의 선행 조건이 됐다.

### 착수 후 뒤집힌 전제 3건 (전부 실측)

1. **「5계층을 시드해야 한다」가 낡았다.** 권한 스킴(`V008:51,62`)·워크플로 스킴(`V201:118,137`)은
   이미 마이그레이션이 시드하고, `authn_providers` 는 **행 부재가 곧 활성**이다
   (`AuthnProviderConfigRepository.isEnabled():40-44`). 실제로 비어 있는 건 2계층뿐이었다.
2. **「시드 장치가 없다」가 아니라 「있는데 조립에서만 꺼져 있었다」.** 모듈마다 `data-dev.sql` 이 있었고
   두 모듈이 같은 리소스 이름(`classpath:data-dev.sql`)을 써서 조립 클래스패스에서 충돌해
   `spring.sql.init.mode: never` 로 비활성화돼 있었다(`app/application.yml:47` 주석에 사유 명시).
3. **「계정이 없다」가 이 머신에서는 거짓이었다.** 로컬 dev DB 에 `alice`(2026-07-10)와
   `CREATE_PROJECT` 전역 부여(2026-07-28)가 이미 있었다. 볼륨 `infra_bts-postgres-data` 가 영속이다.
   ⇒ 이 작업의 가치는 **새 머신 · CI · 볼륨 삭제 후**에 있다.

## 결정

### D1. 시드 깊이 = 최소 (Maxi 확정)

`users` 1행 + `local_credentials` 1행 + `CREATE_PROJECT` 전역 부여 1행. **프로젝트는 시드하지 않는다.**

근거 — 로그인 성립 최소집합이 실측으로 앞의 2행이다(`AuthController:147` →
`CompositeAuthenticationManager.authenticate` → `LocalProvider`). 프로젝트는 `/projects/new`(#300)로
만들며, 그 **실 생성 경로가 곧 손검증 대상**이 된다.

### D2. 주입 방식 = 조립 모듈 전용 `ApplicationRunner` (Maxi 확정)

`:modules:app` 의 `NonProdDevSeedRunner` + `NonProdDevSeeder`. Flyway 안(`R__` repeatable) 기각 —
비밀번호 해시 **리터럴이 prod 저장소에 남고** 스크립트가 BC 모듈 마이그레이션 경로를 쓴다.

실행 시점은 `ApplicationRunner` 여야 한다 — `FlywayAssemblyConfig.assemblyFlywayMigrator():45-65` 가
`InitializingBean.afterPropertiesSet()` 에서 마이그레이션하므로 refresh **중** 끝나고,
`ApplicationRunner` 는 refresh **후** 실행이라 테이블이 반드시 있다.
`@PostConstruct`/`InitializingBean` 으로 옮기면 조용히 깨진다.

### D3. ★ `SYSTEM_ADMIN` 을 주지 않는다 — `CREATE_PROJECT` 전역 부여만 준다

**원안(관리자 부여)은 이 작업의 목적을 파괴한다.**

```
MfaEnforcementPolicy:24  「관리자(SYSTEM_ADMIN)는 무조건 강제 대상이다」
MfaEnforcementPolicy:71  systemPermissionResolver.isSystemAdmin(userId) ||
      ↓
JwtIssuer:109            mfa_enrollment_required=true 를 토큰에 박음
      ↓
MfaEnrollmentGateFilter:54  허용목록 밖 전 경로 403
```

즉 관리자로 시드하면 **로그인은 되지만 어떤 화면에도 못 간다**.
처방은 `IdentityAccessSystemPermissionResolver:66` 의 판정식
`hasGrant(actorId, permission) || isSystemAdmin(actorId)` 에서 **앞항**을 쓰는 것이다.

기각한 대안 — (a) MFA 등록까지 시드: 로그인마다 TOTP 코드가 필요해 손검증이 더 번거로워진다.
(b) 로그인 후 수동 등록: DB 를 새로 만들 때마다 사람이 반복해야 해 "부팅하면 바로 쓴다" 가 깨진다.

**대가.** `/admin/*` 관리자 화면은 이 계정으로 열리지 않는다. 별도 과제로 남긴다.

### D4. prod 격리는 **허용목록**으로 (부정 금지)

`@Profile("default | dev | local")`. `@Profile("!prod")` 같은 부정은 앞으로 생기는 모든 프로파일
(`staging`·`demo`…)을 자동 포함하는 **fail-open** 이다.

`default` 를 반드시 포함해야 한다 — 조립 앱의 실제 기본 부팅은 **무프로파일**이고
`application-dev.yml` 은 `dev` 활성 시에만 병합된다.

L1(`@Profile` 표현식)과 L2(런타임 단언 집합)는 **같은 상수를 공유**하고, 두 집합이 같은지를
테스트가 직접 단언한다(drift 차단).

### D5. 두 실패를 반대로 처리한다

| 실패 | 처리 | 근거 |
|---|---|---|
| prod 격리 위반 | **기동 실패**(예외) | 조용한 skip 이면 음성 봉인이 공허해진다 |
| 시드 자체 실패 | ERROR 로그 + **부팅 계속** | dev 편의 기능이 부팅을 막으면 #321 이 연 비-prod 부팅을 되돌리는 회귀 |

catch 는 `@Transactional` 경계 **밖**이라 실패분은 정상 롤백된 뒤 로그만 남는다.

### D6. 멱등 — 없는 것만 채우고, 있는 것은 건드리지 않는다

**기존 비밀번호가 달라도 덮어쓰지 않는다.** 덮어쓰면 사람이 dev 에서 바꾼 비밀번호를 재부팅이 조용히 되돌린다.

### D7. 시드 장치를 **한 벌로 단일화** (Maxi 확정 — 게이트 1 이후 D6 번복)

모듈별 `data-dev.sql` 2개와 `application-dev.yml` 의 `sql.init` 배선을 **삭제**했다.
같은 목적의 장치 2벌은 시간이 지나면 어긋나고 한쪽만 고치는 사고가 난다.

**부수 효과** — "해시를 손으로 생성해 SQL 에 박는" 문제 자체가 사라졌다(실 인코더가 기동 시 계산).
그 목적으로만 존재하던 `DevSeedHashGenerator.kt` 도 고아가 되어 제거했다.

**대가.** 개별 BC 를 **단독으로** dev 프로파일로 띄우면 시드가 없다. 조립 앱으로 띄워야 한다.
두 `application-dev.yml` 헤더에 경고를 남겼다.

### D8. 비밀번호 기본값 = `password` (기존 값과 동일)

`bts.dev-seed.password: ${BTS_DEV_SEED_PASSWORD:password}`. 삭제된 `data-dev.sql` 이 이미 문서화한 값과
같으므로 **새 비밀이 도입되지 않는다**.

## 결과

- 마이그레이션 **0건** · 신규 의존성 **0건** · FR 수 **139 불변**
- 다른 BC 변경 — `identity-access`·`issue-tracking` 의 `data-dev.sql`(삭제 2) ·
  `application-dev.yml`(2) · `application-test.yml`(1) · `DevSeedHashGenerator.kt`(삭제 1). **#321 의
  「다른 BC 0줄」은 D7 채택으로 의도적으로 포기됐다** (Maxi 가 비용을 알고 선택)
- 검증 — `app:test` 71/0(기준선 63 +8) · `nonProdAssemblyTest` 8/0(기준선 2 +6) ·
  `identity-access` 2432/0 · `issue-tracking` 3206/0
- red→green 실증 — 빈 DB 에서 시더 비활성 시 **4/4 FAILED**, 활성 시 **통과**

## 잔여 위험

1. **`/admin/*` 손검증 불가** (D3 의 대가). 관리자 화면 확인이 필요하면 MFA 등록을 사람이 1회 해야 한다.
2. **단독 모듈 dev 실행 시 시드 없음** (D7 의 대가). 사용 빈도가 낮다는 판단에 기반하며, 틀렸다면 되돌려야 한다.
3. **멱등 판정이 「조회 후 삽입」이라 원리상 TOCTOU** — 동시 부팅 2 프로세스에서만 발생하고 D5 가 흡수한다.
4. **평문 비밀번호가 설정값(String)으로 힙에 존재** — Spring 이 보유하는 값이라 시더가 수명을 통제하지 못한다.
   이 시드가 비-prod 전용이어야 하는 이유 중 하나다.
5. **교차모델 리뷰 부재** — `codex` CLI 미설치 + 에이전트 호출 금지 지시로 outside voice 를 돌리지 못했다.
   #308·#309·#310 과 동형의 잔여 위험.
