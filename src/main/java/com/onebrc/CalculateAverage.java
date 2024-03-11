package com.onebrc;

import sun.misc.Unsafe;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class CalculateAverage {

    private static final String FILE = "./measurements.txt";
    private static final byte DELIMITER = 59;
    private static final byte NEW_LINE = 10;
    private static final byte MINUS_SIGN = 45;
    private static final byte DOT = 46;
    private static final byte ZERO = 48;

    private static class Aggregate {
        private long nameStart;
        private long nameEnd;
        private double min;
        private double max;
        private double sum;
        private long count;

        Aggregate(long nameStart, long nameEnd, double value) {
            this.nameStart = nameStart;
            this.nameEnd = nameEnd;
            this.min = value;
            this.max = value;
            this.sum = value;
            this.count = 1;
        }

        void add(double value) {
            this.min = Math.min(this.min, value);
            this.max = Math.max(this.max, value);
            this.sum += value;
            this.count++;
        }

        void merge(Aggregate other) {
            this.min = Math.min(this.min, other.min);
            this.max = Math.max(this.max, other.max);
            this.sum += other.sum;
            this.count += other.count;
        }

        public String toString() {
            double mean = (Math.round(this.sum * 10.0) / 10.0) / this.count;
            return round(min) + "/" + round(mean) + "/" + round(max);
        }

        private double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException, NoSuchFieldException, IllegalAccessException {
        var start = Instant.now();
        calculate();
        System.out.println(Duration.between(start, Instant.now()).toString());
    }

    private static void calculate() throws IOException, InterruptedException, NoSuchFieldException, IllegalAccessException {
        var channel = FileChannel.open(Path.of(FILE), StandardOpenOption.READ);

        long size = channel.size();

        int processors = Runtime.getRuntime().availableProcessors();
        long partSize = size / processors;

        @SuppressWarnings("unchecked")
        Map<Integer, Aggregate>[] results = new Map[processors];

        var segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, Arena.global());
        long address = segment.address();

        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        var unsafe = (Unsafe) f.get(null);

        var executor = Executors.newFixedThreadPool(processors);

        long start = 0;
        for (int i = 0; i < processors; i++) {
            long end = start + partSize + 1;
            long endPos = Math.min(end + getOffset(unsafe, address, end, size), size);

            int curr = i;
            long startPos = start;

            executor.submit(() -> {
                results[curr] = processChunk(unsafe, address, startPos, endPos);
            });
            start = endPos + 1;
        }

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.HOURS);

        var combined = new HashMap<Integer, Aggregate>();

        for (var map : results) {
            for (var entry : map.entrySet()) {
                if (!combined.containsKey(entry.getKey())) {
                    combined.put(entry.getKey(), entry.getValue());
                } else {
                    combined.get(entry.getKey()).merge(entry.getValue());
                }
            }
        }

        var result = new TreeMap<String, String>();

        combined.values().forEach(v -> {
            result.put(
                new String(segment.asSlice(v.nameStart - address, v.nameEnd - v.nameStart)
                    .toArray(ValueLayout.JAVA_BYTE)),
                v.toString()
            );
        });

        System.out.println(result);
    }

    private static Map<Integer, Aggregate> processChunk(
        Unsafe unsafe,
        long address,
        long start,
        long end
    ) {
        var map = new HashMap<Integer, Aggregate>();

        long offset = start;
        while (offset < end) {
            long nameStart = address + offset;

            byte b = unsafe.getByte(address + offset);

            int hash = 0;
            while (b != DELIMITER) {
                hash = hash * 33 + b;
                offset++;
                b = unsafe.getByte(address + offset);
            }

            long nameEnd = address + offset;

            // skip \n
            offset++;
            b = unsafe.getByte(address + offset);

            boolean negative = b == MINUS_SIGN;
            if (negative) {
                offset++;
                b = unsafe.getByte(address + offset);
            }

            int temperature = 0;
            while (b != DOT) {
                temperature *= 10;
                temperature += b - ZERO;
                offset++;
                b = unsafe.getByte(address + offset);
            }

            offset++;
            b = unsafe.getByte(address + offset);
            temperature *= 10;
            temperature += b - ZERO;

            if (negative) temperature = -temperature;

            offset += 2; // skip \n and go to the next byte

            var aggregate = map.get(hash);
            if (aggregate == null) map.put(hash, new Aggregate(nameStart, nameEnd, (double) temperature/10));
            else aggregate.add((double) temperature/10);
        }
        return map;
    }

    private static int getOffset(Unsafe unsafe, long address, long start, long size) {
        int offset = 0;

        while (start < size) {
            if (unsafe.getByte(address + start) == NEW_LINE) break;
            offset++;
            start++;
        }

        return offset;
    }
}
