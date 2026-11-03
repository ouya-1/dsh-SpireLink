package spirelink.actions;

/** Raised by the pure request parsers; {@code code} is one of {@link ErrorCodes}. */
public class BadRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public final String code;

    public BadRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static BadRequestException badRequest(String message) {
        return new BadRequestException(ErrorCodes.BAD_REQUEST, message);
    }
}
