# 20. 개인화

## 20.1 설계 원칙

- 사용자가 시스템을 자기 방식대로 사용
- 알림은 시끄럽지 않게, 중요한 것만
- 자주 쓰는 항목은 빠르게 접근
- 부재중일 때는 자동 처리

## 20.2 UserProfile

```kotlin
data class UserProfile(
    val userId: Long,
    val displayName: String,        // 사용자 편집 (LDAP 동기화 옵션)
    val avatarUrl: String?,         // 사용자 업로드
    val timezone: String,           // "Asia/Seoul"
    val locale: String,             // "ko-KR"
    val department: String?,        // LDAP 동기화
    val title: String?,             // LDAP 동기화
    val managerId: Long?,           // LDAP 동기화
    val statusEmoji: String?,       // "🌴"
    val statusText: String?,        // "휴가 중"
    val statusUntil: Instant?,      // 상태 만료
    val outOfOfficeUntil: LocalDate?,  // 부재중
    val outOfOfficeDelegate: Long?,    // 위임 대상
)
```

### 부재중 (Out of Office)
- 활성화 시 모든 알림에 "부재중" 표시
- 새 할당은 위임 대상에게 자동 알림
- 부재중 기간 후 자동 해제

## 20.3 UserPreferences

```kotlin
data class UserPreferences(
    val userId: Long,
    val theme: Theme,               // LIGHT / DARK / SYSTEM
    val language: String,           // "ko" / "en"
    val dateFormat: String,         // "YYYY-MM-DD"
    val timeFormat: String,         // "24h" / "12h"
    val density: Density,           // COMPACT / COZY / COMFORTABLE
    val startPage: String,          // "/", "/issues", "/dashboards/1"
    val defaultBoard: Long?,
    val keyboardShortcuts: Map<String, String>,  // 커스텀 단축키
)
```

## 20.4 NotificationSubscription

```kotlin
data class NotificationSubscription(
    val userId: Long,
    val scope: String,              // "global" / "project:PROJ"
    val channelPreferences: Map<String, List<String>>,
    // 예: {"issue.assigned": ["inapp", "email", "slack"]}
    val quietHoursStart: LocalTime?,  // 22:00
    val quietHoursEnd: LocalTime?,    // 07:00
    val quietHoursDays: List<DayOfWeek>,
    val dailyDigest: Boolean,         // 일일 요약
    val digestTime: LocalTime,        // 09:00
)
```

전역 + 프로젝트별 오버라이드.

## 20.5 즐겨찾기 (Favorites)

```kotlin
data class Favorite(
    val id: Long,
    val userId: Long,
    val targetType: FavoriteType,   // PROJECT / ISSUE / FILTER / DASHBOARD / BOARD
    val targetId: Long,
    val displayName: String,        // 캐시된 이름
    val sortOrder: Int,
    val createdAt: Instant,
)
```

사이드바 또는 홈 대시보드에서 빠른 접근.

## 20.6 최근 본 항목 (Recently Viewed)

- 자동 기록 (이슈/페이지 접근 시)
- 최대 50개 보존
- 사이드바 표시
- 검색에서 가중치 부여

## 20.7 활동 피드

자기 활동 + 팔로우 대상 활동:
- 내가 한 작업 (생성/수정/댓글)
- 내 이슈에 일어난 일
- 즐겨찾기한 프로젝트의 활동

## 20.8 개인 캘린더 (FR-CA)

### 통합 일정 소스
- 할당된 이슈의 due_date
- 시작한 Worklog
- 스프린트 시작/종료
- 위키 페이지 reminder (v0.5)

### iCal Export
- 사용자별 고유 URL (토큰 기반)
- 외부 캘린더에서 구독 (Google Calendar, Outlook)
- 자동 갱신 (15분 캐시)

```
https://atlas.company.com/api/v1/users/me/calendar.ics?token=...
```

## 20.9 키보드 단축키 (FR-UX-05)

> **구현 현황 (2026-07-05, ADR [decisions/2026-07-05-fr-ux-05-keymap.md](../decisions/2026-07-05-fr-ux-05-keymap.md))**. MVP는 **전역 네비게이션 5종 + 도움말**(`?`·`c`·`/`·`g i`·`g d`)을 커스텀 훅(의존성 0)으로 구현 완료. 컨텍스트 의존 단축키(`j/k`·`e`·`m`·`s`)는 이슈 상세/보드와의 깊은 결선이 필요해 후속 FR로 미룸 — **그 승계자가 FR-UX-10** (§20.10). `cmd+k`는 FR-UX-04에서 구현. 사용자 커스텀 키맵은 FR-PF-03.

표준 단축키 + 사용자 커스텀:

| 단축키 | 동작 |
|---|---|
| `?` | 단축키 도움말 |
| `c` | 이슈 생성 |
| `g i` | 내 이슈 |
| `g d` | 대시보드 |
| `/` | 검색 |
| `j / k` | 다음/이전 (보드, 리스트) |
| `e` | 편집 |
| `m` | 담당자 변경 |
| `s` | 상태 변경 |
| `cmd+k` | Command Palette |

## 20.10 Jira 인터랙션 패리티 (FR-UX-07 ~ FR-UX-14)

> **분할 (2026-07-29)**. FR-UX-07은 처음에 27 PR 로드맵 전체를 한 FR로 묶었다. 그 결과 D1~D7이 완주 단위로 닫히지 않았다 — 단축키·인라인 편집·생성 모달·백로그가 각자 도메인 정리와 명세를 따로 요구하기 때문이다. 기능 축으로 8개 FR로 쪼갠다. 로드맵 정본은 `~/.claude/plans/ui-ux-sorted-kay.md` §PR 체인이고 아래 "승계 PR"의 F번호는 거기서 온다.

