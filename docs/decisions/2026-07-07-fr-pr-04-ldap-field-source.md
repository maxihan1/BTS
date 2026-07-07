# ADR — FR-PR-04 LDAP 동기화 필드 vs 사용자 편집 분리: source 모델

> 날짜: 2026-07-07
> 상태: 결정됨 (Maxi 확정)
> 관련 FR: FR-PR-04 (LDAP 동기화 필드 분리)
> 선행 ADR: [2026-07-05-fr-pr-01-user-profile-placement](2026-07-05-fr-pr-01-user-profile-placement.md) D3 (source 컬럼 추가 여지)
> 관련 slug: fr-pr-04-ldap-vs-source

## 맥락

personalization BC(`docs/plan/product/personalization.md §2.4`)의 FR-PR-04. FR-PR-01 ADR D3가
"LDAP 동기화 필드 vs 사용자 편집 필드의 **출처 분리(source 컬럼)** 는 FR-PR-04 범위로 미룬다"로
경계를 그었고, 본 ADR이 그 후속을 확정한다.

### 핵심 사실 (코드 실측)

1. **LDAP 동기화는 로그인 시점(JIT)에만** 발생한다. `AutoProvisionService.provision()`이
   `UserRepository.provisionFromExternal()` → `SQL_PROVISION_UPSERT`(`ON CONFLICT (username) DO UPDATE
   SET email = EXCLUDED.email, display_name = EXCLUDED.display_name`)를 호출한다. 주기적 sync 잡은 없다.
2. LDAP이 제공하는 프로필 값은 `cn`(→ display_name) / `mail`(→ email)뿐이다(`LdapProvisionAttrs`).
3. 사용자 편집 가능 필드(FR-PR-01)는 `display_name`(users) / `timezone` / `department` / `avatar`(user_profiles).
4. **LDAP 동기화 ∩ 사용자 편집 = `display_name` 단 하나.** email은 현재 편집 불가,
   timezone/department/avatar는 LDAP이 접촉하지 않는다.

따라서 실제 충돌(사용자가 편집한 값을 LDAP 재로그인이 원복)은 **`display_name` 하나**에서만 발생한다.

## 결정

### D1. source 모델 = `users.display_name_source` 단일 enum 컬럼 (Option A)

`users` 테이블에 `display_name_source` 컬럼(값 `'LDAP'` | `'USER'`)을 추가한다. 범용 field-source
테이블이나 user_profiles 컬럼별 source를 만들지 않는다.

```
users (기존, V001 + 신규 컬럼)
  display_name         ← 편집/동기화 대상
  display_name_source  ← 'LDAP' | 'USER' (신규, DEFAULT 'LDAP')
```

**근거.**
- 실제 충돌 필드가 display_name 하나뿐이므로, 그 필드가 있는 `users` 테이블에 source 컬럼을 얹는 것이
  가장 작은 폭발 반경이다(CLAUDE.md §작업 기준 — 완제품이되 speculative 금지).
- 범용 테이블(Option B)은 email 편집 확대 등 지금 실익 없는 인프라를 미리 만든다(YAGNI). 미래에 LDAP
  동기화 대상 편집 필드가 추가되면 같은 `_source` 컬럼 패턴으로 확장한다.
- product 문서 D3의 "user_profiles 컬럼별 source"(Option C)는 핵심 충돌 필드 display_name이 users에
  있어 커버하지 못한다 → 문서-현실 부정합. 아래 §영향에서 product 문서를 정정한다.

### D2. 충돌 해소 = 편집 시 USER 전환, 재로그인은 source 게이트 (D4 실현)

- **사용자 편집** — `UserProfileService.patchProfile`이 display_name을 갱신할 때 동시에
  `display_name_source = 'USER'`로 전환한다(단일 트랜잭션).
- **LDAP 재로그인 UPSERT** — `SQL_PROVISION_UPSERT`의 `DO UPDATE`를 다음으로 바꾼다.

  ```sql
  SET display_name = CASE
        WHEN users.display_name_source = 'USER' THEN users.display_name   -- 사용자 편집 보존
        ELSE EXCLUDED.display_name END,                                    -- LDAP 동기화
      email        = EXCLUDED.email,   -- email은 편집 대상 아님 → 계속 LDAP 동기화
      updated_at   = NOW()
  ```

  `display_name_source` 자체는 LDAP 동기화가 바꾸지 않는다(USER 유지 = 영구 보존).

### D3. source 기본값/backfill = 'LDAP'

컬럼 `DEFAULT 'LDAP'`. 기존 행 backfill도 `'LDAP'`.

**근거.** 신규 LDAP 프로비저닝 사용자는 display_name이 LDAP cn에서 온 것이므로 'LDAP'이 정확하다.
로컬 계정(FR-AU-05 `create()`)의 display_name은 LDAP 출처가 아니지만, 로컬 사용자는 `provisionFromExternal`
경로를 타지 않아 source 값이 **무해(inert)** 하다(LDAP 재로그인이 없으므로 덮어쓸 일도 없음). 편집 시엔
어차피 'USER'로 전환된다. 시스템이 아직 prod 미배포라 backfill 정밀도 부담도 낮다.

### D4. 재동기화 = "LDAP 값으로 재설정" 지원

사용자가 편집한 이름을 다시 LDAP 동기화 대상으로 되돌리는 경로를 제공한다. `display_name_source`를
'USER' → 'LDAP'로 되돌리면 다음 LDAP 재로그인부터 cn으로 동기화가 재개된다.

- 백엔드 — profile PATCH에 재동기화 의도를 표현(예: `displayNameSource: "LDAP"` 또는 전용 액션). 상세는 spec.
- 프론트 — 프로필 페이지에서 "LDAP 값으로 재설정" 액션(외부 LDAP 계정이 연결된 사용자에게만 노출).

## 영향

- **신규 마이그레이션** — `users.display_name_source` 컬럼 추가(identity-access `db/migration/V0NN`,
  enum은 CHECK 제약 또는 VARCHAR + CHECK). `user_external_accounts`로 LDAP 소싱 여부 판별.
- **변경 SQL** — `SQL_PROVISION_UPSERT`의 display_name UPDATE에 CASE 게이트 추가. `SQL_UPSERT`(로컬 save)는
  현행 유지(로컬 경로는 source 무관).
- **변경 서비스** — `UserProfileService.patchProfile`이 display_name 편집 시 source='USER' 전환.
- **API/뷰** — profile 응답에 `displayNameSource` 노출(UI 라벨/편집 가드용). whoami 노출 여부는 spec.
- **프론트** — 필드별 "LDAP에서 동기화됨" 라벨 + "LDAP 값으로 재설정" 액션.
- **product 문서 정정** — `personalization.md §2.4 D3`의 "user_profiles 컬럼별 source 표시"를
  "users.display_name_source 단일 컬럼(실제 충돌 필드 display_name)"으로 정정한다(같은 PR에서 동기화).
