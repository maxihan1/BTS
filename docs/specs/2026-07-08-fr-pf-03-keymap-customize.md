<!-- FR-PF-03 단축키 커스터마이즈 스펙 — user_keymap 저장, 충돌 검출, GET/PATCH keymap API, 설정 UI, E2E -->

# FR-PF-03 단축키 커스터마이즈 — 스펙

- 날짜: 2026-07-08
- FR: FR-PF-03 (personalization 논리 BC / identity-access 물리 모듈)
- 선행: FR-UX-05(전역 단축키) · FR-PF-01(user_preferences 패턴)
- ADR: [decisions/2026-07-08-fr-pf-03-keymap-customize.md](../decisions/2026-07-08-fr-pf-03-keymap-customize.md)

## 배경 — 무엇을 커스터마이즈하나

FR-UX-05가 전역 단축키 5종을 `apps/web/src/components/keyboard-shortcuts/shortcuts.ts`의
`SHORTCUTS` 코드 상수로 하드코딩했다. FR-PF-03은 각 단축키의 **동작(action)은 고정**하고
**키(key_combo)만 사용자가 재배치**하게 한다. action별 안정 식별자 5종.

| action ID | 기본 key_combo | 기본 trigger | 동작 |
|---|---|---|---|
| `help` | `?` | single | 도움말 모달 토글 |
| `create-issue` | `c` | single | `/issues/new` 이동 |
| `search` | `/` | single | `/search` 이동 |
| `goto-my-issues` | `g i` | leader | `/issues` 이동 |
| `goto-dashboard` | `g d` | leader | `/dashboards` 이동 |

**스코프 (Maxi 결정 2026-07-08)**. 5종 전부 재배치 가능(도움말 포함) + single↔leader 자유 변환.

## key_combo 형식 (정규화)

- **single**. 수정자 없는 한 키. 정규화 문자열 = 그 키 1글자. 예: `c`, `?`, `/`, `n`, `x`.
- **leader**. leader 키 `g` 직후(타임아웃 내) 한 키. 정규화 문자열 = `g <key>`(공백 1칸). 예: `g i`, `g c`.
- **leader 키는 `g` 고정**. FR-UX-05 `LEADER_KEY='g'`를 유지한다. "자유 변환"은 single ↔ (`g` + 키) leader
  사이 변환을 뜻하며, leader 키 자체(`g`)를 다른 문자로 바꾸는 것은 이 FR 범위 밖(YAGNI, 추가 시 후속).
- **수정자/IME 불가**. Ctrl/Alt/Meta 조합, IME 조합키는 커스텀 키로 지정 불가(single 또는 `g`-leader만).
  (`cmd+k`는 FR-UX-04 팔레트 전용이라 keymap 대상 아님.)

## 사용자 시나리오 (Given-When-Then)

- **S1 조회**. Given 로그인 사용자, When `/settings/keymap` 진입, Then 5종 action의 현재 key_combo
  (저장 override 또는 기본값)가 표시된다.
- **S2 single 재배치**. Given `create-issue=c`(기본), When `create-issue`를 `n`으로 변경·저장, Then
  이후 `n` 키가 새 이슈 폼을 연다. `c`는 더 이상 발화하지 않는다.
- **S3 single→leader**. When `create-issue`를 `g c`로 변경·저장, Then `g` 후 `c` 시퀀스가 새 이슈 폼을 연다.
- **S4 leader→single**. When `goto-my-issues`를 `x`로 변경·저장, Then `x` 키가 내 이슈로 이동한다.
- **S5 완전중복 충돌**. Given `create-issue=c`, When `search`를 `c`로 변경 시도, Then 충돌 오류로 저장 거부.
- **S6 leader 접두 충돌**. Given `goto-my-issues=g i`(leader), When `help`를 `g`(single)로 변경 시도, Then
  `g`가 leader 접두와 겹쳐 충돌 오류로 저장 거부(`g` 즉시 발화 vs 시퀀스 대기 모호).
- **S7 빈 값 거부**. When 어떤 action의 key_combo를 빈 값으로 저장 시도, Then 거부(도움말 접근 경로 보장 포함).
- **S8 기본 복원**. Given 재배치된 action, When "기본값으로 복원", Then 해당 action이 기본 key_combo로 돌아가고
  override가 제거된다(effective = 기본값).
- **S9 부분 저장자**. Given 일부 action만 저장한 사용자, When 조회, Then 저장한 action은 override, 나머지는 기본값.

## 기능 요구사항 (FR)

- **FR1 조회**. `GET /api/v1/users/me/keymap` — 5종 effective 키맵(저장 override + 기본값 병합) 반환.
  행 없는 사용자는 전부 기본값(preferences lazy 패턴).
