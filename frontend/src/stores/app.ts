import { defineStore } from 'pinia';

export const useAppStore = defineStore('app', {
  state: () => ({
    systemStatus: '服务正常',
  }),
});
