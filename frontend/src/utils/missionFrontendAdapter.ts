import type { FrontendMission } from '@/types/mission';

/** The real user id created for the seeded `teacher` demo account. */
export const DEMO_TEACHER_USER_ID = 2;

const fixtureMissions: FrontendMission[] = [
  {
    id: 'mission-101-assigned',
    title: '完成《光的干涉》课堂设计',
    description: '围绕光的干涉现象准备一份可用于课堂讨论的教学任务。',
    deadline: '2026-09-06',
    status: 'ASSIGNED',
    teacherId: DEMO_TEACHER_USER_ID,
    updatedAt: '2026-08-30T08:00:00Z',
    decision: 'PENDING',
    source: 'FRONTEND_ONLY',
    limitation: 'BACKEND_NOT_CONNECTED',
  },
  {
    id: 'mission-101-accepted',
    title: '完善《牛顿运动定律》任务单',
    description: '整理任务单结构，并准备后续会话协作所需的上下文。',
    deadline: '2026-09-10',
    status: 'ACCEPTED',
    teacherId: DEMO_TEACHER_USER_ID,
    updatedAt: '2026-08-29T08:00:00Z',
    decision: 'ACCEPTED',
    source: 'FRONTEND_ONLY',
    limitation: 'BACKEND_NOT_CONNECTED',
  },
  {
    id: 'mission-101-progress',
    title: '制作《电磁感应》探究方案',
    description: '继续完善课堂探究流程和教师提示。',
    deadline: '2026-09-12',
    status: 'IN_PROGRESS',
    teacherId: DEMO_TEACHER_USER_ID,
    updatedAt: '2026-08-28T08:00:00Z',
    decision: 'ACCEPTED',
    source: 'FRONTEND_ONLY',
    limitation: 'BACKEND_NOT_CONNECTED',
  },
  {
    id: 'mission-101-submitted',
    title: '提交《机械能守恒》初稿',
    description: '查看已提交任务的当前状态。',
    deadline: '2026-08-31',
    status: 'SUBMITTED',
    teacherId: DEMO_TEACHER_USER_ID,
    updatedAt: '2026-08-27T08:00:00Z',
    decision: 'ACCEPTED',
    source: 'FRONTEND_ONLY',
    limitation: 'BACKEND_NOT_CONNECTED',
  },
  {
    id: 'mission-101-returned',
    title: '修改《化学反应速率》材料',
    description: '根据退回意见重新整理教学材料。',
    deadline: '2026-09-02',
    status: 'RETURNED',
    teacherId: DEMO_TEACHER_USER_ID,
    updatedAt: '2026-08-26T08:00:00Z',
    decision: 'ACCEPTED',
    source: 'FRONTEND_ONLY',
    limitation: 'BACKEND_NOT_CONNECTED',
  },
  {
    id: 'mission-101-completed',
    title: '归档《细胞分裂》教学成果',
    description: '查看已经完成的教学任务记录。',
    deadline: '2026-08-20',
    status: 'COMPLETED',
    teacherId: DEMO_TEACHER_USER_ID,
    updatedAt: '2026-08-20T08:00:00Z',
    decision: 'ACCEPTED',
    source: 'FRONTEND_ONLY',
    limitation: 'BACKEND_NOT_CONNECTED',
  },
];

function cloneMission(mission: FrontendMission): FrontendMission {
  return { ...mission };
}

export function listFrontendMissions(userId: number | null | undefined): FrontendMission[] {
  return fixtureMissions.filter((mission) => mission.teacherId === userId).map(cloneMission);
}

export function getFrontendMission(missionId: string, userId: number | null | undefined): FrontendMission | null {
  return listFrontendMissions(userId).find((mission) => mission.id === missionId) || null;
}
