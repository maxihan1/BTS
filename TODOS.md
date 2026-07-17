<!-- 후속 기술부채 추적 — 리팩토링이 필요하나 현재 PR 범위 밖이라 보류한 항목 기록 -->

# TODOS

## identity-access — 컨트롤러 권한 게이트 DRY 부채 (D19, PR #277)

**결정 (Maxi 확정, plan-eng-review D19)**. `FORBIDDEN_RESPONSE`/`UNAUTHORIZED_RESPONSE` 상수 · `resolveActorId` ·
`requireSystemAdmin` 3종 복제는 **선재 부채**다. `global_permission_grants`(FR-PM-10, PR-1)가 신설한
`GlobalPermissionGrantController`가 기존 패턴을 한 벌 더 복제했다. **공통화는 지금 하지 않는다** — 공통화하면
PR-1이 N파일 리팩토링이 되어 글로벌 CLAUDE.md §3(surgical, 변경은 요청받은 것만)과 충돌하고 권한 PR의 리뷰
단위가 무너진다. 대신 복사 + 이 기록으로 후속 작업을 명시한다.

**중복 3종 (2026-07-17 grep 재확인 — PR #277 T4/T5/T6 반영 후 실측치, 추측 아님)**.

| 패턴 | 파일 수 (src/main) | 재확인 명령 |
|---|---|---|
| `FORBIDDEN_RESPONSE`/`UNAUTHORIZED_RESPONSE` 상수 | 14 | `grep -rlE "FORBIDDEN_RESPONSE\|UNAUTHORIZED_RESPONSE" backend/modules/identity-access/src/main --include="*.kt"` |
| `resolveActorId` 함수 | 7 | `grep -rl "resolveActorId" backend/modules/identity-access/src/main --include="*.kt"` |
| `requireSystemAdmin` 함수 | 3 | `grep -rl "fun requireSystemAdmin" backend/modules/identity-access/src/main --include="*.kt"` |

> ⚠️ **`requireSystemAdmin`은 plan D19가 인계한 "5파일"과 다르다.** plan-eng-review 원안(`docs/plans/2026-07-17-project-management-crud.md:1312`)은 pre-T6 기준 "4파일"로 적었고, T6이 `GlobalPermissionGrantController`에 1벌을 더 복제해 "5파일"이 될 것으로 예상했다(구두 인계). **grep 재확인 결과 실제로는 3파일**뿐이다 — `GlobalPermissionGrantController` · `IssueSecuritySchemeController` · `UserGroupController`. plan의 "4파일" 원안 자체가 애초 과다 계상이었던 것으로 보인다([[spec-stated-count-becomes-blindfold]] 재발 — 숫자를 그대로 물려받지 말 것). 후속 리팩토링 착수 시 이 grep 명령으로 다시 재검증할 것.
>
> `resolveActorId`는 검색 결과 8개 파일에서 매치되나, 1개(`GlobalPermissionGrantControllerTest.kt`)는 KDoc/주석에서 개념을 언급할 뿐 실제 중복 구현이 아니라 제외했다(`src/main`만 집계).

**후속 작업**. 공통 베이스 클래스 또는 shared-kernel 유틸로 추출 — 별도 PR로 분리해 리팩토링 리뷰 단위를 권한 변경과 섞지 않는다.

## identity-access — ADR D-1 이중 방어의 "두 겹이 같은 집합" 정합 무가드 (PR #277 codereview)

**결정 (Maxi 확정, 2026-07-17 게이트 2)**. **후속으로 미룬다.** ADR이 이미 **잔여 위험 4**로 의식적으로 수용한
항목이고, 두 집합이 **현재 일치하므로 잠복 부채이지 현행 결함이 아니다**. 가드를 넣으려면 private companion
상수에 리플렉션을 걸거나 상수를 `internal`로 승격해야 하는데(prod 코드 변경), 권한 PR의 리뷰 단위를 흐린다.

**무엇이 안 잠겨 있나**. ADR D-1의 이중 방어는 **두 겹이 같은 집합**이어야 성립한다.

| 겹 | 위치 | 값 |
|---|---|---|
| 앱 | `GlobalPermissionGrantService.kt:129` | `val ALLOWED_GLOBAL_PERMISSIONS = setOf("CREATE_PROJECT")` |
| DB | `V036__global_permission_grants.sql:26` | `CHECK (permission IN ('CREATE_PROJECT'))` |

**두 값을 함께 읽는 테스트가 0건이다.** 서비스 KDoc `:34-36`이 스스로 *"🛑 권한코드 추가 시 3곳을 동시에
갱신한다(ADR 잔여 위험 4) … 하나만 놓치면 fail-closed로 조용히 막힌다"*라고 **수동** 동기화를 지시하는데,
그 지시를 강제하는 자동 가드가 없다. BTS가 FR 카운트 drift에 `verify-master-plan.sh`를 붙인 것과 같은 종류의 부채다.

**드리프트 방향별 결과**.
- 화이트리스트만 확장 → 서비스 통과 후 DB CHECK 위반 → `DataIntegrityViolationException` → **400이어야 할 것이 500으로 변질** (서비스 KDoc `:31-32`가 예고한 바로 그 변질)
- CHECK만 확장 → 부여가 **400으로 조용히 거부**

**후속 작업**. `GlobalPermissionGrantSchemaMigrationTest`에 "화이트리스트 전량이 V036 CHECK를 통과하는지"
확인하는 테스트 추가(`@JdbcTest` 자동 롤백에 기댄다). 상수가 private companion이라 리플렉션이 필요하며,
꺼려지면 `internal`로 승격해 직접 참조하는 편이 깔끔하다. **빈 집합이면 루프가 vacuous하게 통과하므로
`assertThat(allowed).isNotEmpty()` 선단언 필수**([[verify-logic-vs-verify-guard]]).
