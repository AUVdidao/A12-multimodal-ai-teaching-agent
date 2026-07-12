import { getProject, type TeachingProject } from '@/api/projects';
import { defineStore } from 'pinia';

export const useProjectContextStore = defineStore('project-context', {
  state: () => ({
    projectId: null as number | null,
    project: null as TeachingProject | null,
    loading: false,
    error: '',
    loadedAt: null as number | null,
    requestId: 0,
    pendingProjectId: null as number | null,
    pendingRequest: null as Promise<TeachingProject | null> | null,
  }),
  actions: {
    async load(projectId: number, force = false) {
      if (!force && this.projectId === projectId && this.project) return this.project;
      if (!force && this.pendingProjectId === projectId && this.pendingRequest) return this.pendingRequest;
      const requestId = ++this.requestId;
      this.projectId = projectId;
      this.project = null;
      this.loading = true;
      this.error = '';
      const request = (async () => {
        try {
          const project = await getProject(projectId);
          if (requestId !== this.requestId || this.projectId !== projectId) return null;
          this.project = project;
          this.loadedAt = Date.now();
          return this.project;
        } catch {
          if (requestId === this.requestId) {
            this.project = null;
            this.error = '暂时无法读取项目上下文。';
          }
          return null;
        } finally {
          if (requestId === this.requestId) {
            this.loading = false;
            this.pendingProjectId = null;
            this.pendingRequest = null;
          }
        }
      })();
      this.pendingProjectId = projectId;
      this.pendingRequest = request;
      return request;
    },
    clear() {
      this.requestId += 1;
      this.projectId = null;
      this.project = null;
      this.error = '';
      this.loadedAt = null;
      this.pendingProjectId = null;
      this.pendingRequest = null;
    },
  },
});
