package scratch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.enums.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

public class JacksonBenchmark {

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        TransferResponse response = new TransferResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("250.0000"),
                "INR",
                "Benchmark Transfer Description for Verification",
                TransferStatus.COMPLETED,
                Instant.now()
        );

        // Warmup (10,000 iterations for C2 JIT compilation)
        for (int i = 0; i < 10000; i++) {
            mapper.writeValueAsString(response);
        }

        // Measured runs (100,000 iterations)
        int iterations = 100000;
        long[] times = new long[iterations];

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            String json = mapper.writeValueAsString(response);
            long end = System.nanoTime();
            times[i] = end - start;
        }

        Arrays.sort(times);

        double min = times[0] / 1000.0;
        double p50 = times[(int) (iterations * 0.50)] / 1000.0;
        double p90 = times[(int) (iterations * 0.90)] / 1000.0;
        double p95 = times[(int) (iterations * 0.95)] / 1000.0;
        double p99 = times[(int) (iterations * 0.99)] / 1000.0;
        double max = times[iterations - 1] / 1000.0;

        long sum = 0;
        for (long t : times) sum += t;
        double mean = (sum / (double) iterations) / 1000.0;

        System.out.println("=================================================");
        System.out.println("JACKSON SERIALIZATION INDEPENDENT BENCHMARK");
        System.out.println("Iterations: " + iterations);
        System.out.println("Min:  " + String.format("%.3f", min) + " µs");
        System.out.println("p50:  " + String.format("%.3f", p50) + " µs");
        System.out.println("p90:  " + String.format("%.3f", p90) + " µs");
        System.out.println("p95:  " + String.format("%.3f", p95) + " µs");
        System.out.println("p99:  " + String.format("%.3f", p99) + " µs");
        System.out.println("Max:  " + String.format("%.3f", max) + " µs");
        System.out.println("Mean: " + String.format("%.3f", mean) + " µs");
        System.out.println("=================================================");
    }
}
