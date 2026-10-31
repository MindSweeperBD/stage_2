package mindsweeper.benchmark;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

public class BenchmarkRunner {

    public static void main(String[] args) throws Exception {
        Path baseDir = Files.isDirectory(Path.of("search-service"))
                ? Path.of("search-service")
                : Path.of(".");

        Path resultsDir = baseDir.resolve("benchmarks/results");
        Files.createDirectories(resultsDir);

        Path outputCsv = resultsDir.resolve("jmh-results.csv");

        Options options = new OptionsBuilder()
                .include(".*SearchBenchmark.*")
                .resultFormat(ResultFormatType.CSV)
                .result(outputCsv.toString())
                .build();

        new Runner(options).run();
    }
}
