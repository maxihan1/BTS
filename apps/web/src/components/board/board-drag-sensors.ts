// 보드 계열 드래그의 공용 센서 구성 — 포인터 + 키보드 (부채 177 · design 리뷰 BLOCKER-1)

import { useSensor, useSensors, PointerSensor, KeyboardSensor } from '@dnd-kit/core'
import type { SensorDescriptor, SensorOptions } from '@dnd-kit/core'

/**
 * 포인터 드래그를 시작시키는 최소 이동 거리(px).
 *
 * 카드가 링크를 겸하므로 0 이면 클릭이 드래그로 먹힌다. 5px 는 `KanbanBoard` 가 D-2 에서
 * 실측으로 정한 값이고, 여기서 그 값을 **한 곳에** 둔다.
 */
const POINTER_ACTIVATION_DISTANCE = 5

/**
 * 보드 계열 화면(칸반 보드 · 보드 설정)이 공유하는 `@dnd-kit` 센서 구성.
 *
 * ### 왜 훅으로 뽑았나 — 키보드 접근이 조용히 사라지는 자리다
 *
 * `KeyboardSensor` 가 빠지면 **포인터 없이는 드래그를 아예 할 수 없다.** 그런데 그 사실을
 * 잡는 테스트가 없다 — 드롭 판정 순수 함수는 센서를 모르고, 컴포넌트 테스트도 보통
 * `fireEvent` 로 포인터를 흉내 내므로 전부 초록이다
 * (memory `mock-swallowed-prop-is-invisible-to-unit-tests` 의 접근성 판본).
 *
 * 그래서 「센서를 이렇게 거세요」를 문서로 두지 않고 **함수 하나로 만들어** 새 드래그 표면이
 * 그것을 부르게 한다. 구성이 두 곳에 복사돼 있으면 한쪽만 고쳐지고, 고쳐지지 않은 쪽은
 * 보조기술 사용자에게만 고장 난 채로 남는다.
 *
 * @returns `DndContext` 의 `sensors` prop 에 그대로 넘길 센서 목록.
 */
export function useBoardDragSensors(): SensorDescriptor<SensorOptions>[] {
  return useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: POINTER_ACTIVATION_DISTANCE } }),
    useSensor(KeyboardSensor),
  )
}
