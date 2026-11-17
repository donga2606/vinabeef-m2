package vn.edu.hcmut.vinabeef.reader.emulator;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Command-line options. See {@link #USAGE}. */
record EmulatorOptions(
        String readerKind,
        String readerId,
        int count,
        int readsPerPassage,
        String direction,
        List<String> tags,
        long startSeq,
        Duration interval,
        Instant start,
        int dupEvery,
        int gapEvery,
        boolean realtime,
        URI postUrl) {

    static final String USAGE = """
            reader-emulator: emits TagObservation v1 JSON lines (software contract only, not hardware qualification)
              --kind MOBILE|GATE      reader kind (default GATE)
              --reader-id ID          default GATE-FARM01-A / MOB-EMULATOR-01
              --count N               logical reads (passages for GATE) to emit (default 10)
              --reads-per-passage K   antenna multiplicity: K distinct reads per logical read, 100 ms apart (default 1)
              --tags T1,T2,...        tag values to cycle through (default VN-0001-000001..000005)
              --start-seq N           first seq (default 1)
              --interval-ms N         source-time spacing between reads (default 500)
              --start RFC3339         source time of first read (default now)
              --dup-every N           after every N reads, retransmit the last one (same observationId)
              --gap-every N           after every N reads, skip one seq (simulated missed read)
              --realtime              sleep interval between reads instead of emitting instantly
              --post URL              POST each observation; prints one JSON line per response (status, latencyMs, receipt)
              --direction IN|OUT      GATE only: force this direction (default: alternate per tag, starting with IN)
            MOBILE never sets direction.
            """;

    static EmulatorOptions parse(String... args) {
        String kind = "GATE";
        String readerId = null;
        int count = 10;
        int readsPerPassage = 1;
        String direction = null;
        List<String> tags = new ArrayList<>();
        long startSeq = 1;
        long intervalMs = 500;
        Instant start = Instant.now();
        int dupEvery = 0;
        int gapEvery = 0;
        boolean realtime = false;
        URI post = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--kind" -> kind = value(args, ++i, a).toUpperCase();
                case "--reader-id" -> readerId = value(args, ++i, a);
                case "--count" -> count = Integer.parseInt(value(args, ++i, a));
                case "--reads-per-passage" -> readsPerPassage = Integer.parseInt(value(args, ++i, a));
                case "--direction" -> direction = value(args, ++i, a).toUpperCase();
                case "--tags" -> tags = Arrays.asList(value(args, ++i, a).split(","));
                case "--start-seq" -> startSeq = Long.parseLong(value(args, ++i, a));
                case "--interval-ms" -> intervalMs = Long.parseLong(value(args, ++i, a));
                case "--start" -> start = Instant.parse(value(args, ++i, a));
                case "--dup-every" -> dupEvery = Integer.parseInt(value(args, ++i, a));
                case "--gap-every" -> gapEvery = Integer.parseInt(value(args, ++i, a));
                case "--realtime" -> realtime = true;
                case "--post" -> post = URI.create(value(args, ++i, a));
                default -> throw new IllegalArgumentException("Unknown option: " + a);
            }
        }
        if (!kind.equals("GATE") && !kind.equals("MOBILE")) {
            throw new IllegalArgumentException("--kind must be MOBILE or GATE");
        }
        if (count < 0 || readsPerPassage < 1 || startSeq < 1 || dupEvery < 0 || gapEvery < 0 || intervalMs < 0) {
            throw new IllegalArgumentException("numeric options must be non-negative and --start-seq, --reads-per-passage >= 1");
        }
        if (direction != null && (!kind.equals("GATE") || !(direction.equals("IN") || direction.equals("OUT")))) {
            throw new IllegalArgumentException("--direction needs --kind GATE and IN or OUT");
        }
        if (readerId == null) {
            readerId = kind.equals("GATE") ? "GATE-FARM01-A" : "MOB-EMULATOR-01";
        }
        if (tags.isEmpty()) {
            for (int t = 1; t <= 5; t++) {
                tags.add("VN-0001-%06d".formatted(t));
            }
        }
        return new EmulatorOptions(kind, readerId, count, readsPerPassage, direction, List.copyOf(tags), startSeq,
                Duration.ofMillis(intervalMs), start, dupEvery, gapEvery, realtime, post);
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[i];
    }
}
