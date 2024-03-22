package com.onebrc;

import sun.misc.Unsafe;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
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
    private static final int MAP_SIZE = 16384; // 2^14, closest to 10_000
    private static final int MAP_MASK = MAP_SIZE - 1;

    private static class Aggregate {
        private String name;
        private final long nameStart;
        private final long nameEnd;
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

        Aggregate[][] results = new Aggregate[processors][MAP_SIZE];

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

        var combined = new TreeMap<String, Aggregate>();

        for (var result : results) {
            for (var aggregate : result) {
                if (aggregate == null) continue;
                var existing = combined.get(aggregate.name);
                if (existing == null) combined.put(aggregate.name, aggregate);
                else existing.merge(aggregate);
            }
        }

        System.out.println(combined);
    }

    private static Aggregate[] processChunk(
        Unsafe unsafe,
        long address,
        long start,
        long end
    ) {
        var aggregates = new Aggregate[MAP_SIZE];
        var bytes = new byte[Long.BYTES];
        long offset = start;
        long word;
        while (offset < end) {
            long nameStart = address + offset;

            int hash = 33;

            while (true) {
                word = unsafe.getLong(address + offset);
                bytes[0] = (byte) word;
                bytes[1] = (byte) (word >> 8);
                bytes[2] = (byte) (word >> 16);
                bytes[3] = (byte) (word >> 24);
                bytes[4] = (byte) (word >> 32);
                bytes[5] = (byte) (word >> 40);
                bytes[6] = (byte) (word >> 48);
                bytes[7] = (byte) (word >> 56);

                boolean stop = false;
                for (int i = 0; i < Long.BYTES; i++) {
                    if (bytes[i] == DELIMITER) {
                        offset += i;
                        stop = true;
                        break;
                    }
                    hash = hash * 31 + bytes[i];
                }
                if (stop) break;
                offset += Long.BYTES;
            }

            long nameEnd = address + offset;

            // skip \n
            offset++;

            word = unsafe.getLong(address + offset);

            int temperature = 0;
            boolean negative = false;

            bytes[0] = (byte) word;
            bytes[1] = (byte) (word >> 8);
            bytes[2] = (byte) (word >> 16);
            bytes[3] = (byte) (word >> 24);
            bytes[4] = (byte) (word >> 32);
            bytes[5] = (byte) (word >> 40);
            bytes[6] = (byte) (word >> 48);
            bytes[7] = (byte) (word >> 56);

            for (int i = 0; i < Long.BYTES; i++) {
                if (bytes[i] == NEW_LINE) {
                    offset += i + 1;
                    break;
                }
                if (bytes[i] == MINUS_SIGN) {
                    negative = true;
                    continue;
                }
                if (bytes[i] == DOT) continue;
                temperature *= 10;
                temperature += bytes[i] - ZERO;
            }

            if (negative) temperature = -temperature;

            int index = hash & MAP_MASK;
            Aggregate aggregate;
            int currNameLength = (int) (nameEnd - nameStart);

            while (true) {
                aggregate = aggregates[index];
                if (aggregate == null) {
                    aggregates[index] = new Aggregate(nameStart, nameEnd, (double) temperature/10);
                    break;
                }
                int nameLength = (int) (aggregate.nameEnd - aggregate.nameStart);
                if (nameLength != currNameLength || !unsafeEquals(unsafe, nameStart, aggregate.nameStart, nameLength)) {
                    index = (index + 1) & MAP_MASK;
                } else {
                    aggregate.add((double) temperature/10);
                    break;
                }
            }
        }

        for (Aggregate aggregate : aggregates) {
            if (aggregate == null) continue;
            int size = (int) (aggregate.nameEnd - aggregate.nameStart);
            var name = new byte[size];
            unsafe.copyMemory(null, aggregate.nameStart, name, Unsafe.ARRAY_BYTE_BASE_OFFSET, size);
            aggregate.name = new String(name);
        }

        return aggregates;
    }

    private static boolean unsafeEquals(Unsafe unsafe, long firstAddress, long secondAddress, int nameLength) {
        // compare 8 bytes while possible
        while (nameLength > Long.BYTES) {
            if (unsafe.getLong(firstAddress) != unsafe.getLong(secondAddress)) {
                return false;
            }
            nameLength -= Long.BYTES;
            firstAddress += Long.BYTES;
            secondAddress += Long.BYTES;
        }

        // byte comparison for the leftover if any
        while (nameLength > 0) {
            if (unsafe.getByte(firstAddress) != unsafe.getByte(secondAddress)) {
                return false;
            }
            nameLength--;
            firstAddress++;
            secondAddress++;
        }
        return true;
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
