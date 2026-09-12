# Contributing to Micula

Thank you for your interest in contributing to Micula. This document covers
how to test and submit changes, as well as the coding standards we follow.

## Testing

Run the unit tests:

```bash
ant test
```

## Submitting Changes

1. Fork the repository and create a branch for your changes
2. Make your changes, following the [Coding Standards](#micula-coding-standards) below
3. Ensure `ant test` passes
4. Submit a pull request with a clear description of the change
5. Address any review feedback

---

# Micula Coding Standards

This document defines the coding standards and conventions for the Micula
project. All contributions should adhere to these guidelines.

## Java Version Compatibility

**Micula requires Java 21 (LTS) as the minimum baseline.**

This is enforced at compile time via the `--release` flag in `build.xml`.

**The following language features are prohibited by project style policy,**
even though they are available on this baseline. Micula uses a traditional
procedural style for clarity and maintainability:

- `var` keyword
- Switch expressions
- Text blocks
- Records
- Pattern matching
- Sealed classes
- Virtual threads
- Lambda expressions
- Method references
- Streams API (`java.util.stream`)
- `Optional<T>`
- `CompletableFuture` and `Future`

### Default Methods in Interfaces

Default methods are permitted for **backward-compatible interface evolution**:
adding a new, optional callback or accessor to an existing public interface
without breaking every class that already implements it. A default method used
this way should have a body that is either empty or a sensible, self-contained
fallback — not business logic that belongs in a concrete class.

Default methods are **not** a substitute for an abstract class or a proper
base implementation, and should not be used to share non-trivial logic between
implementers — use composition or a shared helper class for that instead.

## No java.io Streams

Micula is NIO-first. Do **not** introduce `java.io.InputStream` or
`java.io.OutputStream` APIs or adapters. Use `ByteBuffer`,
`ReadableByteChannel`, `WritableByteChannel`, and `FileChannel` only.

## File Headers

All source files must include a proper file header containing:

- Filename
- Copyright owner and date (created/modified year)
- Copyright notice with license reference

Example:

```java
/*
 * ExampleClass.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of micula, a Brotli codec for Java.
 * For more information please visit https://github.com/cpkb-bluezoo/micula/
 *
 * micula is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * micula is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with micula.  If not, see <http://www.gnu.org/licenses/>.
 */
```

## Documentation

- All Java classes must have proper Javadoc with `@author` tag
- Document the intent and purpose, not the obvious mechanics
- Don't write comments that simply restate what the code does

## Control Flow

### Conditional Blocks

All conditional blocks must be properly delimited with curly braces and
indented, even for single-line blocks. No short-form statements on the same
line after `if`.

## Imports

- Use proper import statements for all classes
- No fully qualified class names in code unless there is a genuine name clash
- Organize imports logically (`java.*`, then project packages)

## Annotations

- Only `@Override`, `@Deprecated`, and `@SuppressWarnings` are permitted in
  main source code
- `@SuppressWarnings` must be as narrowly scoped as possible and should carry
  a comment explaining why the warning is unavoidable
- Other annotations may be used in JUnit tests

## Language Features to Avoid

### No Lambdas

Use traditional anonymous classes or explicit method implementations instead.

### No Functional Paradigm

Use clear, traditional procedural code. Avoid streams, functional interfaces,
and method references.

### No Method Chaining

Avoid chaining method calls (except for builders, used sparingly). Write each
operation as a separate statement for clarity.

### No Inline Function Calls as Parameters

Avoid calling functions inline as parameters. Assign to variables first for
clarity.

**Exception:** The `++` operator may be used inline.

### No Future/Promise

Avoid `Future`, `CompletableFuture`, and similar constructs. Use traditional
callback patterns instead.

### No Regular Expressions

Avoid `java.util.regex` patterns. Use traditional string parsing methods
instead.

## Error Messages

Codec parse/encode errors are for developers and use hardcoded English strings
(no L10N). They represent malformed data or API misuse.

## Summary

The goal of these standards is to produce code that is:

- **Clear**: Easy to read and understand at a glance
- **Predictable**: Follows consistent patterns throughout
- **Maintainable**: Easy to modify without introducing bugs
- **Traditional**: Uses well-understood Java idioms
- **Compatible**: Runs on Java 21 and later without modification

When in doubt, prefer clarity over cleverness.
