import { computed, onBeforeUnmount, ref, type Ref } from 'vue';

type WorkspaceResizeTarget = 'rail' | 'board';

interface StoredWorkspaceLayout {
  rail?: unknown;
  board?: unknown;
}

const DEFAULT_RAIL_WIDTH = 172;
const DEFAULT_BOARD_WIDTH = 292;

function clampWidth(value: unknown, minimum: number, maximum: number, fallback: number) {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? Math.min(maximum, Math.max(minimum, numeric)) : fallback;
}

/**
 * Keeps the two Mission Workspace rails draggable without coupling the layout
 * state to a particular page or to a global, cross-user preference.
 */
export function useResizableWorkspace(userId: Ref<number | null | undefined>) {
  const railWidth = ref(DEFAULT_RAIL_WIDTH);
  const boardWidth = ref(DEFAULT_BOARD_WIDTH);
  const resizeTarget = ref<WorkspaceResizeTarget | null>(null);
  const storageKey = computed(() => userId.value == null ? null : `lessonforge:workspace-layout:${userId.value}`);
  let resizeStartX = 0;
  let resizeStartWidth = 0;

  function restoreWorkspaceLayout() {
    const key = storageKey.value;
    if (!key || typeof window === 'undefined') return;
    try {
      const raw = window.localStorage.getItem(key);
      const parsed = raw ? JSON.parse(raw) as StoredWorkspaceLayout : null;
      railWidth.value = clampWidth(parsed?.rail, 140, 260, DEFAULT_RAIL_WIDTH);
      boardWidth.value = clampWidth(parsed?.board, 260, 420, DEFAULT_BOARD_WIDTH);
    } catch {
      railWidth.value = DEFAULT_RAIL_WIDTH;
      boardWidth.value = DEFAULT_BOARD_WIDTH;
    }
  }

  function persistWorkspaceLayout() {
    const key = storageKey.value;
    if (!key || typeof window === 'undefined') return;
    try {
      window.localStorage.setItem(key, JSON.stringify({ rail: railWidth.value, board: boardWidth.value }));
    } catch {
      // Browser storage is optional; the current session remains usable.
    }
  }

  function handleResize(event: PointerEvent) {
    if (resizeTarget.value === 'rail') {
      railWidth.value = clampWidth(resizeStartWidth + event.clientX - resizeStartX, 140, 260, DEFAULT_RAIL_WIDTH);
    }
    if (resizeTarget.value === 'board') {
      boardWidth.value = clampWidth(resizeStartWidth - (event.clientX - resizeStartX), 260, 420, DEFAULT_BOARD_WIDTH);
    }
    persistWorkspaceLayout();
  }

  function stopResize() {
    resizeTarget.value = null;
    if (typeof document !== 'undefined') document.body.classList.remove('lf-is-resizing');
    if (typeof window !== 'undefined') {
      window.removeEventListener('pointermove', handleResize);
      window.removeEventListener('pointerup', stopResize);
      window.removeEventListener('pointercancel', stopResize);
    }
  }

  function startResize(target: WorkspaceResizeTarget, event: PointerEvent) {
    if (event.button !== 0) return;
    resizeTarget.value = target;
    resizeStartX = event.clientX;
    resizeStartWidth = target === 'rail' ? railWidth.value : boardWidth.value;
    if (typeof document !== 'undefined') document.body.classList.add('lf-is-resizing');
    if (typeof window !== 'undefined') {
      window.addEventListener('pointermove', handleResize);
      window.addEventListener('pointerup', stopResize);
      window.addEventListener('pointercancel', stopResize);
    }
  }

  onBeforeUnmount(stopResize);

  return {
    railWidth,
    boardWidth,
    resizeTarget,
    restoreWorkspaceLayout,
    persistWorkspaceLayout,
    startResize,
    stopResize,
  };
}
