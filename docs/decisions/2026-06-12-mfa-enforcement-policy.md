<!-- FR-MF-04 MFA 강제 정책의 cross-BC 구조·enforcement 방식 결정을 기록하는 ADR -->
# ADR — MFA 강제 정책 (FR-MF-04)

> 날짜: 2026-06-12
> 상태: 채택
> 관련 FR: FR-MF-04
> 관련 PR: #123
> BC: identity-access (주) · issue-tracking · shared-kernel

## 맥락

FR-MF-01(TOTP)/FR-MF-02(백업 코드)로 MFA는 **opt-in** 상태다 — 사용자가 직접 켜야만 로그인 2단계가 적용되고, 미설정 사용자는 비밀번호만으로 세션을 받는다. SDD §19.7.2는 다음을 **강제**로 규정한다.

- 모든 관리자: 강제
- 민감 프로젝트 멤버(`project.require_2fa = true`): 강제
- 일반 사용자: 권장
- 외부 협력사: 강제 검토(미확정)

이를 구현하려면 (1) 누가 강제 대상인지 평가하고, (2) 강제 대상이 미설정이면 등록을 의무화해 차단해야 한다. 평가에 필요한 데이터가 두 BC에 나뉘어 있다 — 관리자 역할·프로젝트 멤버십은 identity-access(V012/V007), '민감 프로젝트' 표시는 issue-tracking `projects`(V001).

## 결정

### D1. 강제 대상 범위 = 관리자 + 민감 프로젝트 멤버

SDD '필수' 정의를 그대로 구현한다. 외부 협력사는 SDD가 '강제 검토'(미확정)로 둔 상태이므로 이번 범위에서 제외하고 후속으로 미룬다. FR을 쪼개지 않고 '필수' 정의를 1회 PR(+D6/D7 후속)로 완결한다.

### D2. `require_2fa`는 issue-tracking `projects`에, 평가는 shared-kernel resolver 포트 경유

`require_2fa BOOLEAN NOT NULL DEFAULT false` 컬럼을 issue-tracking `projects` 테이블에 추가한다(프로젝트 설정의 자연스러운 자리). issue-tracking은 jOOQ 코드 생성 모듈이므로 `init_codegen.sql`에도 미러한다.

cross-BC 평가는 shared-kernel에 `SensitiveProjectResolver` 포트를 두고 issue-tracking이 adapter를 제공한다 — 기존 `SystemPermissionResolver`(FR-PM-08) 선례와 동일하다. 포트 시그니처는 `UUID`만 사용해 identity 도메인 타입 역의존을 차단한다.

```
interface SensitiveProjectResolver {
    // 주어진 프로젝트 후보 중 require_2fa=true 인 것이 하나라도 있으면 true
    fun anyRequiresMfa(projectIds: Set<UUID>): Boolean
}
```

identity-access는 자기 BC의 `project_memberships`에서 사용자의 프로젝트 ID 집합을 구한 뒤 이 포트로 민감 여부를 질의한다. 멤버십 조회는 in-BC, 민감 표시 조회만 cross-BC다.

**fallback 등록 = `@ConditionalOnMissingBean`(프로파일 무관) + WARN.** `SensitiveProjectResolver`는 `JwtIssuer`→`MfaEnforcementPolicy`의 hard-dependency라 모든 identity-access 컨텍스트에 빈이 존재해야 한다. assembled 부팅에서는 issue-tracking의 실 adapter가 그 자리를 채운다. identity-access **단독** 부팅(prod·비prod 테스트, issue-tracking 미조립)에서는 실 빈이 부재하므로, `@ConditionalOnMissingBean(SensitiveProjectResolver::class)` fallback(`NonProdSensitiveProjectResolver`, `anyRequiresMfa`=항상 false)을 등록해 부팅 가용성을 확보한다. 프로파일과 무관하게 '실 빈이 없을 때만' 등록되므로 assembled 우선이 보장된다. fallback이 실제로 켜지면 시작 시 WARN 로그(`SensitiveProjectResolver 실 adapter 부재 — fallback 활성 …`)로 misassembled를 관측 가능하게 둔다. 구현 메모: `@ConditionalOnMissingBean`은 컴포넌트 스캔된 `@Component`에서는 등록 순서에 의존해 신뢰성이 없어, `@Configuration`의 `@Bean` 메서드(`SensitiveProjectResolverFallbackConfig`)에서 평가한다.

