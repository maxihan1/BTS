// 워크플로우 상태 편성의 순서 계산 — 드래그 결과를 새 id 순서로 옮기는 순수 함수
/**
 * 드래그 결과를 새 id 순서로 계산한다.
 *
 * ### 왜 컴포넌트 밖인가
 * jsdom 은 dnd-kit 이 쓰는 레이아웃 측정 API 를 구현하지 않아 드래그 자체를 단위 테스트로
 * 재현할 수 없다. 그래서 계산만 떼어 낸다 — `lib/timeline-layout.ts` 가 세운 관례이자
 * Gantt ADR 이 같은 처방을 적어 둔 자리다. 이 분리가 없으면 「잘못된 순서를 보낸다」는
 * 결함을 잡는 판정이 어디에도 못 선다(실측 — 뮤테이션 3건이 전부 green 이었다).
 *
 * 컴포넌트 파일에서 내보내면 `react-refresh/only-export-components` 가 막는다 — 그 규칙이
 * 가리키는 곳이 바로 여기다.
 *
 * @param ids 지금 순서의 id 목록
 * @param activeId 집어 든 것
 * @param overId 놓은 자리의 것
 * @returns 옮긴 뒤의 **전체** id 목록. 못 옮기면 원본 그대로(참조 동일 — 호출부가 무동작으로 가른다)
 */
export function reorderIds(ids: string[], activeId: string, overId: string): string[] {
  const from = ids.indexOf(activeId)
  const to = ids.indexOf(overId)
  if (from < 0 || to < 0 || from === to) {
    return ids
  }
  const next = [...ids]
  next.splice(to, 0, next.splice(from, 1)[0]!)
  return next
}
