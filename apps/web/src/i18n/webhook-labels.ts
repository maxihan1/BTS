// 아웃바운드 webhook 이벤트/발송상태 한국어 라벨 — 미지 값은 원문 반환(전방호환) (FR-API-03 PR4)

// ─────────────────────────────────────────────────────────────────────────────
// eventLabels — webhook 구독이 선택 가능한 발행 이벤트 wireValue 2종
// 백엔드 com.bts.search.webhook.domain.WebhookEventCatalog.PUBLISHABLE 미러
// 이벤트 추가 시 이 객체 + api/webhooks.ts WEBHOOK_PUBLISHABLE_EVENTS 동반 갱신할 것
// ─────────────────────────────────────────────────────────────────────────────

/** webhook 이벤트 wireValue(e.g. `"issue.created"`) → 한국어 라벨 */
const eventLabels: Record<string, string> = {
  'issue.created': '이슈 생성',
  'issue.transitioned': '이슈 상태 전이',
}

// ─────────────────────────────────────────────────────────────────────────────
// statusLabels — 발송 이력 status 2종(SUCCEEDED|FAILED)
// ─────────────────────────────────────────────────────────────────────────────

/** webhook 발송 이력 status(e.g. `"SUCCEEDED"`) → 한국어 라벨 */
const statusLabels: Record<string, string> = {
  SUCCEEDED: '성공',
  FAILED: '실패',
}

/**
 * webhook 이벤트 wireValue를 한국어 라벨로 변환한다.
 *
 * 백엔드 enum/카탈로그가 확장돼도 UI가 깨지지 않도록, 매핑이 없는 값은 원문을 그대로 반환한다.
 *
 * @param wireValue 이벤트 wireValue (예: "issue.created")
 * @returns 매핑된 한국어 라벨, 없으면 wireValue 원문
 */
export function labelForEvent(wireValue: string): string {
  return eventLabels[wireValue] ?? wireValue
}

/**
 * webhook 발송 이력 status를 한국어 라벨로 변환한다.
 *
 * 매핑이 없는 값(백엔드가 새 상태를 추가한 경우 등)은 원문을 그대로 반환한다.
 *
 * @param status 발송 상태 (예: "SUCCEEDED", "FAILED")
 * @returns 매핑된 한국어 라벨, 없으면 status 원문
 */
export function labelForStatus(status: string): string {
  return statusLabels[status] ?? status
}
