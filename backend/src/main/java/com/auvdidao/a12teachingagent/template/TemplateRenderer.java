package com.auvdidao.a12teachingagent.template;

import org.springframework.core.io.Resource;

public interface TemplateRenderer {
    RenderResult render(Resource source);

    record RenderResult(boolean implemented, String adapter, String adapterVersion, String previewReference,
                        Integer slideCount, String sourceSha256, String outputSha256, Long outputSizeBytes,
                        String message) {
        public RenderResult(boolean implemented, String adapter, String previewReference, Integer slideCount, String message) {
            this(implemented, adapter, null, previewReference, slideCount, null, null, null, message);
        }

        static RenderResult notImplemented(String adapter, String message) {
            return new RenderResult(false, adapter, null, null, null, null, null, null, message);
        }
    }
}

