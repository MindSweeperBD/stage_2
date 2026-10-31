package mindsweeper;

public final class BookNotReadyException extends RuntimeException {
    public BookNotReadyException(String message) {
        super(message);
    }
}
