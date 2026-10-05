# JMH reference results

`jmh-reference.json` and `jmh-reference.txt` are produced by:

```bash
# Use JDK 21 (project baseline) when regenerating
export JAVA_HOME=...   # JDK 21
ant jmh-baseline
```

`environment.txt` records the JVM and Ant/JMH versions used for the checked-in reference.

Compare locally after changes:

```bash
ant jmh-results
diff benchmark/baseline/jmh-reference.txt benchmark/results/jmh-*.txt
```

Scores are informational only; CI does not fail on regressions yet.

## Corpora

| Name | Meaning |
|------|---------|
| `FOX` | `cli_4_q11.br` (small, reference brotli CLI) |
| `LITERAL_HEAVY` | ~1 MiB repeating fox text, micula q1-compressed (literal-heavy decode) |
| `REPETITIVE` | 2 MiB highly compressible pattern (decode bench only) |
