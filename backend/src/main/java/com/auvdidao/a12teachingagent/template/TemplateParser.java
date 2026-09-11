package com.auvdidao.a12teachingagent.template;

import org.springframework.core.io.Resource;

import java.io.IOException;

public interface TemplateParser {
    ParseResult parse(Resource source) throws IOException;

    record ParseResult(String snapshotJson, String checksum, int slideCount) { }
}


