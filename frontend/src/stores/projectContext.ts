import { getProject, type TeachingProject } from '@/api/projects';
import { defineStore } from 'pinia';

export const useProjectContextStore = defineStore('project-context', {
  state: () => ({
    projectId: null as number | null,
    project: null as TeachingProject | null,
    loading: false,
    error: '',
    loadedAt: null as number | null,
  }),
  actions: {
    async load(projectId: number, force = false) {
      if (!force && this.projectId === projectId && this.project) return this.project;
      this.projectId = projectId;
      this.loading = true;
      this.error = '';
      try {
        this.project = await getProject(projectId);
        this.loadedAt = Date.now();
        return this.project;
      } catch {
        this.project = null;
        this.error = '暂时无法读取项目上下文。';
        return null;
      } finally {
        this.loading = false;
      }
    },
    clear() {
      this.projectId = null;
      this.project = null;
      this.error = '';
      this.loadedAt = null;
    },
  },
});
