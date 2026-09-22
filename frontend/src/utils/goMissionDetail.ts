export interface GoMissionDetailCollections {
  messages?: unknown[] | null;
  files?: unknown[] | null;
  generationJobs?: unknown[] | null;
  artifacts?: unknown[] | null;
  gameJobs?: unknown[] | null;
  gameArtifacts?: unknown[] | null;
}

export function normalizeGoMissionCollections<T extends GoMissionDetailCollections>(data: T) {
  return {
    ...data,
    messages: data.messages ?? [],
    files: data.files ?? [],
    generationJobs: data.generationJobs ?? [],
    artifacts: data.artifacts ?? [],
    gameJobs: data.gameJobs ?? [],
    gameArtifacts: data.gameArtifacts ?? [],
  };
}
