package com.acme.catalog.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects what the run measured and writes it to {@code target/e2e-report.md} when the
 * JVM exits.
 *
 * <p>A pass/fail is the assertion's job; this exists because the interesting output of a
 * migration suite is the comparison itself — which engine answered how fast, how long a
 * write took to become visible, and whether the engines agreed. That belongs in an
 * artefact a human reads, not only in a green tick.
 */
public final class E2eReport {

    private static final Map<String, List<Latency>> LATENCIES = new LinkedHashMap<>();
    private static final List<String> NOTES = new ArrayList<>();
    private static final List<String[]> PARITY = new ArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(E2eReport::write));
    }

    private E2eReport() {
    }

    public static synchronized void latency(String section, Latency latency) {
        if (latency.count() > 0) {
            LATENCIES.computeIfAbsent(section, key -> new ArrayList<>()).add(latency);
        }
    }

    public static synchronized void parity(String scenario, String engine, long total, boolean agrees) {
        PARITY.add(new String[]{scenario, engine, Long.toString(total), agrees ? "yes" : "NO"});
    }

    public static synchronized void note(String note) {
        NOTES.add(note);
    }

    private static synchronized void write() {
        StringBuilder md = new StringBuilder();
        md.append("# End-to-end run\n\n")
                .append("Target: `").append(E2eConfig.baseUrl()).append("`  \n")
                .append("Engines: ").append(String.join(", ", E2eConfig.engines())).append("  \n")
                .append("Iterations: ").append(E2eConfig.iterations())
                .append(" (after ").append(E2eConfig.warmup()).append(" warm-up)  \n")
                .append("Finished: ").append(Instant.now()).append("\n");

        LATENCIES.forEach((section, entries) -> {
            md.append("\n## ").append(section).append("\n\n")
                    .append("| scenario | engine | n | p50 | p95 | p99 | max |\n")
                    .append("|---|---|---:|---:|---:|---:|---:|\n");
            entries.forEach(l -> md.append("| ").append(l.scenario())
                    .append(" | ").append(l.engine())
                    .append(" | ").append(l.count())
                    .append(" | ").append(ms(l.percentile(50)))
                    .append(" | ").append(ms(l.percentile(95)))
                    .append(" | ").append(ms(l.percentile(99)))
                    .append(" | ").append(ms(l.max()))
                    .append(" |\n"));
        });

        if (!PARITY.isEmpty()) {
            md.append("\n## Result parity against the MySQL baseline\n\n")
                    .append("| scenario | engine | total hits | identical ids |\n|---|---|---:|---|\n");
            PARITY.forEach(row -> md.append("| ").append(String.join(" | ", row)).append(" |\n"));
        }

        if (!NOTES.isEmpty()) {
            md.append("\n## Notes\n\n");
            NOTES.forEach(note -> md.append("- ").append(note).append('\n'));
        }

        try {
            Path target = Path.of("target", "e2e-report.md");
            Files.createDirectories(target.getParent());
            Files.writeString(target, md.toString());
            System.out.println("\n[e2e] report written to " + target.toAbsolutePath() + "\n");
            System.out.println(md);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String ms(double value) {
        return Double.isNaN(value) ? "—" : String.format("%.1f ms", value);
    }
}
