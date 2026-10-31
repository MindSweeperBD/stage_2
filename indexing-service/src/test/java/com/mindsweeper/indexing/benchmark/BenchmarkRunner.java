package com.mindsweeper.indexing.benchmark;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class BenchmarkRunner {
    public static void main(String[] args)
            throws Exception {

        Options options = new OptionsBuilder()
                .include(".*IndexingBenchmark.*")
                .resultFormat(ResultFormatType.CSV)
                .result("benchmarks/results/jmh-results.csv")
                .build();

        new Runner(options).run();
    }
}
