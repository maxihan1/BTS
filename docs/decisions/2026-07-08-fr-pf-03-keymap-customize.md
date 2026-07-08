<!-- FR-PF-03 단축키 커스터마이즈의 도메인/아키텍처 결정 — 논리≠물리 BC, user_keymap 스키마, action 화이트리스트, 충돌 검출, single↔leader 자유 변환 ADR -->

# ADR — FR-PF-03 단축키 커스터마이즈: 도메인 모델 · user_keymap 스키마 · 충돌 검출

- 날짜: 2026-07-08
- 상태: 채택 (Accepted)
- 관련 FR: FR-PF-03
- 관련 PR: #248
- 선행: FR-UX-05(전역 단축키 커스텀 훅) · FR-PF-01(user_preferences) · FR-PR-01(personalization 첫 백엔드 → identity-access 물리 모듈)

## 맥락 (Context)

product 문서(`docs/plan/product/personalization.md §3.3`)는 FR-PF-03(단축키 커스터마이즈)의
D 단계를 다음과 같이 명세한다.

- D1. 도메인
- D2. 명세 — 충돌 검출
- D3. 데이터 모델 — `user_keymap(action, key_combo)`
- D4. 백엔드 — `GET/PATCH /api/v1/users/me/keymap`
- D5. 백엔드 테스트
- D6. 프론트 UI — 단축키 설정 + 실시간 reassign
- D7. E2E

FR-UX-05(ADR `2026-07-05-fr-ux-05-keymap.md`)는 전역 단축키 5종을
`apps/web/src/components/keyboard-shortcuts/shortcuts.ts`의 `SHORTCUTS` **코드 상수**로 하드코딩하고,
"커스텀 키맵(FR-PF-03, `user_keymap` 테이블)은 별도 FR로 이 작업 범위 밖"이라고 명시적으로 이 작업에 넘겼다.

FR-UX-05 단축키 5종.

| 논리 action | 기본 key_combo | trigger 종류 | 동작 |
|---|---|---|---|
| `help` | `?` | single | 도움말 모달 토글 |
| `create-issue` | `c` | single | `/issues/new` 이동 |
| `search` | `/` | single | `/search` 이동 |
| `goto-my-issues` | `g i` | leader | `/issues` 이동 |
| `goto-dashboard` | `g d` | leader | `/dashboards` 이동 |

**현재 SHORTCUTS에는 안정적 action 식별자가 없다.** `action.kind`는 `navigate`/`toggle-help`이며,
`navigate` 3종(`create-issue`·`search`·`goto-my-issues`·`goto-dashboard` 중 navigate 4종)이 kind를 공유해
키맵 저장의 식별자로 못 쓴다. 따라서 각 단축키에 **안정 action ID를 부여**하는 정규화가 이 작업의 전제다.

## 결정 (Decision)

### D1. BC — 논리 personalization / 물리 identity-access (선례 유지)

FR-PF-03의 백엔드는 논리 BC `personalization`에 속하지만 물리 모듈은 `identity-access`에 둔다.
FR-PR-01/02/03/04·FR-PF-01/02가 확립한 "personalization 논리 BC의 백엔드 = identity-access 물리 모듈"
패턴을 그대로 따른다. 프론트는 `apps/web`의 기존 `keyboard-shortcuts` 컴포넌트를 확장한다.
→ `fr-index.md`의 FR-PF-03 BC 매핑(`personalization`)은 불변. 카운트/BC 합계 영향 0.

### D2. 스코프 — 전역 5종 전부 커스터마이즈 + single↔leader 자유 변환 (Maxi 결정 2026-07-08)

- **대상**: FR-UX-05 전역 5종 전부(`help`·`create-issue`·`search`·`goto-my-issues`·`goto-dashboard`).
  도움말(`help`)도 재배치 대상에 포함한다.
- **자유 변환**: single↔leader 상호 변환을 허용한다. 즉 `c`(single)를 `g c`(leader)로,
  `g i`(leader)를 `x`(single)로 바꿀 수 있다. `key_combo`는 임의 1~2단계 키 시퀀스를 표현한다.

이는 가장 유연한 스코프이며, 그 대가로 **충돌 검출(D5)이 핵심 도메인 규칙**이 된다.

### D3. 도메인 엔티티 — UserKeymap (override 집합)

- **UserKeymap**(개념적 aggregate) — 한 사용자의 action→key_combo 오버라이드 집합.
- **action**(값) — 단축키가 실행하는 논리적 동작의 안정 식별자. 5종 화이트리스트.
  프론트 `SHORTCUTS`와 백엔드 화이트리스트 양쪽이 이 식별자를 단일 진실 출처로 공유한다.
