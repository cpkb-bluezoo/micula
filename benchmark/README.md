# Micula JMH benchmarks

Throughput benchmarks using [JMH](https://github.com/openjdk/jmh). Built and run **only via Ant**.

## Prerequisites

JDK 21+ and network access the first time JMH libraries are downloaded (`ant jmh-libs`).

## Commands

From the repository root:

| Target | Purpose |
|--------|---------|
| `ant jmh-build` | Compile benchmarks only |
| `ant jmh-smoke` | Minimal run (CI): `MiculaDecodeBench` only |
| `ant jmh` | All benchmarks in `org.bluezoo.micula.bench` (default `-wi 2 -i 3`) |
| `ant jmh-results` | Full run with timestamped JSON under `benchmark/results/` |
| `ant jmh-baseline` | Full run; refresh `benchmark/baseline/jmh-reference.json` |

Custom JMH options:

```bash
ant jmh -Djmh.args='MiculaEncodeBench -wi 3 -i 5'
```

## Benchmark classes

| Class | What it measures |
|-------|------------------|
| `MiculaDecodeBench` | Decode with reused decoder (`FOX` vs `REPETITIVE` corpus) |
| `MiculaDecodeChunkedBench` | Chunked `receive()` (1 / 64 / 8192) on `FOX` or `LITERAL_HEAVY` |
| `MiculaDecodeContentEmitBench` | `contentEmitThreshold` 1 vs 4096 on `FOX` or `LITERAL_HEAVY` |
| `MiculaEncodeBench` | Encode fox text, qualities 1–10 |

Corpus files live under `test/junit/src/org/bluezoo/micula/`.

JMH JARs are cached in `benchmark/lib/` (not committed). CI runs `ant jmh-smoke` after unit tests.
