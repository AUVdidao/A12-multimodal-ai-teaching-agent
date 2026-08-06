package com.auvdidao.a12teachingagent.knowledge;

import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.knowledge.KnowledgeChunk;
import com.auvdidao.a12teachingagent.domain.knowledge.repository.KnowledgeChunkRepository;
import com.auvdidao.a12teachingagent.domain.material.MaterialPurpose;
import com.auvdidao.a12teachingagent.domain.material.ParseResult;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.MaterialPurposeRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.ParseResultRepository;
import com.auvdidao.a12teachingagent.knowledge.dto.KnowledgeDtos.KnowledgeChunkResponse;
import com.auvdidao.a12teachingagent.material.chunk.ChunkSplitter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;

@Service
public class KnowledgeIndexService {

    private final KnowledgeChunkRepository chunkRepository;
    private final ParseResultRepository parseResultRepository;
    private final MaterialPurposeRepository purposeRepository;
    private final ChunkSplitter chunkSplitter;

    public KnowledgeIndexService(
            KnowledgeChunkRepository chunkRepository,
            ParseResultRepository parseResultRepository,
            MaterialPurposeRepository purposeRepository,
            ChunkSplitter chunkSplitter
    ) {
        this.chunkRepository = chunkRepository;
        this.parseResultRepository = parseResultRepository;
        this.purposeRepository = purposeRepository;
        this.chunkSplitter = chunkSplitter;
    }

    @Transactional
    public List<KnowledgeChunkResponse> index(UploadedMaterial material) {
        ParseResult parseResult = parseResultRepository
                .findFirstByMaterialIdOrderByCreatedAtDescIdDesc(material.getId())
                .filter(result -> result.getParseStatus() == MaterialParseStatus.SUCCEEDED)
                .orElseThrow(() -> new ConflictException("Only successfully parsed materials can be indexed"));

        String extractedText = parseResult.getExtractedText();
        if (extractedText == null || extractedText.isBlank()) {
            throw new ConflictException("Parsed material has no extracted text");
        }

        List<PurposeType> usages = purposeRepository.findByMaterialIdOrderByIdAsc(material.getId()).stream()
                .map(MaterialPurpose::getPurposeType)
                .distinct()
                .toList();
        if (usages.isEmpty()) {
            throw new ConflictException("Material purpose is required before indexing");
        }

        List<String> contents = chunkContents(parseResult.getSections(), extractedText);
        if (contents.isEmpty()) {
            throw new ConflictException("Parsed material does not contain useful text");
        }

        chunkRepository.deleteByMaterialId(material.getId());
        chunkRepository.flush();

        List<KnowledgeChunk> chunks = new java.util.ArrayList<>();
        for (int index = 0; index < contents.size(); index++) {
            chunks.add(chunk(material, index + 1, contents.get(index), usages, parseResult));
        }

        List<KnowledgeChunk> saved = chunkRepository.saveAll(chunks);
        chunkRepository.flush();
        if (saved.size() != contents.size()) {
            throw new ConflictException("Persisted knowledge chunk count does not match parsed content");
        }

        parseResult.setChunkCount(saved.size());
        parseResultRepository.saveAndFlush(parseResult);
        return saved.stream().map(KnowledgeIndexService::toResponse).toList();
    }

    private List<String> chunkContents(List<String> sections, String extractedText) {
        if (sections == null || sections.isEmpty()) {
            return chunkSplitter.split(extractedText);
        }
        return sections.stream()
                .filter(section -> section != null && !section.isBlank())
                .map(String::strip)
                .flatMap(section -> chunkSplitter.split(section).stream())
                .toList();
    }

    public static KnowledgeChunkResponse toResponse(KnowledgeChunk chunk) {
        return new KnowledgeChunkResponse(
                chunk.getId(),
                chunk.getProjectId(),
                chunk.getMaterialId(),
                chunk.getChunkNo(),
                chunk.getSourceFilename(),
                chunk.getTitle(),
                chunk.getContent(),
                chunk.getKeywords(),
                chunk.getUsageTypes(),
                chunk.getCreatedAt()
        );
    }

    private static KnowledgeChunk chunk(
            UploadedMaterial material,
            int chunkNo,
            String content,
            List<PurposeType> usages,
            ParseResult parseResult
    ) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setProjectId(material.getProjectId());
        chunk.setMaterialId(material.getId());
        chunk.setChunkNo(chunkNo);
        chunk.setSourceFilename(material.getOriginalFileName());
        chunk.setTitle(chunkTitle(content, chunkNo, material.getOriginalFileName()));
        chunk.setContent(content);
        chunk.setKeywords(keywords(parseResult, content));
        chunk.setUsageTypes(usages);
        return chunk;
    }

    private static String chunkTitle(String content, int chunkNo, String filename) {
        if (content != null) {
            int chineseColon = content.indexOf('：');
            int asciiColon = content.indexOf(':');
            int separator = chineseColon >= 0 && asciiColon >= 0
                    ? Math.min(chineseColon, asciiColon)
                    : Math.max(chineseColon, asciiColon);
            if (separator > 0 && separator <= 32) {
                String title = content.substring(0, separator).strip();
                if (!title.isBlank()) {
                    return title;
                }
            }
        }
        return "Chunk " + chunkNo + " - " + filename;
    }

    private static List<String> keywords(ParseResult parseResult, String content) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        if (parseResult.getKeywords() != null) {
            keywords.addAll(parseResult.getKeywords());
        }
        String[] words = content.split("\\s+");
        for (String word : words) {
            if (word.length() >= 2 && keywords.size() < 12) {
                keywords.add(word.replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", ""));
            }
        }
        return List.copyOf(keywords);
    }
}
