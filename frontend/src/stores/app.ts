import { defineStore } from 'pinia';

export const useAppStore = defineStore('app', {
  state: () => ({
    mode: 'MVP 原型 / Mock AI 模式',
    currentProjectName: '五年级科学示例课件',
  }),
});
