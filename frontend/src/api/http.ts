import axios from 'axios';

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
  timeout: 8000,
});

export { configuredBaseURL as apiBaseURL };
