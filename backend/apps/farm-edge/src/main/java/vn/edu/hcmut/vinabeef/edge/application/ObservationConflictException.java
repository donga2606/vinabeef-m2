package vn.edu.hcmut.vinabeef.edge.application;

/** Same observationId re-sent with different content (409). */
public class ObservationConflictException extends RuntimeException {
    public ObservationConflictException(String message) {
        super(message);
    }
}
