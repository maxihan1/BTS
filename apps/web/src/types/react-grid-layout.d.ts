// react-grid-layout ambient 타입 선언 — @types/react-grid-layout 미존재 시 임시 대체 (FR-DB-01 Task 8)
declare module 'react-grid-layout' {
  import type { ReactNode, ComponentType } from 'react'

  /** 그리드 아이템 레이아웃 */
  export interface Layout {
    /** 아이템 고유 키 */
    i: string
    /** 열 위치 (0-based) */
    x: number
    /** 행 위치 (0-based) */
    y: number
    /** 너비 (컬럼 수) */
    w: number
    /** 높이 (행 수) */
    h: number
    /** 최소 너비 */
    minW?: number
    /** 최대 너비 */
    maxW?: number
    /** 최소 높이 */
    minH?: number
    /** 최대 높이 */
    maxH?: number
    /** 위치 고정 여부 */
    static?: boolean
    /** 드래그 비활성화 여부 */
    isDraggable?: boolean
    /** 리사이즈 비활성화 여부 */
    isResizable?: boolean
  }

  /** GridLayout 컴포넌트 Props */
  export interface ReactGridLayoutProps {
    /** 레이아웃 배열 */
    layout?: Layout[]
    /** 컬럼 수 */
    cols?: number
    /** 픽셀 너비 (WidthProvider가 주입) */
    width?: number
    /** 행 높이(픽셀) */
    rowHeight?: number
    /** 마진 [x, y] */
    margin?: [number, number]
    /** 컨테이너 패딩 [x, y] */
    containerPadding?: [number, number]
    /** 드래그 가능 여부 */
    isDraggable?: boolean
    /** 리사이즈 가능 여부 */
    isResizable?: boolean
    /** 드래그 핸들 CSS 선택자 */
    draggableHandle?: string
    /** 드래그 취소 CSS 선택자 — 버튼·입력 등 클릭 전용 영역에서 드래그 차단 */
    draggableCancel?: string
    /** 레이아웃 변경 콜백 */
    onLayoutChange?: (layout: Layout[]) => void
    /** 자식 엘리먼트 */
    children?: ReactNode
    /** 클래스명 */
    className?: string
    /** 스타일 */
    style?: React.CSSProperties
  }

  /**
   * WidthProvider HOC — 컨테이너 너비를 자동으로 props에 주입한다.
   * jsdom 환경에서는 stub이 필요하다 (Task 8 테스트 mock 참고).
   */
  export function WidthProvider<P extends object>(
    component: ComponentType<P>,
  ): ComponentType<Omit<P, 'width'>>

  /** GridLayout 컴포넌트 */
  const GridLayout: ComponentType<ReactGridLayoutProps>
  export default GridLayout
}
