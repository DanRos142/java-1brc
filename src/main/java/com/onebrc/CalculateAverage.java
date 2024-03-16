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
import java.util.HashMap;
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
        calculate();
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

        var combined = new HashMap<String, Aggregate>();

        for (var result : results) {
            for (var aggregate : result) {
                if (aggregate == null) continue;
                var existing = combined.get(aggregate.name);
                if (existing == null) combined.put(aggregate.name, aggregate);
                else existing.merge(aggregate);
            }
        }

        System.out.println(new TreeMap<>(combined));
    }

    private static Aggregate[] processChunk(
        Unsafe unsafe,
        long address,
        long start,
        long end
    ) {
        var aggregates = new Aggregate[MAP_SIZE];

        long offset = start;
        while (offset < end) {
            long nameStart = address + offset;

            byte b = unsafe.getByte(address + offset);

            int hash = 17;
            while (b != DELIMITER) {
                hash = hash * 31 + b;
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

            int index = hash & MAP_MASK;
            Aggregate aggregate;
            int currNameLength = (int) (nameEnd - nameStart);

            while (true) {
                aggregate = aggregates[index];
                if (aggregate == null) break;
                int nameLength = (int) (aggregate.nameEnd - aggregate.nameStart);
                if (nameLength != currNameLength || !unsafeEquals(unsafe, nameStart, aggregate.nameStart, nameLength)) {
                    index = (index + 1) & MAP_MASK;
                } else {
                    break;
                }
            }

            if (aggregate == null) {
                aggregates[index] = new Aggregate(nameStart, nameEnd, (double) temperature/10);
            } else {
                aggregate.add((double) temperature/10);
            }
        }

        for (Aggregate aggregate : aggregates) {
            if (aggregate == null) continue;
            int size = (int) (aggregate.nameEnd - aggregate.nameStart);
            var bytes = new byte[size];
            unsafe.copyMemory(null, aggregate.nameStart, bytes, Unsafe.ARRAY_BYTE_BASE_OFFSET, size);
            aggregate.name = new String(bytes);
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