**대안(기각).** identity-access가 `mfa_enforcement_policy` 테이블을 자체 소유 → cross-BC 0이지만 '프로젝트 설정'이 두 BC로 갈라져 일관성이 깨진다. 프로젝트 관리자가 한 곳(issue-tracking)에서 설정하는 게 자연스럽다.

### D3. enforcement = whoami 플래그 + 백엔드 게이트

FR-AU-05 `mustChangePassword` 선례를 따른다.

- 로그인은 정상 세션을 발급한다(별도 로그인 흐름 재설계 없음 → 회귀 0).
- whoami가 `mfaEnrollmentRequired = mfaRequired(user) AND NOT mfaService.isEnabled(user)`를 노출한다.
- 프론트(D6 후속)가 이 플래그로 MFA 설정 화면을 강제한다.
- **백엔드 게이트**: 미등록 강제 대상의 요청은 enrollment 관련 API(MFA 설정/검증) + whoami + logout을 제외하고 차단한다. FR-AU-05는 백엔드 전역 차단을 후속으로 미뤘으나, MFA는 프론트만 게이팅하면 직접 API 호출로 우회 가능하므로 이번엔 백엔드 차단을 포함한다(보안 정합).

**상태 영속 없음.** `mfaEnrollmentRequired`는 매 요청 계산값이다. identity-access에 새 마이그레이션이 필요 없다. 유예 기간(grace)은 두지 않는다(SDD 미명시 + 추적 상태 복잡도 회피).

### D4. PR 범위 = 백엔드 먼저

정책 평가 + resolver + whoami 노출 + 백엔드 게이트 + 통합 테스트까지 이 PR. 프론트 게이팅 UI + Playwright E2E는 D6/D7 후속 PR. FR-MF-01/02와 동일한 분할.

## 결과

- issue-tracking: 마이그레이션 1건(`require_2fa`) + init_codegen 미러 + 토글 엔드포인트(프로젝트 관리자 게이트) + resolver adapter.
- shared-kernel: `SensitiveProjectResolver` 포트.
- identity-access: `MfaEnforcementPolicy` 평가 서비스 + whoami 필드 + 백엔드 게이트(filter/interceptor) + 통합 테스트.
- 회귀 표면: whoami 응답에 필드 추가(프론트 Zod 인라인 mock 전수 갱신 필요) + 게이트가 기존 인증 흐름을 막지 않도록 allow-list 정밀 설계.

## 위험 / 후속

- **게이트 allow-list 누락 위험** — 차단 예외(enrollment/whoami/logout) 경로를 빠뜨리면 강제 대상이 MFA 설정조차 못 해 영구 락. 통합 테스트로 'enrollment 경로는 통과, 그 외 차단' 양방향 검증.
- **resolver fail-open 금지(부팅 가용성과 양립)** — cross-BC resolver는 nullable 의존성 `?: return`로 두지 않고 non-null 빈으로 보장해 fail-open(빈 부재 시 silent 우회)을 차단한다(`crossbc-resolver-nullable-fail-open` 선례). 단, 빈 보장 방식은 '빈 부재 시 부팅 차단(loud-fail)'이 아니라 '`@ConditionalOnMissingBean` fallback 등록'으로 한다. 초기엔 fallback을 `@Profile("!prod")`로 두어 prod 단독 부팅 시 빈 미해소→BeanCreationException으로 loud-fail시켰으나, 배포 모델(`no-cross-bc-deployment-assembly`)상 'issue-tracking 없는 standalone-prod' 시나리오 자체가 없어 loud-fail로 막을 대상이 없었고, 오히려 identity-access 단독 `@ActiveProfiles("prod")` 통합테스트(약 20건)의 컨텍스트 로드를 깨뜨리는 회귀를 유발했다. 따라서 loud-fail을 폐기하고 프로파일 무관 `@ConditionalOnMissingBean` + WARN로 전환한다. fallback의 안전 기본(`anyRequiresMfa`=false)은 강제를 '덜 켜는' 방향이라 fail-safe이며, 회귀 가드(`SensitiveProjectResolverFallbackBootTest`, prod 프로파일 부팅)로 재발을 차단한다.
- 외부 협력사 강제(SDD '강제 검토')는 SDD 확정 후 후속 FR.
- D6/D7 프론트 게이팅 + E2E 후속.
