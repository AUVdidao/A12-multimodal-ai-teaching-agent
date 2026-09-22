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
import { useConversationWorkspaceStore } from '@/stores/conversationWorkspace';
import { useLessonForgeStore } from '@/stores/lessonForge';
import { defineStore } from 'pinia';
import { isGoBackend } from '@/config/runtime';
import { setGoBearerToken } from '@/api/go';

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: isGoBackend ? '' : window.localStorage.getItem(AUTH_TOKEN_STORAGE_KEY) || '',
    user: null as UserProfile | null,
    initialized: false,
    sessionActive: false,
  }),
  getters: {
    isAuthenticated: (state) => Boolean(state.user && (isGoBackend ? state.sessionActive : state.token)),
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
      if (isGoBackend) {
        try {
          this.user = await getCurrentUser();
          this.sessionActive = true;
          this.initialized = true;
          return this.user;
        } catch (error) {
          this.clearSession();
          throw error;
        }
      }
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
      if (!this.initialized) {
        if (isGoBackend || this.token) await this.loadCurrentUser();
        else this.initialized = true;
      }
      return this.user;
    },
    async switchRole(role: UserRole) {
      const previousRole = this.user?.activeRole;
      this.user = await switchActiveRole(role);
      if (previousRole && previousRole !== this.user.activeRole) useLessonForgeStore().reset();
      return this.user;
    },
    async logout() {
      try {
        if (isGoBackend || this.token) {
          await logoutRequest();
        }
      } finally {
        this.clearSession();
      }
    },
    applySession(token: string, user: UserProfile) {
      if (this.user?.id && this.user.id !== user.id) {
        useConversationWorkspaceStore().reset(this.user.id);
        useLessonForgeStore().reset();
      }
      this.token = token;
      this.user = user;
      this.sessionActive = true;
      this.initialized = true;
      if (isGoBackend) setGoBearerToken(token);
      else window.localStorage.setItem(AUTH_TOKEN_STORAGE_KEY, token);
    },
    clearSession() {
      useConversationWorkspaceStore().reset(this.user?.id ?? null);
      useLessonForgeStore().reset();
      this.token = '';
      this.user = null;
      this.sessionActive = false;
      this.initialized = true;
      if (isGoBackend) setGoBearerToken('');
      window.localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
    },
  },
});
