package com.mindsweeper.indexing;

public final class BookNotReadyException extends RuntimeException {
    public BookNotReadyException(String message) {
        super(message);
    }
}
