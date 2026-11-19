package vn.edu.hcmut.vinabeef.health.adapter.web;

import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HealthReceipt;

public record ProblemResponse(String type, String title, int status, String code, String detail,
                              HealthReceipt receipt) {
    public static ProblemResponse from(int status, String code, String detail, HealthReceipt receipt) {
        String title = switch (status) {
            case 401 -> "Unauthenticated";
            case 403 -> "Forbidden";
            case 404 -> "Not found";
            case 409 -> "Conflict";
            case 422 -> "Invalid input";
            case 503 -> "Authority unavailable";
            default -> "Request failed";
        };
        return new ProblemResponse("about:blank", title, status, code, detail, receipt);
    }
}
