import type { ApiResponse } from './health';
import { http } from './http';

export type UserRole = 'TEACHER' | 'LEADER' | 'STUDENT';

export interface UserProfile {
  id: number;
  username: string;
  displayName: string;
  roles: UserRole[];
  activeRole: UserRole;
}

export interface AuthSession {
  token: string;
  expiresAt: string;
  user: UserProfile;
}

export interface LoginPayload {
  username: string;
  password: string;
  activeRole?: UserRole;
}

export interface RegisterPayload {
  username: string;
  displayName: string;
  password: string;
}

export async function login(payload: LoginPayload) {
  const response = await http.post<ApiResponse<AuthSession>>('/api/v1/auth/login', payload);
  return response.data.data;
}

export async function register(payload: RegisterPayload) {
  const response = await http.post<ApiResponse<AuthSession>>('/api/v1/auth/register', payload);
  return response.data.data;
}

export async function getCurrentUser() {
  const response = await http.get<ApiResponse<UserProfile>>('/api/v1/auth/me');
  return response.data.data;
}

export async function switchActiveRole(role: UserRole) {
  const response = await http.post<ApiResponse<UserProfile>>('/api/v1/auth/switch-role', { role });
  return response.data.data;
}

export async function logout() {
  await http.post('/api/v1/auth/logout');
}
