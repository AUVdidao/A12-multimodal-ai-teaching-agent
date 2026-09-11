export type LessonForgeMissionStatus = 'ASSIGNED' | 'IN_PROGRESS' | 'FEEDBACK' | 'SUBMITTED';
export type LessonForgePhase = 'QUESTION' | 'DRAFT' | 'LOCKED' | 'GENERATING' | 'SUCCEEDED' | 'CONFLICT' | 'UNAVAILABLE';

export interface LessonForgeFile {
  id: string;
  name: string;
  kind: 'MATERIAL' | 'TEMPLATE' | 'TEACHER_ASSET';
  sizeLabel?: string;
}

export interface LessonForgeMessage {
  id: string;
  role: 'teacher' | 'assistant';
  body: string;
  time?: string;
  attachments?: string[];
}

export interface LessonForgePlan {
  version: string;
  pages: number;
  status: 'DRAFT' | 'LOCKED';
  summary: string;
  sections: string[];
}

export interface LessonForgeOutput {
  name: string;
  pages: number;
  version: string;
  sizeLabel: string;
}

export interface LessonForgeGeneration {
  status: 'GENERATING' | 'SUCCEEDED';
  detail?: string;
}

export interface LessonForgeReviewer {
  id: string;
  name: string;
  role: string;
}

export interface LessonForgeSubmission {
  id: string;
  fileName: string;
  reviewerId: string;
  reviewerName: string;
  status: 'SUBMISSION_PENDING' | 'REVIEWED';
  submittedAt: string;
  source: 'SERVER';
  size?: number;
  rating?: number | null;
  reviewNote?: string | null;
  reviewedAt?: string | null;
  downloadUrl?: string;
}

export interface LessonForgeReview {
  reviewerName: string;
  rating: number;
  comment: string;
  submittedFileName: string;
  reviewedAt: string;
}

export interface LessonForgeSurfaceGate {
  submission: boolean;
  feedback: boolean;
}

export interface LessonForgeActivity {
  label: string;
  detail: string;
  time: string;
  tone?: 'default' | 'success' | 'warning';
}

export interface LessonForgeMission {
  id: string;
  teacherId: number;
  title: string;
  description: string;
  status: LessonForgeMissionStatus;
  selectedConnectionId?: number | null;
  currentPhase: LessonForgePhase;
  phaseMessage?: string;
  surfaceGate: LessonForgeSurfaceGate;
  deadline?: string;
  recentActivity: string;
  sources: LessonForgeFile[];
  messages: LessonForgeMessage[];
  question?: { current: number; total: number; prompt: string; options: string[] };
  plan?: LessonForgePlan;
  output?: LessonForgeOutput;
  generation?: LessonForgeGeneration;
  activities: LessonForgeActivity[];
  feedback?: string;
  submission?: LessonForgeSubmission;
  submissions: LessonForgeSubmission[];
  review?: LessonForgeReview;
}

export const lessonForgeStatusLabels: Record<LessonForgeMissionStatus, string> = {
  ASSIGNED: '待开始',
  IN_PROGRESS: '进行中',
  FEEDBACK: '待修改',
  SUBMITTED: '已提交',
};