- **key_combo**(값) — action에 배정된 키 시퀀스의 정규화 문자열. single은 한 키(`c`),
  leader는 공백 구분 2키(`g i`). 정규화 규칙은 spec에서 확정.
- **충돌(conflict)**(도메인 규칙) — 아래 D5.

### D4. 데이터 모델 — user_keymap (override 패턴, V033)

```
user_keymap(
  user_id   UUID   FK users(id) ON DELETE CASCADE,
  action    VARCHAR   -- 화이트리스트 5종 CHECK
  key_combo VARCHAR,
  ...
  UNIQUE(user_id, action)
)
```

- **override 패턴** — FR-PF-01(user_preferences)과 동일. 커스터마이즈하지 않은 action은 행이 없고,
  프론트 `SHORTCUTS`의 기본 key_combo를 사용한다. 재배치한 action만 행으로 존재한다.
- **action CHECK 화이트리스트** — DB 최후 방어선. 임의 action 저장 차단(FR-PF-02 start_page CHECK 선례).
- **마이그레이션 번호** — identity-access 모듈 V032가 최신 → **V033**. 머지 직전 재확인(V번호 동시 브랜치 충돌 방지).
- **하드 삭제(override 제거)** — 기본값 복원 시 override 행을 `DELETE ... WHERE user_id`로 즉시 제거한다(soft-delete 아님).
  개인 설정 토글이라 복구 가치가 낮고(favorites·saved_filters·dashboard_share_tokens 선례) 프론트 기본 키맵으로 언제든
  재구성 가능하므로 하드 삭제가 정당하다. DATA.md §1.2/§3 규칙에 따라 §3 하드 삭제 예외 목록에 `user_keymap`을 등록한다.
  (WHERE 절 필수 — DEVELOPMENT.md §1.2 #7 충족.)

### D5. 충돌 검출 — 완전 중복 + leader 접두 충돌 (백엔드 SSOT + 프론트 실시간)

single↔leader 자유 변환 때문에 두 종류의 충돌을 모두 검출한다.

1. **완전 중복** — 두 action이 같은 key_combo. (예: `create-issue=n`, `search=n`)
2. **leader 접두 충돌** — 한 action의 single 키가 다른 action의 leader 접두와 같음.
   (예: `help=g` single ↔ `goto-my-issues=g i` leader → `g` 누르면 즉시 발화 vs 시퀀스 대기가 모호)

- **빈 key_combo 금지** — 모든 5종 action은 항상 유효한 key_combo를 가져야 한다.
  도움말(`help`)을 빈 값으로 못 만들게 하여 재배치 후에도 도움말 접근 경로를 보장한다.
- **검증 위치** — 백엔드 PATCH가 검증의 단일 진실 출처(SSOT). 저장 전 전체 5종 유효 키맵에 대해
  충돌·화이트리스트·빈 값·형식을 검증하고 위반 시 409/400. 프론트는 실시간 UI 피드백용으로 같은 규칙을 복제.

### D6. 정규화 — SHORTCUTS에 안정 action ID 부여

FR-UX-05 `shortcuts.ts`의 `SHORTCUTS` 각 항목에 안정 action ID를 추가하고,
`resolveKeydown`이 하드코딩 기본값 대신 "기본값 + 사용자 override 병합" 키맵을 참조하도록 확장한다.
이는 same-BC(FR-UX-05가 이미 personalization 논리 BC) 프론트 view-layer 확장이라 BC 격리 위반 아님.

## 결과 (Consequences)

- **풀스택** — db(V033) + backend(도메인·API·충돌검출·테스트) + frontend(설정 UI·실시간 reassign·SHORTCUTS 정규화·병합) + qa(E2E).
  FR-UX-04/05가 프론트 전용이었던 것과 달리 FR-PF-03은 백엔드 저장이 명시돼 스코프가 크다.
- **product 문서 deviation 없음** — D3의 `user_keymap(action, key_combo)`, D4의 `GET/PATCH /api/v1/users/me/keymap`을 그대로 따른다.
  스코프 확정(전역 5종·자유 변환)은 D2 "충돌 검출" 명세를 구체화한 것이지 범위 변경이 아님.
- **새 용어** — `action`(단축키 논리 동작 식별자) · `key_combo`(키 시퀀스) · `keymap 충돌`. glossary 추가 후보(Maxi 승인 대기).
- **기존 결정 충돌** — 없음. FR-UX-05 ADR이 커스텀 키맵을 명시적으로 이 FR에 위임.
- **후속 연계** — 컨텍스트 의존 단축키(`j/k/e/m/s`, FR-UX-05에서 후속으로 미룸)가 도입되면
  같은 user_keymap·충돌 검출 위에 action을 추가하는 방식으로 확장된다.
