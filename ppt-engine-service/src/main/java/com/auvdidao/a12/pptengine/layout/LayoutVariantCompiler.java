package com.auvdidao.a12.pptengine.layout;

import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import org.springframework.stereotype.Component;

import java.util.Locale;

import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.BODY;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.BULLETS;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.ContentType.TITLE;
import static com.auvdidao.a12.pptengine.contract.ContractTypes.SlotBindingKind.TEXT;

/**
 * Deterministic semantic-to-geometry compiler. The model may describe intent,
 * but it never supplies coordinates. Old specifications without semantic
 * hints retain their confirmed template slot bounds for compatibility.
 */
@Component
public class LayoutVariantCompiler {

    public ContractModels.Bounds boundsFor(
            ContractModels.LockedPptSlide slide,
            ContractModels.ConfirmedTemplateProfile profile,
            ContractModels.ComponentSlot slot,
            ContractTypes.SlotBindingKind bindingKind,
            String bindingId,
            ContractModels.Bounds safeArea) {
        if (slide == null || slot == null || safeArea == null || !activated(slide) || bindingKind != TEXT) {
            return slot == null ? null : slot.bounds();
        }
        ContractTypes.ContentType type = contentType(slide, bindingId, slot);
        int ordinal = ordinal(slide, bindingId, type);
        int count = count(slide, type);
        return boundsFor(type, variant(slide), safeArea, slot.bounds(), ordinal, count, slide);
    }

    public String variant(ContractModels.LockedPptSlide slide) {
        String explicit = normalize(slide == null || slide.semanticLayout() == null
                ? null : slide.semanticLayout().pageType());
        if (isVariant(explicit)) {
            return explicit;
        }
        String title = normalize(slide == null ? null : slide.title());
        String focus = normalize(slide == null || slide.semanticLayout() == null
                ? null : slide.semanticLayout().visualFocus());
        String combined = title + " " + focus;
        if (containsAny(combined, "PROCESS", "FLOW", "PIPELINE", "流程", "步骤", "流水线")) {
            return "PROCESS";
        }
        if (containsAny(combined, "COMPARE", "COMPARISON", "TABLE", "对比", "比较", "区别", "指标")) {
            return "COMPARISON";
        }
        if (containsAny(combined, "SUMMARY", "REVIEW", "RECAP", "小结", "总结", "回顾")) {
            return "SUMMARY";
        }
        if (slide != null && slide.pageNumber() == 1) {
            return "TITLE_IMPORT";
        }
        int blocks = slide == null ? 0 : slide.contentBlocks().size();
        return blocks >= 4 ? "CARDS" : "CONCEPT";
    }

    public boolean activated(ContractModels.LockedPptSlide slide) {
        if (slide == null || slide.semanticLayout() == null) {
            return false;
        }
        ContractModels.SemanticLayout layout = slide.semanticLayout();
        return normalize(layout.pageType()) != null
                || !layout.informationHierarchy().isEmpty()
                || normalize(layout.visualFocus()) != null
                || normalize(layout.contentDensity()) != null
                || !layout.componentRequirements().isEmpty()
                || normalize(layout.sourceConstraint()) != null;
    }

    private ContractTypes.ContentType contentType(
            ContractModels.LockedPptSlide slide,
            String bindingId,
            ContractModels.ComponentSlot slot) {
        if (bindingId != null) {
            for (ContractModels.LockedPptContentBlock block : slide.contentBlocks()) {
                if (bindingId.equals(block.blockId())) {
                    return block.type();
                }
            }
        }
        return slot.acceptedContentTypes().stream().findFirst().orElse(ContractTypes.ContentType.TEXT);
    }

    private int ordinal(ContractModels.LockedPptSlide slide, String bindingId, ContractTypes.ContentType type) {
        int index = 0;
        for (ContractModels.LockedPptContentBlock block : slide.contentBlocks()) {
            if (block.type() == type) {
                if (bindingId != null && bindingId.equals(block.blockId())) {
                    return index;
                }
                index++;
            }
        }
        return 0;
    }

    private int count(ContractModels.LockedPptSlide slide, ContractTypes.ContentType type) {
        return (int) slide.contentBlocks().stream().filter(block -> block.type() == type).count();
    }

