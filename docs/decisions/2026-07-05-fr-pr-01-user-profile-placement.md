# ADR — FR-PR-01 사용자 프로필: 모듈 배치 + user_profiles 테이블 설계

> 날짜: 2026-07-05
> 상태: 결정됨 (Maxi 확정)
> 관련 FR: FR-PR-01 (사용자 프로필), FR-PR-04 (LDAP 동기화 필드 분리 — 후속)
> 관련 slug: fr-pr-01-user-profile

## 맥락

personalization BC(product 문서 `docs/plan/product/personalization.md §2.1`)의 첫 백엔드 FR. 두 가지 미결 결정이 코드 위치와 데이터 모델을 좌우했다.

1. **모듈 배치** — personalization은 논리적 BC일 뿐 백엔드 모듈이 없다. 프로필 코드를 어디에 둘 것인가.
2. **테이블 설계** — `users.display_name`이 V001에 이미 존재(LDAP cn으로 채워짐)하는데 product 문서는 `user_profiles(user_id, display_name, avatar_url, timezone, department)` 별도 테이블을 명시. display_name 중복/출처 문제가 FR-PR-04와 맞물린다.

## 결정

### D1. 모듈 = identity-access

프로필 백엔드는 신규 personalization 모듈이 아니라 **identity-access** 모듈에 둔다.

**근거.**
- `users` 테이블과 `/api/v1/users/me/*` 엔드포인트(`UsersController`, `WhoamiController`, `PreferencesController` stub)가 이미 identity-access에 있다.
- UserProfile은 User와 1:1 관계라 같은 모듈이면 cross-BC 우회 없이 JOIN 가능. 별도 모듈이면 User를 읽기 위해 cross-BC 이벤트/API가 필요(BC 격리 규칙) → 부팅 배선·복잡도 증가.
- 선례: FR-UX-01(퀵 필터)도 논리 BC(personalization) ≠ 물리 모듈(agile-planning)로 배치. "논리 BC ≠ 물리 모듈" 패턴 확립됨.

### D2. 테이블 = user_profiles 신설 + display_name은 users 유지 (Option A)

```
users (기존, V001)
  display_name  ← '이름' 편집 대상 (여기 유지, 이관 안 함)

user_profiles (신규)
  user_id       PK/FK → users.id (ON DELETE CASCADE)
  avatar_url    (nullable)
  timezone      (기본값 정책은 spec에서)
  department    (nullable)
```

**근거.**
- `users.display_name`을 중복/이관하지 않아 폭발 반경 최소. whoami·LDAP provision 등 기존 소비처를 건드리지 않는다.
- 프로필 조회 = `users JOIN user_profiles`. 이름 편집 = `users.display_name` 업데이트. 아바타/타임존/부서 편집 = `user_profiles` 업데이트.
- product 문서의 테이블 이름(`user_profiles`)은 유지하되, display_name 컬럼만 users에 남겨 단일 출처 보장.

**기각.**
- Option B (product 문서 그대로 display_name 이관 + source 컬럼): users.display_name 소비처 전수 이관 필요 → 폭발 반경 큼. FR-PR-04(소스 분리)를 FR-PR-01로 당겨 스코프 확대.
- Option C (별도 테이블 없이 users에 컬럼 추가): product 데이터 모델(`user_profiles`)과 어긋남, users 테이블 비대.

### D3. FR-PR-04 경계

LDAP 동기화 필드 vs 사용자 편집 필드의 **출처 분리(source 컬럼/매트릭스)** 는 FR-PR-04 범위로 미룬다. FR-PR-01은 프로필 CRUD + 아바타 업로드까지. 단 D2 테이블 설계가 FR-PR-04를 막지 않도록(source 컬럼 추가 여지 유지) 한다.

### D4. 아바타 저장 = identity-access 자체 MinIO 배선

MinIO는 issue-tracking(`MinioStorageAdapter`/`MinioStorageConfig`)·search-export-import에 선례가 있으나 모듈 격리상 공유 클라이언트가 없다. identity-access에 자체 MinIO 배선을 신설하고 issue-tracking 첨부의 보안 패턴(MIME 화이트리스트·크기 제한·`Content-Disposition`/nosniff)을 재사용한다. 상세(ClamAV 스캔 여부·크기 상한·허용 MIME)는 spec에서 확정.

## 영향

- 신규 마이그레이션: `user_profiles` 테이블 (identity-access `db/migration/V0NN`)
- 신규 엔드포인트: `GET/PATCH /api/v1/users/me/profile`, 아바타 업로드 엔드포인트 (spec에서 확정)
- 신규 도메인: `UserProfile`
- FR-PR-04는 본 테이블에 source 컬럼을 얹는 후속 작업
