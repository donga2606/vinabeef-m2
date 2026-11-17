package vn.edu.hcmut.vinabeef.reader.emulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

/** CLI entry point. Prints JSON lines to stdout, or POSTs each observation when --post is given. */
public final class ReaderEmulator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReaderEmulator() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.print(EmulatorOptions.USAGE);
            return;
        }
        EmulatorOptions options;
        try {
            options = EmulatorOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.print(EmulatorOptions.USAGE);
            System.exit(2);
            return;
        }

        List<ObjectNode> observations = new ObservationGenerator(UUID::randomUUID).generate(options);
        HttpClient http = options.postUrl() == null ? null : HttpClient.newHttpClient();
        int failures = 0;
        long maxLatency = 0;

        for (int i = 0; i < observations.size(); i++) {
            if (i > 0 && options.realtime()) {
                Thread.sleep(options.interval());
            }
            String json = observations.get(i).toString();
            if (http == null) {
                System.out.println(json);
                continue;
            }
            HttpRequest req = HttpRequest.newBuilder(options.postUrl())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            ObjectNode line = MAPPER.createObjectNode();
            line.put("observationId", observations.get(i).get("observationId").asText());
            long started = System.nanoTime();
            try {
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                line.put("status", res.statusCode());
                line.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
                line.set("response", parseOrText(res.body()));
                if (res.statusCode() >= 300) {
                    failures++;
                }
            } catch (IOException e) {
                failures++;
                line.put("status", 0);
                line.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
                line.put("error", String.valueOf(e.getMessage()));
            }
            maxLatency = Math.max(maxLatency, line.get("latencyMs").asLong());
            System.out.println(line);
        }
        if (http != null) {
            System.err.println("sent=" + observations.size() + " failures=" + failures + " maxLatencyMs=" + maxLatency);
        }
        if (failures > 0) {
            System.err.println(failures + " observation(s) not accepted");
            System.exit(1);
        }
    }

    private static JsonNode parseOrText(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            return TextNode.valueOf(body);
        }
    }
}
