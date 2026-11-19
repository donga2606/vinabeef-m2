package vn.edu.hcmut.vinabeef.health.domain;

import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HealthReceipt;

public class HealthException extends RuntimeException {
    private final int httpStatus;
    private final String code;
    private final HealthReceipt receipt;

    public HealthException(int httpStatus, String code, String detail) {
        this(httpStatus, code, detail, null);
    }

    public HealthException(int httpStatus, String code, String detail, HealthReceipt receipt) {
        super(detail);
        this.httpStatus = httpStatus;
        this.code = code;
        this.receipt = receipt;
    }

    public int httpStatus() { return httpStatus; }
    public String code() { return code; }
    public HealthReceipt receipt() { return receipt; }

    public static HealthException unauthenticated() {
        return new HealthException(401, "UNAUTHENTICATED", "A trusted authenticated principal is required.");
    }

    public static HealthException forbidden() {
        return new HealthException(403, "ACCESS_DENIED", "The action is not permitted for this target.");
    }

    public static HealthException unavailable(String code) {
        return new HealthException(503, code, "Current identity or authorization authority is unavailable.");
    }

    public static HealthException notFound(String resource) {
        return new HealthException(404, "NOT_FOUND", "The requested " + resource + " was not found.");
    }

    public static HealthException invalid(String detail) {
        return new HealthException(422, "INVALID_INPUT", detail);
    }

    public static HealthException conflict(String code, String detail) {
        return new HealthException(409, code, detail);
    }

    public static HealthException conflict(HealthReceipt receipt) {
        return new HealthException(409, receipt.reasonCode(), "This intent needs review before it can be accepted.", receipt);
    }
}
