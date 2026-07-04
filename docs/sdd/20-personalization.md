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

> **구현 현황 (2026-07-05, ADR [decisions/2026-07-05-fr-ux-05-keymap.md](../decisions/2026-07-05-fr-ux-05-keymap.md))**. MVP는 **전역 네비게이션 5종 + 도움말**(`?`·`c`·`/`·`g i`·`g d`)을 커스텀 훅(의존성 0)으로 구현 완료. 컨텍스트 의존 단축키(`j/k`·`e`·`m`·`s`)는 이슈 상세/보드와의 깊은 결선이 필요해 후속 FR로 미룸. `cmd+k`는 FR-UX-04에서 구현. 사용자 커스텀 키맵은 FR-PF-03.

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

## 20.10 다음 챕터

- 인증 → [19. 인증 시스템](19-authentication.md)
- 프론트엔드 구현 → [21. 프론트엔드 아키텍처](21-frontend.md)
