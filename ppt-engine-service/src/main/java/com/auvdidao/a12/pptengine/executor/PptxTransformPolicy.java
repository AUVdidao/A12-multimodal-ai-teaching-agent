package com.auvdidao.a12.pptengine.executor;

import com.auvdidao.a12.pptengine.contract.ContractModels;
import com.auvdidao.a12.pptengine.contract.ContractTypes;
import org.w3c.dom.Element;

/** The single transform policy used by text, shape, and picture execution paths. */
final class PptxTransformPolicy {

    private static final String IMAGE_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";

    private PptxTransformPolicy() {
    }

    static void apply(Element object, ContractModels.Bounds bounds,
                      ContractTypes.TransformConstraint requested,
                      ContractTypes.TransformConstraint allowed) {
        if (requested == null || allowed == null || requested != allowed) {
            throw new TransformConstraintException("transform declaration mismatch");
        }
        Element xfrm = PptxPackage.descendants(object, IMAGE_NS, "xfrm").stream()
                .findFirst().orElseThrow(() -> new TransformConstraintException("transform nodes missing"));
        Element off = PptxPackage.child(xfrm, IMAGE_NS, "off");
        Element ext = PptxPackage.child(xfrm, IMAGE_NS, "ext");
        if (off == null || ext == null) {
            throw new TransformConstraintException("transform nodes missing");
        }
        ContractModels.Bounds current = new ContractModels.Bounds(
                parseEmu(off.getAttribute("x")), parseEmu(off.getAttribute("y")),
                parseEmu(ext.getAttribute("cx")), parseEmu(ext.getAttribute("cy")));
        if (bounds == null || bounds.widthEmu() <= 0 || bounds.heightEmu() <= 0) {
            throw new TransformConstraintException("bounds missing or non-positive");
        }
        boolean valid = switch (allowed) {
            case FIXED -> bounds.equals(current);
            case TRANSLATE_ONLY -> bounds.widthEmu() == current.widthEmu()
                    && bounds.heightEmu() == current.heightEmu();
            case UNIFORM_SCALE -> current.widthEmu() > 0 && current.heightEmu() > 0
                    && (long) bounds.widthEmu() * current.heightEmu()
                    == (long) bounds.heightEmu() * current.widthEmu();
            case STRETCH_X -> bounds.topEmu() == current.topEmu()
                    && bounds.heightEmu() == current.heightEmu();
            case STRETCH_Y -> bounds.leftEmu() == current.leftEmu()
                    && bounds.widthEmu() == current.widthEmu();
            case RESPONSIVE -> true;
        };
        if (!valid) {
            throw new TransformConstraintException("bounds exceed " + allowed.name());
        }
        if (allowed == ContractTypes.TransformConstraint.FIXED) {
            return;
        }
        if (allowed != ContractTypes.TransformConstraint.STRETCH_Y) {
            off.setAttribute("x", Integer.toString(bounds.leftEmu()));
        }
        if (allowed != ContractTypes.TransformConstraint.STRETCH_X) {
            off.setAttribute("y", Integer.toString(bounds.topEmu()));
        }
        if (allowed != ContractTypes.TransformConstraint.TRANSLATE_ONLY
                && allowed != ContractTypes.TransformConstraint.STRETCH_Y) {
            ext.setAttribute("cx", Integer.toString(bounds.widthEmu()));
        }
        if (allowed != ContractTypes.TransformConstraint.TRANSLATE_ONLY
                && allowed != ContractTypes.TransformConstraint.STRETCH_X) {
            ext.setAttribute("cy", Integer.toString(bounds.heightEmu()));
        }
    }

    private static int parseEmu(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new TransformConstraintException("invalid geometry");
        }
    }
}
