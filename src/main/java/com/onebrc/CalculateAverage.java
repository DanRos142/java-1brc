package com.onebrc;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
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
        private double min;
        private double max;
        private double sum;
        private long count;

        Aggregate(double value) {
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

    public static void main(String[] args) throws IOException, InterruptedException {
        var start = Instant.now();
        calculate();
        System.out.println(Duration.between(start, Instant.now()).toString());
    }

    private static void calculate() throws IOException, InterruptedException {
        var channel = FileChannel.open(Path.of(FILE), StandardOpenOption.READ);

        long size = channel.size();

        int processors = Runtime.getRuntime().availableProcessors();
        long partSize = size / processors;

        @SuppressWarnings("unchecked")
        Map<Integer, Aggregate>[] results = new Map[processors];

        var mappedBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, Arena.global());
        var nameMap = new HashMap<Integer, byte[]>();

        var executor = Executors.newFixedThreadPool(processors);

        long start = 0;
        for (int i = 0; i < processors; i++) {
            long end = start + partSize + 1;
            long endPos = Math.min(end + getOffset(channel, end), size);

            int curr = i;
            long startPos = start;

            executor.submit(() -> {
                results[curr] = processChunk(mappedBuffer, startPos, endPos, nameMap);
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

        combined.forEach((k, v) -> result.put(
            new String(nameMap.get(k)),
            v.toString()
        ));

        System.out.println(result);
    }

    private static Map<Integer, Aggregate> processChunk(
        MemorySegment memorySegment,
        long start,
        long end,
        Map<Integer, byte[]> nameMap
    ) {
        try {
            var map = new HashMap<Integer, Aggregate>();

            var parseBuffer = ByteBuffer.allocate(128);

            int hash = 0;
            long offset = start;
            while (offset < end) {
                byte b = memorySegment.get(ValueLayout.OfByte.JAVA_BYTE, offset);

                if (b == DELIMITER) {
                    var trimmed = trim(parseBuffer);
                    hash = trimmed.hashCode();
                    nameMap.put(hash, trimmed.array());
                    parseBuffer.clear();
                }
                else if (b == NEW_LINE) {
                    double value = fromBytes(parseBuffer.array(), parseBuffer.position());

                    var aggregate = map.get(hash);
                    if (aggregate == null) map.put(hash, new Aggregate(value));
                    else aggregate.add(value);
                    parseBuffer.clear();
                } else {
                    parseBuffer.put(b);
                }
                offset++;
            }
            return map;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ByteBuffer trim(ByteBuffer buffer) {
        var bytes = new byte[buffer.position()];
        System.arraycopy(buffer.array(), 0, bytes, 0, buffer.position());
        return ByteBuffer.wrap(bytes);
    }

    private static double fromBytes(byte[] bytes, int position) {
        boolean signed = false;

        int acc = 0;
        int divider = 0;
        for (int i = 0; i < position; i++) {
            byte b = bytes[i];
            if (b == MINUS_SIGN) {
                signed = true;
            } else if (b == DOT) {;
                divider = position - i - 1 > 1 ? 100 : 10;
            } else {
                acc *= 10;
                acc += b - ZERO;
            }
        }

        if (signed) acc = -acc;

        return (double) acc/divider;

    }

    private static int getOffset(FileChannel channel, long start) throws IOException {
        var buffer = ByteBuffer.allocate(128);
        channel.read(buffer, start);

        buffer.rewind();

        int offset = 0;

        while (buffer.hasRemaining()) {
            if (buffer.get() == NEW_LINE) break;
            offset++;
        }

        return offset;
    }
}
