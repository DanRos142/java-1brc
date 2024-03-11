package com.onebrc;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collector;

import static java.nio.file.Paths.get;
import static java.util.stream.Collectors.groupingBy;

public class CalculateAverageBaseline {

    private static final String FILE = "./measurements.txt";

    private static record Measurement(String station, double value) {
        private Measurement(String[] parts) {
            this(parts[0], Double.parseDouble(parts[1]));
        }
    }

    private static record ResultRow(double min, double mean, double max) {

        public String toString() {
            return round(min) + "/" + round(mean) + "/" + round(max);
        }

        private double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }
    };

    private static class MeasurementAggregator {
        private double min = Double.POSITIVE_INFINITY;
        private double max = Double.NEGATIVE_INFINITY;
        private double sum;
        private long count;
    }

    public static void main(String[] args) throws IOException {
        var start = Instant.now();

        calculate();

        System.out.println(Duration.between(start, Instant.now()).toString());

    }

    private static void calculate() throws IOException {
        Collector<Measurement, MeasurementAggregator, ResultRow> collector = Collector.of(
            MeasurementAggregator::new,
            (a, m) -> {
                a.min = Math.min(a.min, m.value);
                a.max = Math.max(a.max, m.value);
                a.sum += m.value;
                a.count++;
            },
            (agg1, agg2) -> {
                var res = new MeasurementAggregator();
                res.min = Math.min(agg1.min, agg2.min);
                res.max = Math.max(agg1.max, agg2.max);
                res.sum = agg1.sum + agg2.sum;
                res.count = agg1.count + agg2.count;

                return res;
            },
            agg -> {
                return new ResultRow(agg.min, (Math.round(agg.sum * 10.0) / 10.0) / agg.count, agg.max);
            });

        Map<String, ResultRow> measurements = new TreeMap<>(Files.lines(get(FILE))
            .map(l -> new Measurement(l.split(";")))
            .collect(groupingBy(m -> m.station(), collector)));

        System.out.println(measurements);
    }
}