    private ContractModels.Bounds boundsFor(
            ContractTypes.ContentType type,
            String variant,
            ContractModels.Bounds area,
            ContractModels.Bounds original,
            int ordinal,
            int count,
            ContractModels.LockedPptSlide slide) {
        int left = area.leftEmu();
        int top = area.topEmu();
        int width = area.widthEmu();
        int height = area.heightEmu();
        int gap = Math.max(90000, Math.min(180000, width / 100));
        int titleHeight = Math.min(850000, Math.max(600000, height / 8));
        if (type == TITLE) {
            return box(left, top, width, titleHeight, area);
        }
        boolean hasBody = count(slide, BODY) > 0;
        if (type == BODY) {
            return switch (variant) {
                case "CONCEPT" -> box(left, top + height / 6, width * 38 / 100, height * 25 / 100, area);
                case "PROCESS", "COMPARISON" -> box(left + width / 12, top + height / 6,
                        width * 10 / 12, height / 6, area);
                case "CARDS" -> box(left + width / 20, top + height / 6,
                        width * 9 / 10, height / 7, area);
                case "SUMMARY" -> box(left + width / 10, top + height / 6,
                        width * 8 / 10, height / 5, area);
                default -> box(left + width / 10, top + height / 6,
                        width * 8 / 10, height / 5, area);
            };
        }
        if (type == BULLETS) {
            int bulletLeft;
            int bulletTop;
            int bulletWidth;
            int bulletHeight;
            boolean collectionPositioned = false;
            switch (variant) {
                case "CONCEPT" -> {
                    bulletLeft = hasBody ? left + width * 43 / 100 : left + width / 12;
                    bulletTop = top + height / 6;
                    bulletWidth = hasBody ? width * 51 / 100 : width * 5 / 6;
                    bulletHeight = height * 63 / 100;
                }
                case "PROCESS", "COMPARISON" -> {
                    collectionPositioned = true;
                    bulletLeft = left + width / 12;
                    bulletTop = top + height * 39 / 100;
                    bulletWidth = width * 10 / 12;
                    bulletHeight = height * 49 / 100;
                    if (count > 1) {
                        int available = Math.max(1, bulletWidth - gap * (count - 1));
                        int itemWidth = Math.max(500000, available / count);
                        bulletLeft += ordinal * (itemWidth + gap);
                        bulletWidth = itemWidth;
                    }
                }
                case "CARDS" -> {
                    collectionPositioned = true;
                    bulletLeft = left + width / 20;
                    bulletTop = top + height * 35 / 100;
                    bulletWidth = width * 9 / 10;
                    bulletHeight = height * 55 / 100;
                    if (count > 1) {
                        int columns = 2;
                        int rows = (count + columns - 1) / columns;
                        int availableWidth = Math.max(1, bulletWidth - gap * (columns - 1));
                        int availableHeight = Math.max(1, bulletHeight - gap * (rows - 1));
                        int itemWidth = Math.max(500000, availableWidth / columns);
                        int itemHeight = Math.max(500000, availableHeight / rows);
                        int row = ordinal / columns;
                        int column = ordinal % columns;
                        bulletLeft += column * (itemWidth + gap);
                        bulletTop += row * (itemHeight + gap);
                        bulletWidth = itemWidth;
                        bulletHeight = itemHeight;
                    }
                }
                case "SUMMARY" -> {
                    collectionPositioned = true;
                    bulletLeft = left + width / 10;
                    bulletTop = top + height * 43 / 100;
                    bulletWidth = width * 8 / 10;
                    bulletHeight = height * 42 / 100;
                    if (count > 1) {
                        int available = Math.max(1, bulletWidth - gap * (count - 1));
                        int itemWidth = Math.max(500000, available / count);
                        bulletLeft += ordinal * (itemWidth + gap);
                        bulletWidth = itemWidth;
                    }
                }
                default -> {
                    bulletLeft = left + width / 12;
                    bulletTop = top + height * 35 / 100;
                    bulletWidth = width * 10 / 12;
                    bulletHeight = height * 55 / 100;
                }
            }
            // Process, comparison, card and summary variants already assign
            // each item to its own column/grid cell. Applying the legacy
            // vertical stack a second time would move every item diagonally
            // and make the generated page look sparse or misaligned.
            if (count > 1 && !collectionPositioned) {
                int available = Math.max(1, bulletHeight - gap * (count - 1));
                int itemHeight = Math.max(300000, available / count);
                bulletTop += ordinal * (itemHeight + gap);
                bulletHeight = itemHeight;
            }
            return box(bulletLeft, bulletTop, bulletWidth, bulletHeight, area);
        }
        return original;
    }

    private ContractModels.Bounds box(int left, int top, int width, int height, ContractModels.Bounds area) {
        int safeLeft = Math.max(area.leftEmu(), left);
        int safeTop = Math.max(area.topEmu(), top);
        int safeRight = Math.min(area.leftEmu() + area.widthEmu(), Math.max(safeLeft + 1, left + width));
        int safeBottom = Math.min(area.topEmu() + area.heightEmu(), Math.max(safeTop + 1, top + height));
        return new ContractModels.Bounds(safeLeft, safeTop,
                Math.max(1, safeRight - safeLeft), Math.max(1, safeBottom - safeTop));
    }

    private boolean isVariant(String value) {
        return "TITLE_IMPORT".equals(value) || "CONCEPT".equals(value) || "PROCESS".equals(value)
                || "COMPARISON".equals(value) || "CARDS".equals(value) || "SUMMARY".equals(value);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
