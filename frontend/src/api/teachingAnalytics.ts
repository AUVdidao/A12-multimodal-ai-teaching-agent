import { listArtifactVersions, type ArtifactVersion } from './artifactVersions';
import { listPublications, type Publication } from './publications';
import { listProjects, type TeachingProject } from './projects';
import { listQuestions, type Question } from './questions';
import { listTeachingTasks, type TeachingTask } from './teachingTasks';

export interface TeacherAnalyticsData {
  projects: TeachingProject[];
  tasks: TeachingTask[];
  publications: Publication[];
  questions: Question[];
  versionsByProjectId: Map<number, ArtifactVersion[]>;
  failedSources: string[];
}

export interface StudentInsightsData {
  publications: Publication[];
  questions: Question[];
  failedSources: string[];
}

export async function loadTeacherAnalyticsData(): Promise<TeacherAnalyticsData> {
  const [projectResult, taskResult, publicationResult, questionResult] = await Promise.allSettled([
    listProjects(),
    listTeachingTasks(),
    listPublications(),
    listQuestions(),
  ]);

  const projects = resultValue(projectResult);
  const tasks = resultValue(taskResult);
  const publications = resultValue(publicationResult);
  const questions = resultValue(questionResult);
  const failedSources = failedLabels([
    [projectResult, '教学项目'],
    [taskResult, '教学任务'],
    [publicationResult, '发布记录'],
    [questionResult, '项目问答'],
  ]);
  const versionsByProjectId = new Map<number, ArtifactVersion[]>();

  if (projectResult.status === 'fulfilled') {
    const versionResults = await Promise.allSettled(projects.map((project) => listArtifactVersions(project.id)));
    versionResults.forEach((result, index) => {
      if (result.status === 'fulfilled') {
        versionsByProjectId.set(projects[index].id, result.value);
      } else if (!failedSources.includes('成果版本')) {
        failedSources.push('成果版本');
      }
    });
  }

  return { projects, tasks, publications, questions, versionsByProjectId, failedSources };
}

export async function loadStudentInsightsData(): Promise<StudentInsightsData> {
  const [publicationResult, questionResult] = await Promise.allSettled([
    listPublications(),
    listQuestions(),
  ]);

  return {
    publications: resultValue(publicationResult),
    questions: resultValue(questionResult),
    failedSources: failedLabels([
      [publicationResult, '发布记录'],
      [questionResult, '学生问答'],
    ]),
  };
}

function resultValue<T>(result: PromiseSettledResult<T[]>) {
  return result.status === 'fulfilled' ? result.value : [];
}

function failedLabels(entries: Array<[PromiseSettledResult<unknown[]>, string]>) {
  return entries
    .filter(([result]) => result.status === 'rejected')
    .map(([, label]) => label);
}
