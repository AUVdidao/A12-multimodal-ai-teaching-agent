package com.auvdidao.a12.pptengine.contract;

public final class ResourceLimits {

    public static final long MAX_REQUEST_BYTES = 2_000_000L;
    public static final int MAX_SLIDES = 200;
    public static final int MAX_CONTENT_BLOCKS_PER_SLIDE = 100;
    public static final int MAX_ASSETS_PER_SLIDE = 100;
    public static final int MAX_COMPONENTS = 500;
    public static final int MAX_SLOTS_PER_COMPONENT = 100;
    public static final int MAX_NESTING_DEPTH = 20;
    public static final int MAX_TEXT_LENGTH = 4_000;
    public static final int MAX_NOTES_LENGTH = 2_000;
    public static final int MAX_REFERENCE_LENGTH = 500;

    private ResourceLimits() {
    }
}
