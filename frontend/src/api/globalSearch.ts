import type { UserRole } from './auth';
import { listApprovalRequests } from './approvals';
import { listPublications, listLearningTasks } from './publications';
import { listQuestions } from './questions';
import { listProjects } from './projects';
import { listTeachingTasks } from './teachingTasks';

export type GlobalSearchKind = 'PROJECT' | 'TASK' | 'QUESTION' | 'APPROVAL' | 'PUBLICATION' | 'LEARNING_TASK';

export interface SearchDestination {
  name: string;
  params?: Record<string, string | number>;
}

export interface GlobalSearchResult {
  id: string;
  kind: GlobalSearchKind;
  title: string;
  description: string;
  meta: string;
  destination: SearchDestination;
}

export interface GlobalSearchResponse {
  results: GlobalSearchResult[];
  failedSources: string[];
}

interface SearchSource {
  label: string;
  load: () => Promise<GlobalSearchResult[]>;
}

export async function searchGlobalData(role: UserRole, query: string): Promise<GlobalSearchResponse> {
  const normalizedQuery = normalize(query);
  if (!normalizedQuery) return { results: [], failedSources: [] };

  const sources = searchSources(role);
  const settled = await Promise.allSettled(sources.map((source) => source.load()));
  const results: GlobalSearchResult[] = [];
  const failedSources: string[] = [];

  settled.forEach((outcome, index) => {
    if (outcome.status === 'fulfilled') {
      results.push(...outcome.value.filter((item) => matches(item, normalizedQuery)));
      return;
    }
    failedSources.push(sources[index].label);
  });

  return {
    results: results.sort((left, right) => left.title.localeCompare(right.title, 'zh-CN')),
    failedSources,
  };
}

function searchSources(role: UserRole): SearchSource[] {
  if (role === 'STUDENT') {
    return [
      {
        label: '学习任务',
        load: async () => (await listLearningTasks()).map((task) => ({
          id: `learning-task:${task.publicationId}`,
          kind: 'LEARNING_TASK',
          title: task.title,
          description: task.summary || `${task.courseName} · ${task.className}`,
          meta: `发布 #${task.publicationId} · ${task.courseName}`,
          destination: { name: 'student-learning' },
        })),
      },
      {
        label: '我的问答',
        load: async () => (await listQuestions()).map((question) => ({
          id: `question:${question.id}`,
          kind: 'QUESTION',
          title: question.title,
          description: question.content,
          meta: `问题 #${question.id} · ${questionStatusLabel(question.status)}`,
          destination: { name: 'student-questions' },
        })),
      },
    ];
  }

  if (role === 'LEADER') {
    return [
      taskSource('leader-teaching-tasks'),
      {
        label: '审批',
        load: async () => (await listApprovalRequests()).map((approval) => ({
          id: `approval:${approval.id}`,
          kind: 'APPROVAL',
          title: approval.projectName || `项目 #${approval.projectId} 的审批`,
          description: approval.reviewNote || `版本 #${approval.artifactVersionId}`,
          meta: `审批 #${approval.id} · ${approvalStatusLabel(approval.status)}`,
          destination: { name: 'leader-approvals' },
        })),
      },
      {
        label: '发布',
        load: async () => (await listPublications()).map((publication) => ({
          id: `publication:${publication.id}`,
          kind: 'PUBLICATION',
          title: publication.title,
          description: publication.summary || `${publication.courseName} · ${publication.className}`,
          meta: `发布 #${publication.id} · ${publicationStatusLabel(publication.status)}`,
          destination: { name: 'leader-publications' },
        })),
      },
      questionSource('leader-questions'),
    ];
  }

  return [
    {
      label: '项目',
      load: async () => (await listProjects()).map((project) => ({
        id: `project:${project.id}`,
        kind: 'PROJECT',
        title: project.projectName,
        description: `${project.courseName} · ${project.chapterTitle}`,
        meta: `项目 #${project.id} · ${project.status}`,
        destination: { name: 'project-overview', params: { projectId: project.id } },
      })),
    },
    taskSource('teacher-teaching-tasks'),
    questionSource('teacher-questions'),
  ];
}

function taskSource(routeName: string): SearchSource {
  return {
    label: '教学任务',
    load: async () => (await listTeachingTasks()).map((task) => ({
      id: `task:${task.id}`,
      kind: 'TASK',
      title: task.taskName,
      description: task.requirements,
      meta: `任务 #${task.id} · ${task.courseName} · ${task.taskStatus}`,
      destination: { name: routeName },
    })),
  };
}

function questionSource(routeName: string): SearchSource {
  return {
    label: '问答',
    load: async () => (await listQuestions()).map((question) => ({
      id: `question:${question.id}`,
      kind: 'QUESTION',
      title: question.title,
      description: question.content,
      meta: `问题 #${question.id} · ${question.studentName} · ${questionStatusLabel(question.status)}`,
      destination: { name: routeName },
    })),
  };
}

function matches(item: GlobalSearchResult, query: string) {
  return normalize([item.title, item.description, item.meta, item.kind].join(' ')).includes(query);
}

function normalize(value: string) {
  return value.trim().toLocaleLowerCase();
}

function questionStatusLabel(status: string) {
  return ({ OPEN: '待回答', ANSWERED: '已回答', CLOSED: '已关闭' })[status] || status;
}

function approvalStatusLabel(status: string) {
  return ({ SUBMITTED: '待审批', APPROVED: '已通过', REVISION_REQUIRED: '需修改', CANCELLED: '已取消' })[status] || status;
}

function publicationStatusLabel(status: string) {
  return ({ PUBLISHED: '已发布', WITHDRAWN: '已撤回' })[status] || status;
}