- **FR2 재배치(replace-all)**. `PATCH /api/v1/users/me/keymap` — 5종 전체 bindings를 받아 검증 후 저장.
  충돌 검출이 전체 집합 대상이므로 replace-all 시맨틱(FR-SR-03 저장필터 선례). 검증 실패 시 어떤 write도 없음
  (preferences "부분 적용 없음" 원칙).
- **FR3 충돌·형식 검증** (백엔드 SSOT). 저장 전 다음을 모두 검사, 위반 시 거부.
  1. **action 화이트리스트**. 정확히 5종(`help`·`create-issue`·`search`·`goto-my-issues`·`goto-dashboard`) 완비.
  2. **key_combo 형식**. single(1글자) 또는 `g <key>` leader. 그 외 형식 거부.
  3. **빈 값 금지**. 모든 action의 key_combo가 비어있지 않음.
  4. **완전 중복 금지**. 두 action이 같은 정규화 key_combo를 가지지 않음.
  5. **leader 접두 충돌 금지**. single `g`가 존재하면서 leader(`g X`)가 함께 존재하면 거부.
  6. **dead leader combo 금지**. leader continuation 키가 leader 키 `g`와 같은 `g g`는 거부 —
     FR-UX-05 로직상 두 번째 `g`가 시퀀스를 재시작(E3)해 영원히 발화하지 않는 dead combo다.
- **FR4 저장 정규화**. 기본값과 동일한 action은 override 행을 저장하지 않는다(또는 삭제) — "행 없으면 기본값"
  패턴 유지. 기본값과 다른 action만 `user_keymap`에 upsert.
- **FR5 프론트 SHORTCUTS 정규화**. `shortcuts.ts` `SHORTCUTS` 각 항목에 안정 action ID 부여.
  `resolveKeydown`이 하드코딩 기본값이 아니라 "기본값 + 사용자 override 병합" 키맵을 참조하도록 확장.
  (same-BC 프론트 view-layer 확장 — BC 격리 위반 아님.)
  - **FR5-a 도움말 닫기 키 동기화**. `resolveKeydown`의 도움말 열림 분기(`e.key === '?'` 하드코딩)도
    `help` action의 effective key_combo를 참조해야 한다 — `help`를 재배치하면 도움말을 **닫는** 키도
    그 combo를 따라간다(그렇지 않으면 재배치 후 `?`로는 못 닫는 stale 발생).
- **FR5-b 초기 로드 반영**. 앱 부트 시(로그인 상태) 사용자 override를 keymap 훅이 사용해야 첫 페이지부터
  커스텀 키가 동작한다. **경로 결정은 plan에서 확정** — (a) whoami view-layer에 bindings 노출(preferences
  theme/startPage 선례, 1 라운드트립·whoami 무거워짐) vs (b) 앱 부트 시 `GET /me/keymap` 1회 + react-query
  캐시(whoami 경량 유지·마운트 시 1 요청). 비로그인은 GET 없이 기본값(FR-UX-05 enabled 가드).
- **FR6 설정 UI**. `/settings/keymap` 페이지. 5종 action별 현재 키 표시 + 재배치(키 입력 캡처) +
  실시간 충돌 표시(백엔드 규칙 복제) + 저장 + action별 기본 복원. settings.preferences 레이아웃 선례.
- **FR7 실시간 반영**. 저장 성공 시 keymap 조회 무효화 → 병합 키맵 갱신 → 단축키가 새 키로 즉시 동작
  (재로그인/새로고침 불필요).
- **FR8 E2E**. 재배치→발화, 충돌 거부, 기본 복원, 빈 값 거부 시나리오.

## 비기능 요구사항 (NFR)

- keymap 조회 p95 100ms(§NFR 프로필 조회 대응). 단축키 동작률 100%(E2E 전수).
- 설정 페이지 WCAG 2.1 AA(키 입력 캡처의 스크린리더 접근성 포함).
- 보안: JWT-only(PAT 403 — session/preferences 선례). 본인 키맵만 접근(userId=JWT subject).

## API 인터페이스 (REST)

`@RequestMapping("/api/v1/users")` — PreferencesController 미러(JWT-only, 로컬 @ExceptionHandler).

### GET `/me/keymap`
200 응답.
```json
{
  "bindings": [
    { "action": "help",            "keyCombo": "?",   "trigger": "single", "customized": false },
    { "action": "create-issue",    "keyCombo": "n",   "trigger": "single", "customized": true  },
    { "action": "search",          "keyCombo": "/",   "trigger": "single", "customized": false },
    { "action": "goto-my-issues",  "keyCombo": "g i", "trigger": "leader", "customized": false },
    { "action": "goto-dashboard",  "keyCombo": "g d", "trigger": "leader", "customized": false }
  ]
}
```
- `trigger`는 keyCombo에서 파생(`g `로 시작+2토큰=leader, 아니면 single). `customized`=기본값과 다른지.

