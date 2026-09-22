import type { ApiResponse } from './health';
import { http } from './http';
import { isGoBackend } from '@/config/runtime';
import { goLogin, goLogout, goMe, goRegister } from './go';

export type UserRole = 'TEACHER' | 'LEADER' | 'STUDENT' | 'RESEARCHER';

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
}

export interface RegisterPayload {
  username: string;
  displayName: string;
  password: string;
  role: Extract<UserRole, 'TEACHER' | 'LEADER'>;
}

export async function login(payload: LoginPayload) {
  if (isGoBackend) {
    const response = await goLogin({ email: payload.username, password: payload.password });
    return { token: response.token || '', expiresAt: response.expiresAt || '', user: mapGoUser(response.user) };
  }
  const response = await http.post<ApiResponse<AuthSession>>('/api/v1/auth/login', payload);
  return response.data.data;
}

export async function register(payload: RegisterPayload) {
  if (isGoBackend) {
    await goRegister({ name: payload.displayName, email: payload.username, password: payload.password, role: payload.role === 'LEADER' ? 'RESEARCHER' : 'TEACHER' });
    const response = await goLogin({ email: payload.username, password: payload.password });
    return { token: response.token || '', expiresAt: response.expiresAt || '', user: mapGoUser(response.user) };
  }
  const response = await http.post<ApiResponse<AuthSession>>('/api/v1/auth/register', payload);
  return response.data.data;
}

export async function getCurrentUser() {
  if (isGoBackend) return mapGoUser(await goMe());
  const response = await http.get<ApiResponse<UserProfile>>('/api/v1/auth/me');
  return response.data.data;
}

export async function switchActiveRole(role: UserRole) {
  if (isGoBackend) throw new Error(`ROLE_SWITCH_UNSUPPORTED:${role}`);
  const response = await http.post<ApiResponse<UserProfile>>('/api/v1/auth/switch-role', { role });
  return response.data.data;
}

export async function logout() {
  if (isGoBackend) {
    await goLogout();
    return;
  }
  await http.post('/api/v1/auth/logout');
}

function mapGoUser(user: { id: number; name: string; email: string; role: 'TEACHER' | 'RESEARCHER' }): UserProfile {
  const activeRole: UserRole = user.role === 'RESEARCHER' ? 'RESEARCHER' : 'TEACHER';
  return { id: user.id, username: user.email, displayName: user.name, roles: [activeRole], activeRole };
}