### 활성 프로젝트 컨텍스트 (FR-UX-07)

`projectKey`를 URL(`?projectKey=`)과 활성 컨텍스트로 승격해, 화면마다 흩어져 있던 `DEFAULT_PROJECT_KEY` 하드코딩을 없앤다. 해소는 **4단**이다 — URL 쿼리 → 사용자가 마지막으로 고른 프로젝트(localStorage) → 접근 가능한 프로젝트 목록의 첫 항목 → 없으면 빈 상태 게이트.

- ADR [decisions/2026-07-28-fr-ux-07-active-project-context.md](../decisions/2026-07-28-fr-ux-07-active-project-context.md) §D1~D5
- 명세 [specs/2026-07-28-fr-ux-07-active-project-key.md](../specs/2026-07-28-fr-ux-07-active-project-key.md)
- 데이터 모델 변경 없음 — 활성 프로젝트는 클라이언트 localStorage에만 산다 (ADR §D2 "논리 ≠ 물리"). 마이그레이션 0건.

> **검증 한계 1건 (D7)**. E2E는 13건 전량 통과하지만, 시작 페이지 파생 결함(명세 S8)의 "빈 화면 → 채워짐"은 문자 그대로 재현하지 못한다. MSW 목이 이슈를 프로젝트로 필터링하지 않기 때문이다. 대신 결함의 원인(`DEFAULT_PROJECT_KEY='ATLAS'` 하드코딩)이 사라졌다는 관측 가능 증거 — 재로그인 후 실제 `GET /api/v1/issues` 요청의 `projectKey` 쿼리가 활성 프로젝트로 나가는지 — 를 네트워크 인터셉트로 확인했다. 완전 검증은 `GET /api/v1/issues`의 실 project-scoping을 요구하고, 이는 E2E 스펙 약 120건에 파급되므로 별건이다. 한편 "검색 화면도 같은 활성 프로젝트를 따른다"는 테스트는 S8이 아니라 명세 §7 **엣지케이스 E8**이다 (`/search`는 이미 `?projectKey=`를 읽고 있었고, 폴백 상수만 해소 결과로 교체됐다).

### 후속 FR (FR-UX-08 ~ FR-UX-14)

| FR | 범위 | 승계 PR |
|---|---|---|
| FR-UX-08 | 프로젝트 스위처 + 트리 펼침 영속, 사이드바 "내 작업"(프로젝트 스코프) · "최근 항목" | F12, F17 |
| FR-UX-09 | 이슈 생성 모달(유형·본문 필드, 프로젝트 자유 텍스트 → 셀렉터) + 만들기 진입점 3곳(보드 컬럼·백로그 섹션·목록 헤더) + 생성 시 담당자·우선순위·라벨 | F2, F3, **B1** |
| FR-UX-10 | 컨텍스트 단축키 레지스트리 + 목록 항법(`j`/`k`/`o`/`t`) + 상세 액션(`a`/`i`/`m`/`e`/`l`/`w`) | F10, F11 |
| FR-UX-11 | 이슈 상세 제목/본문 인라인 편집(클릭 진입 · Enter 저장 · Esc 취소) + 목록 셀 인라인 편집(담당자·우선순위·상태) | F8, F9 |
| FR-UX-12 | Command Palette 실체 검색(이슈키 즉시 매칭 · 프로젝트 로컬 필터 · AQL 디바운스) + 상단바 전역 검색 입력창 | F4, F13 |
| FR-UX-13 | 백로그 담당자 표시 봉합 + 세로 스택 · 스프린트 시작/완료 다이얼로그 · 키보드 DnD + 필터바/에픽 패널 | F5, F15, F16 |
| FR-UX-14 | 보드/백로그 카드 밀도(유형 아이콘 · 라벨 칩 · 추정) + 이를 떠받치는 카드 응답 필드 확장 | F14, **B2** |

- **FR-UX-10은 §20.9가 이미 예약해 둔 범위다.** FR-UX-05는 컨텍스트 의존 단축키(`j/k`·`e`·`m`·`s`)를 "후속 FR로 미룸"이라고 명시 이연했고(Maxi 결정 2026-07-05), FR-UX-10이 그 승계자다. 기존 `SHORTCUTS` 레지스트리(전역 5종)는 프론트 2단언 + 백엔드 `KeymapAction` 화이트리스트 + `user_keymap.action` CHECK 제약이 동시에 동결하므로, 컨텍스트 단축키는 **별도 레지스트리**로 신설한다.
- **B1·B2는 chore가 아니라 D4(백엔드)다.** 2026-07-28 Maxi 결정 #3은 "B1/B2는 기존 FR 결손 봉합이라 chore"였으나, 분할 후에는 B1이 FR-UX-09의, B2가 FR-UX-14의 백엔드 단계다. 이 정정은 조용한 변경이 아니라 명시 승계다 — "백엔드 없음"으로 비던 칸이 실제 내용으로 채워지는 쪽이 정확하다.
- 로드맵 27 PR 중 나머지 10건(F6 도움말 배선 · F7 댓글 기본탭 · F18~F25 Tier 3 마감)은 각각 FR-UX-05 · FR-CO · FR-UX-06의 결손 봉합이라 신규 FR을 만들지 않고 chore로 남는다. 17 + 10 = 27로 총량은 불변이다.

## 20.11 다음 챕터

- 인증 → [19. 인증 시스템](19-authentication.md)
- 프론트엔드 구현 → [21. 프론트엔드 아키텍처](21-frontend.md)
