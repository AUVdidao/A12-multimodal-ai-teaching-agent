import {
  getCurrentUser,
  login as loginRequest,
  logout as logoutRequest,
  register as registerRequest,
  switchActiveRole,
  type LoginPayload,
  type RegisterPayload,
  type UserProfile,
  type UserRole,
} from '@/api/auth';
import { AUTH_TOKEN_STORAGE_KEY } from '@/api/http';
import { defineStore } from 'pinia';

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: window.localStorage.getItem(AUTH_TOKEN_STORAGE_KEY) || '',
    user: null as UserProfile | null,
    initialized: false,
  }),
  getters: {
    isAuthenticated: (state) => Boolean(state.token && state.user),
    activeRole: (state) => state.user?.activeRole,
  },
  actions: {
    async login(payload: LoginPayload) {
      const session = await loginRequest(payload);
      this.applySession(session.token, session.user);
      return session.user;
    },
    async register(payload: RegisterPayload) {
      const session = await registerRequest(payload);
      this.applySession(session.token, session.user);
      return session.user;
    },
    async loadCurrentUser() {
      if (!this.token) {
        this.clearSession();
        return null;
      }
      try {
        this.user = await getCurrentUser();
        this.initialized = true;
        return this.user;
      } catch (error) {
        this.clearSession();
        throw error;
      }
    },
    async ensureInitialized() {
      if (!this.initialized && this.token) {
        await this.loadCurrentUser();
      }
      return this.user;
    },
    async switchRole(role: UserRole) {
      this.user = await switchActiveRole(role);
      return this.user;
    },
    async logout() {
      try {
        if (this.token) {
          await logoutRequest();
        }
      } finally {
        this.clearSession();
      }
    },
    applySession(token: string, user: UserProfile) {
      this.token = token;
      this.user = user;
      this.initialized = true;
      window.localStorage.setItem(AUTH_TOKEN_STORAGE_KEY, token);
    },
    clearSession() {
      this.token = '';
      this.user = null;
      this.initialized = true;
      window.localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
    },
  },
});
