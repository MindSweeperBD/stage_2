package com.mindsweeper.benchmark;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.*;

public class BenchmarkRunner {

    public static void main(String[] args)
            throws Exception {

        Options options = new OptionsBuilder()
                .include(".*IngestionBenchmark.*")
                .resultFormat(ResultFormatType.CSV)
                .result("benchmarks/results/jmh-results.csv")
                .build();

        new Runner(options).run();
    }
}
