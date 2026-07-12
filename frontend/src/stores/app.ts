import { defineStore } from 'pinia';
import { checkBackendHealth } from '@/api/health';

export type SystemHealthStatus = 'checking' | 'healthy' | 'unavailable';

export const useAppStore = defineStore('app', {
  state: () => ({
    systemStatus: 'checking' as SystemHealthStatus,
  }),
  getters: {
    systemStatusLabel: (state) => ({ checking: '正在检查服务', healthy: '服务正常', unavailable: '服务暂不可用' })[state.systemStatus],
  },
  actions: {
    async checkHealth() {
      this.systemStatus = 'checking';
      try {
        this.systemStatus = (await checkBackendHealth()).data.status === 'UP' ? 'healthy' : 'unavailable';
      } catch {
        this.systemStatus = 'unavailable';
      }
    },
  },
});
