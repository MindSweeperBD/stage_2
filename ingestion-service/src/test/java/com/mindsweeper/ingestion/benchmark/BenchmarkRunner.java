package com.mindsweeper.ingestion.benchmark;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.*;

public class BenchmarkRunner {

    public static void main(String[] args)
            throws Exception {

                
        java.nio.file.Files.createDirectories(
        java.nio.file.Path.of("benchmarks/results")
        );

        Options options = new OptionsBuilder()
                .include(".*IngestionBenchmark.*")
                .resultFormat(ResultFormatType.CSV)
                .result("benchmarks/results/jmh-results.csv")
                .build();

        new Runner(options).run();
    }
}
