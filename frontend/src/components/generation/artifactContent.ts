import type { ArtifactContent } from '@/api/generation';

export interface PptSlideView {
  order: number;
  title: string;
  subtitle: string;
  bullets: string[];
  notes: string;
}

export interface DocSectionView {
  order: number;
  title: string;
  paragraphs: string[];
}

export interface InteractionOptionView {
  value: string;
  label: string;
  text: string;
  correct?: boolean;
}

export interface InteractionQuestionView {
  order: number;
  question: string;
  type: string;
  options: InteractionOptionView[];
  correctIndex?: number;
  answer: string;
  explanation: string;
}

type UnknownRecord = Record<string, unknown>;

function isRecord(value: unknown): value is UnknownRecord {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function firstValue(record: UnknownRecord, keys: string[]) {
  for (const key of keys) {
    const value = record[key];
    if (value !== undefined && value !== null) return value;
  }
  return undefined;
}

function firstString(record: UnknownRecord, keys: string[]) {
  const value = firstValue(record, keys);
  if (typeof value === 'string') return value.trim();
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return '';
}

function textLeaves(value: unknown, depth = 0): string[] {
  if (depth > 4 || value === null || value === undefined) return [];
  if (typeof value === 'string') return value.trim() ? [value.trim()] : [];
  if (typeof value === 'number' || typeof value === 'boolean') return [String(value)];
  if (Array.isArray(value)) return value.flatMap((item) => textLeaves(item, depth + 1));
  if (!isRecord(value)) return [];

  const preferred = firstString(value, ['text', 'content', 'label', 'title', 'description', 'value']);
  if (preferred) return [preferred];
  return Object.values(value).flatMap((item) => textLeaves(item, depth + 1));
}

function contentItems(content: ArtifactContent, keys: string[]) {
  if (Array.isArray(content)) return content;
  if (!isRecord(content)) return [content];
  for (const key of keys) {
    if (Array.isArray(content[key])) return content[key];
  }
  return [content];
}

function uniqueTexts(values: string[]) {
  return [...new Set(values.map((item) => item.trim()).filter(Boolean))];
}

export function normalizePptSlides(content: ArtifactContent): PptSlideView[] {
  return contentItems(content, ['slides', 'pages', 'items']).map((item, index) => {
    if (!isRecord(item)) {
      return {
        order: index + 1,
        title: `第 ${index + 1} 页`,
        subtitle: '',
        bullets: textLeaves(item),
        notes: '',
      };
    }

    const title = firstString(item, ['title', 'heading', 'name']) || `第 ${index + 1} 页`;
    const subtitle = firstString(item, ['subtitle', 'subTitle', 'description', 'summary']);
    const bodyValue = firstValue(item, ['bullets', 'points', 'items', 'keyPoints', 'content', 'body', 'paragraphs', 'elements']);
    const bullets = uniqueTexts(textLeaves(bodyValue).filter((text) => text !== title && text !== subtitle));
    return {
      order: Number(item.order) || index + 1,
      title,
      subtitle,
      bullets,
      notes: firstString(item, ['notes', 'speakerNotes', 'remark']),
    };
  });
}

export function normalizeDocSections(content: ArtifactContent): DocSectionView[] {
  if (isRecord(content) && (
    'courseInfo' in content
    || 'teachingGoals' in content
    || 'teachingProcess' in content
    || 'classroomActivities' in content
  )) {
    const sections: Array<{ title: string; value: unknown }> = [
      { title: '课程信息', value: formatCourseInfo(content.courseInfo) },
      { title: '教学目标', value: content.teachingGoals },
      { title: '教学重点', value: content.keyPoints },
      { title: '教学难点', value: content.difficultPoints },
      { title: '教学方法', value: content.methods },
      { title: '教学过程', value: formatTeachingProcess(content.teachingProcess) },
      { title: '课堂活动', value: content.classroomActivities },
      { title: '课后作业', value: content.homework },
      { title: '资源说明', value: content.resourceNotes },
    ];
    return sections
      .map((section, index) => ({
        order: index + 1,
        title: section.title,
        paragraphs: uniqueTexts(textLeaves(section.value)),
      }))
      .filter((section) => section.paragraphs.length > 0);
  }

  return contentItems(content, ['sections', 'chapters', 'items', 'content']).map((item, index) => {
    if (!isRecord(item)) {
      return {
        order: index + 1,
        title: `第 ${index + 1} 节`,
        paragraphs: textLeaves(item),
      };
    }

    const title = firstString(item, ['title', 'heading', 'name']) || `第 ${index + 1} 节`;
    const bodyValue = firstValue(item, ['paragraphs', 'content', 'body', 'items', 'points', 'description']);
    return {
      order: Number(item.order) || index + 1,
      title,
      paragraphs: uniqueTexts(textLeaves(bodyValue).filter((text) => text !== title)),
    };
  });
}

function formatCourseInfo(value: unknown) {
  if (!isRecord(value)) return value;
  const labels: Record<string, string> = {
    projectName: '项目名称',
    courseName: '课程名称',
    chapterTopic: '章节主题',
    targetAudience: '授课对象',
    lessonDurationMinutes: '课时长度',
    generationMode: '生成模式',
  };
  return Object.entries(value).map(([key, item]) => {
    const text = textLeaves(item).join('、');
    if (!text) return '';
    const suffix = key === 'lessonDurationMinutes' ? ' 分钟' : '';
    return `${labels[key] || key}：${text}${suffix}`;
  });
}

function formatTeachingProcess(value: unknown) {
  if (!Array.isArray(value)) return value;
  return value.map((item) => {
    if (!isRecord(item)) return textLeaves(item).join('、');
    const stage = firstString(item, ['stage', 'title', 'name']);
    const duration = firstString(item, ['durationMinutes', 'duration']);
    const heading = `${stage}${duration ? `（${duration} 分钟）` : ''}`;
    const details = [
      ['教学内容', firstString(item, ['content', 'description'])],
      ['教师活动', firstString(item, ['teacherActivity'])],
      ['学生活动', firstString(item, ['studentActivity'])],
    ].filter((entry) => entry[1]).map((entry) => `${entry[0]}：${entry[1]}`);
    return [heading, ...details].filter(Boolean).join('\n');
  });
}

function normalizeOptions(value: unknown): InteractionOptionView[] {
  if (!Array.isArray(value)) return [];
  return value.map((item, index) => {
    const defaultLabel = String.fromCharCode(65 + index);
    if (!isRecord(item)) {
      return {
        value: String(index),
        label: defaultLabel,
        text: textLeaves(item).join(' '),
      };
    }
    const label = firstString(item, ['label', 'key', 'code']) || defaultLabel;
    const text = firstString(item, ['text', 'content', 'title', 'description', 'value']) || textLeaves(item).join(' ');
    const rawValue = firstValue(item, ['value', 'id', 'key', 'code']);
    return {
      value: rawValue === undefined ? String(index) : String(rawValue),
      label,
      text,
      correct: typeof item.correct === 'boolean' ? item.correct : typeof item.isCorrect === 'boolean' ? item.isCorrect : undefined,
    };
  });
}

export function normalizeInteractionQuestions(content: ArtifactContent): InteractionQuestionView[] {
  return contentItems(content, ['questions', 'items', 'interactions', 'exercises']).map((item, index) => {
    if (!isRecord(item)) {
      return {
        order: index + 1,
        question: textLeaves(item).join(' '),
        type: '',
        options: [],
        correctIndex: undefined,
        answer: '',
        explanation: '',
      };
    }
    const options = normalizeOptions(firstValue(item, ['options', 'choices', 'answers']));
    const correctOption = item.correctOption;
    const correctIndex = typeof correctOption === 'number' && Number.isInteger(correctOption)
      ? correctOption
      : undefined;
    const answerValue = firstValue(item, ['correctAnswer', 'answer', 'referenceAnswer', 'solution']);
    const correctOptionView = correctIndex === undefined ? undefined : options[correctIndex];
    return {
      order: Number(item.order) || index + 1,
      question: firstString(item, ['question', 'prompt', 'title', 'stem', 'content']) || `第 ${index + 1} 题`,
      type: firstString(item, ['type', 'questionType']),
      options,
      correctIndex,
      answer: correctOptionView
        ? `${correctOptionView.label}. ${correctOptionView.text}`
        : textLeaves(answerValue).join('、'),
      explanation: firstString(item, ['explanation', 'analysis', 'feedback', 'rationale']),
    };
  });
}
