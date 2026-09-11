import { defineStore } from 'pinia';
import type { AssistantContextFile, AssistantMessage } from '@/types/assistant';

const STORAGE_KEY_PREFIX = 'a12-conversation-workspace-index';
const LEGACY_STORAGE_KEY = STORAGE_KEY_PREFIX;

export interface ConversationWorkspaceStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export interface ConversationSessionSummary {
  id: string;
  projectId?: number;
  missionId?: string;
  title: string;
  updatedAt: string;
  status: 'active' | 'idle';
  connectionId?: number | null;
  messages?: AssistantMessage[];
  files?: AssistantContextFile[];
}

function normalizeUserId(userId?: number | null) {
  return typeof userId === 'number' && Number.isInteger(userId) && userId > 0 ? String(userId) : null;
}

export function conversationWorkspaceStorageKey(userId?: number | null) {
  const normalizedUserId = normalizeUserId(userId);
  return normalizedUserId ? `${STORAGE_KEY_PREFIX}:${normalizedUserId}` : null;
}

export function readConversationSessions(storage: ConversationWorkspaceStorage | undefined, userId?: number | null): ConversationSessionSummary[] {
  const key = conversationWorkspaceStorageKey(userId);
  if (!storage || !key) return [];
  try {
    const raw = storage.getItem(key);
    const value = raw ? JSON.parse(raw) : [];
    if (!Array.isArray(value)) return [];
    return value.filter((item): item is ConversationSessionSummary => Boolean(
      item && typeof item.id === 'string' && typeof item.title === 'string' && typeof item.updatedAt === 'string',
    ));
  } catch {
    return [];
  }
}

export function persistConversationSessions(storage: ConversationWorkspaceStorage | undefined, userId: number | null, sessions: ConversationSessionSummary[]) {
  const key = conversationWorkspaceStorageKey(userId);
  if (!storage || !key) return;
  storage.setItem(key, JSON.stringify(sessions.slice(0, 12)));
}

export function clearConversationSessions(storage: ConversationWorkspaceStorage | undefined, userId?: number | null) {
  const key = conversationWorkspaceStorageKey(userId);
  if (storage && key) storage.removeItem(key);
}

function browserStorage() {
  return typeof window === 'undefined' ? undefined : window.localStorage;
}

export const useConversationWorkspaceStore = defineStore('conversation-workspace', {
  state: () => ({
    hydrated: false,
    userId: null as number | null,
    activeSessionId: '',
    sessions: [] as ConversationSessionSummary[],
    selectedConnectionId: null as number | null,
  }),
  getters: {
    activeSession: (state) => state.sessions.find((item) => item.id === state.activeSessionId),
  },
  actions: {
    hydrate(userId?: number | null) {
      const nextUserId = normalizeUserId(userId) ? Number(userId) : null;
      if (this.hydrated && this.userId === nextUserId) return;
      if (browserStorage()) browserStorage()?.removeItem(LEGACY_STORAGE_KEY);
      this.userId = nextUserId;
      this.sessions = readConversationSessions(browserStorage(), this.userId);
      const activeSession = this.sessions.find((session) => session.status === 'active') || this.sessions[0];
      this.activeSessionId = activeSession?.id || '';
      this.selectedConnectionId = activeSession?.connectionId || null;
      this.hydrated = true;
    },
    selectSession(id: string) {
      this.activeSessionId = id;
      this.selectedConnectionId = this.sessions.find((item) => item.id === id)?.connectionId || null;
      this.sessions = this.sessions.map((item) => ({
        ...item,
        status: item.id === id ? 'active' : 'idle',
      }));
      persistConversationSessions(browserStorage(), this.userId, this.sessions);
    },
    touchSession(session: ConversationSessionSummary) {
      this.sessions = [session, ...this.sessions.filter((item) => item.id !== session.id)].slice(0, 12);
      this.activeSessionId = session.id;
      this.selectedConnectionId = session.connectionId || null;
      this.sessions = this.sessions.map((item) => ({
        ...item,
        status: item.id === session.id ? 'active' : 'idle',
      }));
      persistConversationSessions(browserStorage(), this.userId, this.sessions);
    },
    setConnection(id: number | null) {
      this.selectedConnectionId = id;
      this.sessions = this.sessions.map((item) => item.id === this.activeSessionId ? { ...item, connectionId: id } : item);
      persistConversationSessions(browserStorage(), this.userId, this.sessions);
    },
    setSessionMessages(messages: AssistantMessage[]) {
      this.sessions = this.sessions.map((item) => item.id === this.activeSessionId
        ? { ...item, messages: [...messages], updatedAt: new Date().toISOString() }
        : item);
      persistConversationSessions(browserStorage(), this.userId, this.sessions);
    },
    setSessionFiles(files: AssistantContextFile[]) {
      this.sessions = this.sessions.map((item) => item.id === this.activeSessionId
        ? { ...item, files: [...files], updatedAt: new Date().toISOString() }
        : item);
      persistConversationSessions(browserStorage(), this.userId, this.sessions);
    },
    reset(userId?: number | null) {
      const targetUserId = userId === undefined ? this.userId : userId;
      clearConversationSessions(browserStorage(), targetUserId);
      this.hydrated = false;
      this.userId = null;
      this.activeSessionId = '';
      this.sessions = [];
      this.selectedConnectionId = null;
    },
  },
});
