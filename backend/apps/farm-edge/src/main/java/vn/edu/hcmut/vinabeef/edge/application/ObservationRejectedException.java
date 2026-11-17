package vn.edu.hcmut.vinabeef.edge.application;

/** Input that is not a valid gate observation (422). */
public class ObservationRejectedException extends RuntimeException {
    public ObservationRejectedException(String message) {
        super(message);
    }
}
