export type MissionStatus = 'ASSIGNED' | 'ACCEPTED' | 'IN_PROGRESS' | 'SUBMITTED' | 'RETURNED' | 'COMPLETED';
export type MissionDecision = 'PENDING' | 'ACCEPTED' | 'REJECTED';

export interface FrontendMission {
  id: string;
  title: string;
  description: string;
  deadline: string;
  status: MissionStatus;
  teacherId: number;
  updatedAt: string;
  decision: MissionDecision;
  decisionReason?: string;
  source: 'FRONTEND_ONLY';
  limitation: 'BACKEND_NOT_CONNECTED';
}

export const missionStatusLabels: Record<MissionStatus, string> = {
  ASSIGNED: '待接受',
  ACCEPTED: '已接受',
  IN_PROGRESS: '进行中',
  SUBMITTED: '已提交',
  RETURNED: '已退回',
  COMPLETED: '已完成',
};
