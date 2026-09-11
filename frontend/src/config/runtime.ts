export interface LessonForgeDesktopBridge {
  isDesktop: boolean;
}

export function isDesktopRuntime() {
  return typeof window !== 'undefined' && Boolean(window.lessonForgeDesktop?.isDesktop);
}

export const isGoBackend = isDesktopRuntime() || import.meta.env.VITE_BACKEND_MODE === 'go';

const configuredGoBaseUrl = import.meta.env.VITE_GO_API_BASE_URL || 'http://127.0.0.1:8090';
export const goApiBaseUrl = configuredGoBaseUrl.replace(/\/+$/, '');
