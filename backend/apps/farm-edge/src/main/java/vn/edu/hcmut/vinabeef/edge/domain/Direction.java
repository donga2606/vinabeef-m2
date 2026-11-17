package vn.edu.hcmut.vinabeef.edge.domain;

public enum Direction {
    IN, OUT;

    public Presence presenceAfter() {
        return this == IN ? Presence.INSIDE : Presence.OUTSIDE;
    }
}