### PATCH `/me/keymap`
요청(replace-all, 5종 완비).
```json
{ "bindings": [ { "action": "create-issue", "keyCombo": "n" }, ... 5종 전체 ] }
```
- 200: 갱신 후 GET과 동일 형식.
- 400 `KEYMAP_VALIDATION_FAILED`: 화이트리스트/형식/빈값 위반. `{ "code", "message" }`(preferences 형식).
- 409 `KEYMAP_CONFLICT`: 완전중복/leader접두 충돌. `{ "code", "message", "conflicts": [...] }`(어떤 action이 겹치는지).

## 데이터 모델 변경

`user_keymap` (V033, identity-access 모듈).
```sql
CREATE TABLE user_keymap (
    user_id   UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action    VARCHAR(32) NOT NULL,
    key_combo VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, action),
    CONSTRAINT user_keymap_action_chk CHECK (action IN
        ('help','create-issue','search','goto-my-issues','goto-dashboard'))
);
```
- override 패턴. 기본값과 다른 action만 행 존재. PK(user_id, action)로 action별 1행.
- action CHECK = DB 최후 방어선(FR-PF-02 start_page CHECK 선례).
- V033 (identity-access V032 최신). 머지 직전 V번호 재확인(동시 브랜치 충돌 방지).

## 엣지 케이스

- **E1 `?` 정규화**. `?`는 `Shift+/`지만 `e.key==='?'`로 들어온다. single 1글자로 취급, Shift는 형식 검증에서
  수정자로 안 봄(shouldIgnoreEvent가 Shift 허용 — FR-UX-05 FR6).
- **E2 대소문자**. key_combo는 입력된 `e.key` 그대로 저장(대문자 구분). 스펙 MVP는 소문자 권장·표시.
- **E3 single `g` 단독**. leader 시퀀스가 하나라도 있으면 `g` single은 접두 충돌(FR3-5). leader가 0개면
  이론상 허용되나, 기본 키맵에 leader 2종이 있으므로 사용자가 둘 다 single로 바꾸지 않는 한 `g` single 불가.
- **E4 저장값이 기본과 동일**. FR4 정규화로 행 미저장(effective 동일).
- **E5 leader 접두 충돌 방향**. single `g` vs leader `g X`만 접두 충돌. 다른 single 키는 leader(항상 2토큰)와
  절대 충돌 안 함. leader끼리·single끼리는 완전중복만.
- **E6 입력 캡처 중 예약키**. 설정 UI 키 캡처 중 Esc(취소)·Enter(확정)는 캡처 대상에서 제외.
- **E7 orphan override**. action 화이트리스트 밖 행(구 버전 잔재)은 CHECK로 애초에 저장 불가 + GET에서 무시.

## 제약 조건

- **JWT-only**. PAT 403(session-management-pat-exclusion 선례).
- **BC 격리**. 백엔드=identity-access 물리 모듈. 프론트 SHORTCUTS 확장=same-BC(FR-UX-05 논리 personalization) view-layer.
- **product deviation 없음**. D3/D4 스키마·엔드포인트 그대로. 스코프 확정은 D2 "충돌 검출" 구체화.

## 측정 가능한 완료 기준

- [ ] V033 마이그레이션 + jOOQ/JdbcTemplate repository (init_codegen 미러 확인)
- [ ] GET/PATCH `/me/keymap` — JWT-only, 검증 우선, override 정규화
- [ ] 충돌 검출 5종(화이트리스트·형식·빈값·완전중복·leader접두) 백엔드 단위 테스트
- [ ] 백엔드 통합 테스트(400/409/200, 부분 저장자, PAT 403)
- [ ] 프론트 SHORTCUTS action ID 정규화 + resolveKeydown 병합 키맵 참조(단위 테스트 무회귀)
- [ ] `/settings/keymap` UI — 재배치·실시간 충돌·기본 복원
- [ ] keymap 조회/저장 mutation invalidate 실시간 반영
- [ ] E2E: 재배치→발화, 충돌 거부, 기본 복원
- [ ] 전수 동기화(fr-index D체크박스, product D단계, SDD, README, verify-master-plan 통과)

## Brainstorming Check

✅ 통과 (self adversarial sanity check, 1회). gap 3건 발견 후 보강.
- G1 → FR5-a: `help` 재배치 시 도움말 닫기 키(`resolveKeydown` `?` 하드코딩) 동기화.
- G2 → FR3-6: `g g` dead leader combo 형식 거부.
- G3 → FR5-b: 초기 로드 override 반영 경로(whoami vs 부트 GET) plan 확정 위임.
