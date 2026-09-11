import { listFrontendMissions } from '@/utils/missionFrontendAdapter';
import type { FrontendMission } from '@/types/mission';
import { defineStore } from 'pinia';

export const useMissionWorkspaceStore = defineStore('mission-workspace', {
  state: () => ({
    missions: [] as FrontendMission[],
    currentMissionId: null as string | null,
    loading: false,
    error: '',
  }),
  getters: {
    currentMission: (state) => state.missions.find((mission) => mission.id === state.currentMissionId) || null,
  },
  actions: {
    async load(userId: number | null | undefined) {
      this.loading = true;
      this.error = '';
      try {
        this.missions = listFrontendMissions(userId);
        if (!this.currentMissionId || !this.missions.some((mission) => mission.id === this.currentMissionId)) {
          this.currentMissionId = this.missions[0]?.id || null;
        }
      } catch {
        this.missions = [];
        this.currentMissionId = null;
        this.error = 'Mission fixture 暂时无法读取。';
      } finally {
        this.loading = false;
      }
    },
    selectMission(missionId: string) {
      if (this.missions.some((mission) => mission.id === missionId)) this.currentMissionId = missionId;
    },
    acceptCurrentMission() {
      const mission = this.missions.find((item) => item.id === this.currentMissionId);
      if (!mission || mission.status !== 'ASSIGNED' || mission.decision !== 'PENDING') return false;
      mission.status = 'ACCEPTED';
      mission.decision = 'ACCEPTED';
      mission.updatedAt = new Date().toISOString();
      return true;
    },
    rejectCurrentMission(reason: string) {
      const mission = this.missions.find((item) => item.id === this.currentMissionId);
      const normalizedReason = reason.trim();
      if (!mission || mission.status !== 'ASSIGNED' || mission.decision !== 'PENDING' || !normalizedReason) return false;
      mission.decision = 'REJECTED';
      mission.decisionReason = normalizedReason;
      mission.updatedAt = new Date().toISOString();
      return true;
    },
  },
});
