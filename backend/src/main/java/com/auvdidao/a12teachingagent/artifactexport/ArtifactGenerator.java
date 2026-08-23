package com.auvdidao.a12teachingagent.artifactexport;

import com.auvdidao.a12teachingagent.domain.generation.GeneratedArtifact;
import com.auvdidao.a12teachingagent.domain.project.Project;

interface ArtifactGenerator {
    byte[] renderDocx(Project project, GeneratedArtifact artifact);
}
