# YukiObfuscator

A dependency-free Java command-line tool for basic C and C++ source-code obfuscation.

> **AI disclosure:** This repository, including its source code, tests, examples, and documentation, is 100% AI-generated.

YukiObfuscator increases the effort required to read distributed source code by renaming identifiers and source files, removing original comments, and optionally inserting deterministic random comments. It is a source transformer, not a cryptographic protection system.

## Features

- Renames common local variables, global variables, parameters, and data members.
- Renames project-defined `class`, `struct`, `union`, and `enum` types.
- Renames `using` aliases, `typedef` aliases, and template type parameters.
- Keeps constructor, destructor, qualified-name, and member references consistent.
- Renames common C/C++ source and header files while preserving extensions.
- Updates local `#include` directives and common CMake, Make, Meson, and Bazel file references.
- Removes original C/C++ comments by default without changing line counts.
- Optionally inserts deterministic random English block comments.
- Provides normal and high-strength identifier styles.
- Supports exclusion lists, filename-preservation globs, dry runs, and deterministic seeds.
- Produces a JSON reverse mapping unless disabled.
- Requires no Maven, Gradle, or third-party runtime dependencies.

## Requirements

- JDK 17 or newer
- PowerShell 5.1 or PowerShell 7+
- A C++17 compiler is optional and is used by the test script when available

## Build

```powershell
.\build.ps1
```

If local PowerShell policy blocks scripts:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

The executable JAR is generated at `build/yuki-obfuscator.jar`.

## Quick Start

```powershell
java -jar .\build\yuki-obfuscator.jar <input-directory> <output-directory>
```

Basic deterministic run:

```powershell
java -jar .\build\yuki-obfuscator.jar .\examples\simple .\obfuscated --seed 42
```

High-strength run with random comments:

```powershell
java -jar .\build\yuki-obfuscator.jar .\examples\simple .\obfuscated `
  --seed 42 `
  --strength high `
  --random-comments `
  --comment-rate 12 `
  --comment-length 16 `
  --no-map
```

The input and output directories must be different. The output directory must not exist or must be empty.

## Command-Line Options

| Option | Description |
| --- | --- |
| `--seed <number>` | Use a deterministic seed. The default is the current time. |
| `--strength <normal\|high>` | Select normal names or longer visually ambiguous names. |
| `--exclude <a,b,c>` | Preserve a comma-separated list of identifiers. |
| `--exclude-file <path>` | Read preserved identifiers from a UTF-8 file, one per line. Lines beginning with `#` are ignored. |
| `--random-comments` | Enable random English block comments with an 8% insertion rate. |
| `--comment-rate <0-100>` | Set the insertion probability at each existing whitespace token. Zero disables insertion. |
| `--comment-length <4-128>` | Set the number of English characters in each generated comment. The default is 16. |
| `--keep-comments` | Preserve original source comments instead of removing them. |
| `--no-type-rename` | Preserve project-defined type names. |
| `--no-file-rename` | Preserve all source and header filenames. |
| `--keep-file <glob>` | Preserve filenames matching a glob. May be specified more than once. |
| `--no-map` | Do not write the reverse mapping file. Recommended for distributed output. |
| `--dry-run` | Analyze the project and print statistics without creating output files. |
| `--variable-prefix <text>` | Set the generated variable prefix. The default is `v_`. |
| `--type-prefix <text>` | Set the generated type prefix. The default is `t_`. |
| `--file-prefix <text>` | Set the generated filename prefix. The default is `f_`. |
| `--map <relative-path>` | Set the mapping path inside the output directory. |
| `-h`, `--help` | Display command help. |

## Processing Order

For every recognized C/C++ source file, YukiObfuscator performs these operations in order:

1. Lexes the source so strings, character literals, raw strings, and comments remain distinguishable.
2. Replaces original comments with whitespace while preserving line endings, unless `--keep-comments` is set.
3. Collects project-level types and common variable declarations.
4. Renames identifiers consistently across the project.
5. Updates local include paths after planning all filename changes.
6. Inserts optional random block comments only at existing whitespace positions.

The same seed, options, source content, and relative paths produce the same mappings and generated comments.

## Mapping File

By default, `obfuscation-map.json` is written inside the output directory. It records:

- seed and obfuscation settings;
- original-to-generated variable names;
- original-to-generated type names;
- original-to-generated source filenames.

The mapping is useful for debugging but reveals the original names. Use `--no-map` for output intended for distribution.

## Testing

```powershell
.\test.ps1
```

The test suite verifies variable and type renaming, filename and include rewriting, build-file updates, comment removal, random-comment insertion, literal preservation, deterministic output, exclusion rules, dry-run behavior, and output-path validation. When `g++` is available, it also compiles the transformed C++ example.

Clean generated files with:

```powershell
.\clean.ps1
```

## Project Layout

```text
src/main/java/                 Java application source
tools/JarBuilder.java          JAR packager for JDK installations without jar on PATH
examples/simple/               C++ transformation fixture
examples/exclude-names.txt     Example identifier exclusion list
build.ps1                      Build script
test.ps1                       End-to-end test suite
clean.ps1                      Generated-file cleanup script
.github/workflows/ci.yml       GitHub Actions workflow
```

## GitHub Actions

The CI workflow builds with JDK 17, runs the end-to-end tests on Linux, and uploads `yuki-obfuscator.jar` as a workflow artifact after successful pushes and pull requests.

## Limitations

YukiObfuscator uses lexical analysis and conservative declaration heuristics rather than a complete C++ compiler frontend. Complex macros, unusual template constructs, generated APIs, reflection, serialization fields, ABI-sensitive names, and externally required symbols may need exclusions.

Identifier replacement is project-wide by spelling. A project-defined name that is also used by an external API can therefore be changed unintentionally. Preserve such names with `--exclude` or `--exclude-file`, then run the complete project build and test suite against the transformed output.

Use this tool only with source code you are authorized to modify and distribute.
