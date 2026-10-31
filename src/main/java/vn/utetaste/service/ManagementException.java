package vn.utetaste.service;

/** Distinguishes stale identifiers from records that cannot be safely changed. */
public class ManagementException extends BusinessException {
    public enum Kind { NOT_FOUND, CONFLICT }
    private final Kind kind;
    private ManagementException(Kind kind,String message) { super(message);this.kind=kind; }
    public Kind kind() { return kind; }
    public static ManagementException missing(String message) { return new ManagementException(Kind.NOT_FOUND,message); }
    public static ManagementException conflict(String message) { return new ManagementException(Kind.CONFLICT,message); }
}
