import { http } from './http';

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: string;
}

export interface HealthStatus {
  status: string;
  service: string;
  version: string;
}

export async function checkBackendHealth() {
  const response = await http.get<ApiResponse<HealthStatus>>('/api/health');
  return response.data;
}
