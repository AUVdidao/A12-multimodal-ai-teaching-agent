import axios from 'axios';

const configuredBaseURL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';
const requestBaseURL =
  import.meta.env.DEV && configuredBaseURL === 'http://localhost:8080'
    ? ''
    : configuredBaseURL;

export const http = axios.create({
  baseURL: requestBaseURL,
  timeout: 8000,
});

export { configuredBaseURL as apiBaseURL };
