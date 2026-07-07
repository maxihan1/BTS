# FR-PR-04 — LDAP 동기화 필드 vs 사용자 편집 분리 — 스펙

> slug: fr-pr-04-ldap-vs-source · BC: identity-access · type: auth
> ADR: [2026-07-07-fr-pr-04-ldap-field-source](../decisions/2026-07-07-fr-pr-04-ldap-field-source.md)
> 선행: FR-PR-01(프로필 CRUD, PR #239)

## 배경 (코드 실측)

- LDAP 동기화 = 로그인 시점(JIT) `AutoProvisionService.provision()` → `SQL_PROVISION_UPSERT`.
  `ON CONFLICT (username)` 시 email·display_name을 매번 LDAP 값으로 덮어씀.
- 실제 충돌 필드 = **display_name 하나**. (email은 편집 불가, timezone/department/avatar는 LDAP 미접촉)
- 결정(ADR): `users.display_name_source`(`LDAP`|`USER`) 단일 컬럼으로 출처 추적.

## 사용자 시나리오 (Given-When-Then)

### S1 — 사용자가 LDAP 동기화된 이름을 직접 편집(override)
- **Given** LDAP으로 프로비저닝된 사용자 alice(display_name="Alice Kim", source=LDAP)
- **When** 프로필 페이지에서 이름을 "앨리스"로 저장
- **Then** users.display_name="앨리스", display_name_source="USER"로 전환된다

### S2 — override된 이름이 LDAP 재로그인에 보존됨 (핵심)
- **Given** alice(display_name="앨리스", source=USER)
- **When** alice가 LDAP으로 다시 로그인(cn="Alice Kim" 재전달)
- **Then** users.display_name는 "앨리스" 그대로 유지된다(LDAP이 덮어쓰지 않음). email은 계속 동기화된다

### S3 — source=LDAP 사용자는 재로그인에 계속 동기화됨
- **Given** bob(display_name="Bob Lee", source=LDAP, 편집 이력 없음)
- **When** LDAP cn이 "Bob Lee Jr"로 변경된 뒤 bob이 재로그인
- **Then** users.display_name="Bob Lee Jr"로 동기화된다(기존 동작 유지)

### S4 — LDAP 값으로 재설정(재동기화)
- **Given** alice(display_name="앨리스", source=USER)
- **When** 프로필 페이지에서 "LDAP 값으로 재설정" 실행
- **Then** display_name_source="LDAP"로 되돌아간다. display_name은 다음 LDAP 로그인 시 cn으로 동기화된다
  (즉시 값 변경 아님 — 지연 동기화, Maxi 승인)

### S5 — 로컬 전용 사용자는 출처 표시/재설정 없음
- **Given** 외부 계정 없는 로컬 사용자 carol(FR-AU-05 가입)
- **When** 프로필 페이지 조회
- **Then** "LDAP에서 동기화됨" 라벨/재설정 액션이 노출되지 않는다(ldapLinked=false).
  이름 편집은 자유(source 플래그는 무의미/inert)

### S6 — UI 출처 라벨 + 편집/재설정 어포던스
- **Given** LDAP 연결 사용자
- **When** 프로필 페이지 진입
- **Then** display_name 필드에 출처 상태가 표시된다.
  - source=LDAP: "디렉터리에서 동기화됨" 배지 + "직접 편집"(override) 버튼
  - source=USER: "직접 편집됨" 표시 + "디렉터리 값으로 재설정" 버튼 (문구는 provider-중립)

## 기능 요구사항 (FR)

- **FR-1** `users.display_name_source` 컬럼 신설(`LDAP`|`USER`, DEFAULT `LDAP`). 기존 행 backfill=`LDAP`.
- **FR-2** LDAP 프로비저닝 UPSERT(`SQL_PROVISION_UPSERT`)는 `display_name_source='USER'`인 행의 display_name을 **덮어쓰지 않는다**(CASE 게이트). email은 계속 동기화. source 값 자체는 동기화가 바꾸지 않는다.
- **FR-3** 프로필 PATCH로 displayName을 수정하면 서비스가 같은 트랜잭션에서 `display_name_source='USER'`로 전환한다.
- **FR-4** GET `/me/profile` 응답에 `displayNameSource`(LDAP|USER)와 `ldapLinked`(외부 IdP 계정 보유=디렉터리 동기화 대상) 노출.
- **FR-5** 재동기화 엔드포인트: 사용자가 display_name_source를 `USER`→`LDAP`로 되돌린다. 외부 계정이 없는 사용자에겐 409.
- **FR-6** 프론트: display_name 필드에 출처 배지 + override(직접 편집) + "디렉터리 값으로 재설정"(provider-중립 문구) 액션. 로컬 전용 사용자에겐 미노출.

## API 인터페이스 (REST)

### GET `/api/v1/users/me/profile` (기존 확장)
응답 `ProfileResponse`에 필드 추가.
```
{
  ...기존(userId, username, email, displayName, avatarUrl, timezone, department),
  "displayNameSource": "LDAP" | "USER",
  "ldapLinked": true | false
}
```

### PATCH `/api/v1/users/me/profile` (기존, 동작 확장)
- `displayName` 명시 → 값 반영 + `display_name_source='USER'` 전환(FR-3). 요청 바디는 변경 없음.

### POST `/api/v1/users/me/profile/display-name/resync` (신규)
- display_name을 다시 LDAP 동기화 대상으로 되돌린다(`display_name_source='LDAP'`).
- 200 → 갱신된 `ProfileResponse`(displayName은 아직 이전 값, source=LDAP).
- 409 `DISPLAY_NAME_NOT_LDAP_LINKED` → 외부 IdP 계정이 없는 사용자.
- 인증: JWT subject 전용(기존 me-scope 컨트롤러와 동일, PAT 미지원 401).

## 데이터 모델 변경

`V0NN__user_display_name_source.sql` (identity-access, raw SQL — init_codegen 미러 불요, jdbc-only).
```sql
ALTER TABLE users
  ADD COLUMN display_name_source VARCHAR(8) NOT NULL DEFAULT 'LDAP'
    CHECK (display_name_source IN ('LDAP', 'USER'));
COMMENT ON COLUMN users.display_name_source IS
  'FR-PR-04 display_name 값 출처. LDAP=디렉터리 동기화 대상, USER=사용자 편집(재로그인 보존). 기본 LDAP';
```
- V번호는 머지 직전 재확인(migration-vnumber-concurrent-branch-collision).
- 기존 users RowMapper/SELECT는 새 컬럼을 읽을 필요 시에만 확장(프로필 경로만 필요).

## 엣지 케이스

- **EC-1** source=USER인데 사용자가 다시 이름 편집 → 여전히 USER(멱등).
- **EC-2** 로컬 사용자가 이름 편집 → source=USER로 전환되지만 LDAP 재로그인이 없어 inert(무해).
- **EC-3** 재설정 후 다음 로그인 전 조회 → displayName=이전 편집값, source=LDAP(지연 동기화 안내 필요).
- **EC-4** 외부 계정 없는 사용자가 resync 호출 → 409(UI에선 애초에 미노출, 방어적 백엔드 가드).
- **EC-5** 동시성 — 편집(source=USER) 직후 재로그인 UPSERT: CASE 게이트가 READ_COMMITTED에서 커밋된 source 읽어 보존. (편집 트랜잭션 커밋 전이면 재로그인은 이전 source로 판단 → 다음 로그인에 수렴, 데이터 손상 없음)
- **EC-6** displayName 편집과 동시에 department 등 다른 필드도 PATCH → 단일 트랜잭션(기존 patchProfile), source 전환도 같은 tx.
- **EC-7** email은 여전히 매 로그인 LDAP 동기화 — 본 FR 범위 밖(변경 없음).

## 제약 조건

- **User RowMapper fanout 금지** — `display_name_source`·`ldapLinked`는 프로필 조회에서만 필요하므로, 공유 `User` 도메인/`UserRowMapper`에 컬럼을 추가하지 않는다(추가 시 8개 SELECT 전부로 파급). 프로필 전용 targeted 쿼리(예: `findDisplayNameSource(userId)` + `existsExternalAccount(userId)`)로 읽는다(surgical).
- **스키마 스냅샷 테스트 점검** — users 컬럼 추가가 SchemaMigrationTest 등 컬럼 카운트 가드에 걸리는지 확인(fr-pm-permission-seed-migration-test-coupling 류).
- **PATCH 요청 DTO 불변** — displayName 편집 시 source 전환은 서버가 자동 수행(클라이언트가 source를 직접 지정하지 않음). 재설정만 별도 엔드포인트. 같은 값 저장도 source=USER로 전환(명시적 저장=override 의도).
- **whoami 무변경** — Header 이름 표시는 users.display_name만 쓰므로 source 노출 불요(whoami mock fanout 회피).
- **모듈 격리** — identity-access 내부. cross-BC 없음.
- **보안** — resync는 본인(me-scope)만. 타 사용자 source 변경 경로 없음. 409 메시지에 내부정보 없음.
- **product 문서 정정** — `personalization.md §2.4 D3`을 users.display_name_source로 정정(같은 PR).

## 측정 가능한 완료 기준

- [ ] S1~S6 시나리오가 통합/단위 테스트로 검증됨
- [ ] LDAP 재로그인 통합테스트: source=USER 보존 + source=LDAP 동기화 둘 다 green(실 Testcontainers)
- [ ] GET /me/profile 응답에 displayNameSource·ldapLinked 노출(컨트롤러 테스트)
- [ ] POST resync 200/409 경로 테스트
- [ ] 프론트: 출처 배지 + override + 재설정 E2E(MSW)
- [ ] `personalization.md §2.4` 체크박스 D1~D7 `[x]` + product 문서 정정
- [ ] verify-master-plan.sh 통과(FR 카운트 무변, 상태만 갱신)

## Brainstorming Check

✅ 통과 (1회). gap 2건 발견 — 모두 plan에서 흡수(Maxi 결정 불요).
- Gap A(설계): User RowMapper fanout 회피 → 프로필 전용 targeted 쿼리(제약 조건 반영).
- Gap B(마이너): 같은 값 저장도 USER 전환 수용 + 스키마 스냅샷 테스트 점검(제약 조건 반영).
