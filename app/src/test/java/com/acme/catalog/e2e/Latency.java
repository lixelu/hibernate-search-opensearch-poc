package com.acme.catalog.e2e;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A named set of latency samples in milliseconds.
 *
 * <p>Percentiles are computed with nearest-rank on the sorted samples — no interpolation,
 * no estimator. At the sample counts an end-to-end suite produces, an approximate
 * histogram would add error for no benefit, and a reader can check the arithmetic.
 */
public final class Latency {

    private final String scenario;
    private final String engine;
    private final List<Double> samples = new ArrayList<>();

    public Latency(String scenario, String engine) {
        this.scenario = scenario;
        this.engine = engine;
    }

    public void record(double millis) {
        samples.add(millis);
    }

    /** Times the call, records the elapsed wall time, and returns whatever it produced. */
    public <T> T time(java.util.function.Supplier<T> call) {
        long startedAt = System.nanoTime();
        try {
            return call.get();
        } finally {
            record((System.nanoTime() - startedAt) / 1_000_000d);
        }
    }

    public String scenario() {
        return scenario;
    }

    public String engine() {
        return engine;
    }

    public int count() {
        return samples.size();
    }

    public double percentile(double p) {
        if (samples.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.min(sorted.size() - 1, Math.max(0, rank - 1)));
    }

    public double min() {
        return samples.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
    }

    public double max() {
        return samples.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
    }

    public double mean() {
        return samples.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }
}
