import axios from 'axios';

export const AUTH_TOKEN_STORAGE_KEY = 'a12-auth-token';

const configuredBaseURL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';
const normalizedBaseURL = configuredBaseURL.replace(/\/+$/, '');
const requestBaseURL =
  import.meta.env.DEV && normalizedBaseURL === 'http://localhost:8080'
    ? ''
    : normalizedBaseURL.endsWith('/api')
      ? normalizedBaseURL.slice(0, -4)
      : normalizedBaseURL;

export const http = axios.create({
  baseURL: requestBaseURL,
  timeout: 240000,
});

http.interceptors.request.use((config) => {
  const token = window.localStorage.getItem(AUTH_TOKEN_STORAGE_KEY);
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status;
    const requestUrl = String(error?.config?.url || '');
    const isPublicAuthRequest = requestUrl.endsWith('/api/v1/auth/login') || requestUrl.endsWith('/api/v1/auth/register');
    if (status === 401 && !isPublicAuthRequest) {
      window.localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
      if (window.location.pathname !== '/login') {
        const redirect = `${window.location.pathname}${window.location.search}`;
        window.location.assign(`/login?redirect=${encodeURIComponent(redirect)}`);
      }
    }
    return Promise.reject(error);
  },
);

export { configuredBaseURL as apiBaseURL };
