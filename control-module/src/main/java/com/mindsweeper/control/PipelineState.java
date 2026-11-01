package com.mindsweeper.control;

public enum PipelineState {
    PENDING,
    INGESTING,
    INGESTED,
    INDEXING,
    INDEXED,
    VERIFIED,
    FAILED
}
