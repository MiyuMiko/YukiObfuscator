package dev.yuki.obfuscator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class Main {
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            ".c", ".cc", ".cpp", ".cxx", ".h", ".hh", ".hpp", ".hxx", ".inl");
    private static final Set<String> TEXT_BUILD_FILES = Set.of(
            "cmakelists.txt", "makefile", "meson.build", "build", "build.bazel", "workspace");
    private static final Set<String> TEXT_BUILD_EXTENSIONS = Set.of(
            ".cmake", ".mk", ".mak", ".txt", ".bazel");
    private static final Pattern INCLUDE_PATTERN = Pattern.compile(
            "(?m)(^\\s*#\\s*include\\s*[<\"])([^>\"\\r\\n]+)([>\"])");

    private Main() {
    }

    public static void main(String[] args) {
        try {
            Config config = Config.parse(args);
            if (config.help) {
                printHelp();
                return;
            }
            Result result = obfuscate(config);
            System.out.printf(Locale.ROOT,
                    "%s: %d variables, %d types, %d/%d source files renamed, %d files scanned%s%n",
                    config.dryRun ? "Plan" : "Done", result.variableCount, result.typeCount,
                    result.renamedFileCount, result.sourceFileCount, result.totalFileCount,
                    config.dryRun ? "" : ", output: " + config.output);
            if (!config.dryRun && config.writeMap) {
                System.out.println("Mapping: " + config.output.resolve(config.mapFile));
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Run with --help for usage.");
            System.exit(2);
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
            System.exit(1);
        }
    }

    static Result obfuscate(Config config) throws IOException {
        Path input = config.input.toAbsolutePath().normalize();
        Path output = config.output.toAbsolutePath().normalize();
        if (!Files.isDirectory(input)) {
            throw new IllegalArgumentException("input is not a directory: " + input);
        }
        if (input.equals(output) || output.startsWith(input)) {
            throw new IllegalArgumentException("output must be outside the input directory");
        }
        if (!config.dryRun && Files.exists(output) && hasAnyEntry(output)) {
            throw new IllegalArgumentException("output directory must be empty: " + output);
        }
        Path mapTarget = null;
        if (!config.dryRun && config.writeMap) {
            mapTarget = output.resolve(config.mapFile).normalize();
            if (!mapTarget.startsWith(output) || mapTarget.equals(output)) {
                throw new IllegalArgumentException("map file must stay inside the output directory");
            }
        }

        List<Path> relativeFiles;
        try (Stream<Path> stream = Files.walk(input)) {
            relativeFiles = stream.filter(Files::isRegularFile)
                    .map(input::relativize)
                    .sorted()
                    .toList();
        }

        List<Path> sourceFiles = relativeFiles.stream().filter(Main::isSource).toList();
        Set<String> allIdentifiers = new HashSet<>();
        Set<String> candidates = new HashSet<>();
        Map<Path, String> sourceTexts = new HashMap<>();
        Map<Path, List<Token>> sourceTokens = new HashMap<>();
        Set<String> projectTypes = new HashSet<>();
        for (Path relative : sourceFiles) {
            String source = Files.readString(input.resolve(relative), StandardCharsets.UTF_8);
            if (config.stripComments) source = stripComments(source);
            sourceTexts.put(relative, source);
            List<Token> tokens = Lexer.lex(source);
            sourceTokens.put(relative, tokens);
            allIdentifiers.addAll(Analyzer.identifiers(tokens));
            projectTypes.addAll(Analyzer.typeNames(tokens));
        }
        for (Path relative : sourceFiles) {
            candidates.addAll(Analyzer.variableCandidates(sourceTokens.get(relative), projectTypes));
        }
        if (config.excludeFile != null) {
            Path exclusionPath = config.excludeFile.toAbsolutePath().normalize();
            if (!Files.isRegularFile(exclusionPath)) {
                throw new IllegalArgumentException("exclude file does not exist: " + exclusionPath);
            }
            for (String line : Files.readAllLines(exclusionPath, StandardCharsets.UTF_8)) {
                String name = line.strip();
                if (!name.isEmpty() && !name.startsWith("#")) config.excludedNames.add(name);
            }
        }
        candidates.removeAll(config.excludedNames);
        Set<String> typeCandidates = new HashSet<>(projectTypes);
        typeCandidates.removeAll(config.excludedNames);
        if (!config.renameTypes) typeCandidates.clear();
        candidates.removeAll(typeCandidates);

        NameGenerator generator = new NameGenerator(config.seed, config.variablePrefix, config.highStrength);
        Map<String, String> variables = new LinkedHashMap<>();
        Set<String> generatedNames = new HashSet<>();
        for (String name : candidates.stream().sorted().toList()) {
            String generated = generator.identifierName(name, allIdentifiers, generatedNames);
            variables.put(name, generated);
            generatedNames.add(generated);
        }
        NameGenerator typeGenerator = new NameGenerator(config.seed, config.typePrefix, config.highStrength);
        Map<String, String> types = new LinkedHashMap<>();
        for (String name : typeCandidates.stream().sorted().toList()) {
            String generated = typeGenerator.identifierName(name, allIdentifiers, generatedNames);
            types.put(name, generated);
            generatedNames.add(generated);
        }
        Map<String, String> symbols = new HashMap<>(variables);
        symbols.putAll(types);

        Map<Path, Path> fileMap = planFileNames(sourceFiles, config, relativeFiles);
        if (config.dryRun) {
            return new Result(variables.size(), types.size(), fileMap.size(), sourceFiles.size(), relativeFiles.size());
        }
        Path finalMapTarget = mapTarget;
        if (config.writeMap && relativeFiles.stream()
                .map(relative -> output.resolve(fileMap.getOrDefault(relative, relative)).normalize())
                .anyMatch(finalMapTarget::equals)) {
            throw new IllegalArgumentException("map file conflicts with a project file: " + config.mapFile);
        }
        Files.createDirectories(output);
        for (Path relative : relativeFiles) {
            Path targetRelative = fileMap.getOrDefault(relative, relative);
            Path target = output.resolve(targetRelative);
            Files.createDirectories(target.getParent());

            if (isSource(relative)) {
                String rewritten = rewriteIdentifiers(sourceTexts.get(relative), symbols);
                rewritten = rewriteIncludes(rewritten, relative, fileMap);
                if (config.commentRate > 0) {
                    rewritten = insertRandomComments(rewritten, config, relative);
                }
                Files.writeString(target, rewritten, StandardCharsets.UTF_8);
            } else if (isBuildText(relative)) {
                String text = Files.readString(input.resolve(relative), StandardCharsets.UTF_8);
                Files.writeString(target, rewriteBuildPaths(text, fileMap), StandardCharsets.UTF_8);
            } else {
                Files.copy(input.resolve(relative), target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
        }

        if (config.writeMap) {
            String mapping = mappingJson(config, variables, types, fileMap);
            Files.createDirectories(mapTarget.getParent());
            Files.writeString(mapTarget, mapping, StandardCharsets.UTF_8);
        }
        return new Result(variables.size(), types.size(), fileMap.size(), sourceFiles.size(), relativeFiles.size());
    }

    private static boolean hasAnyEntry(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.findAny().isPresent();
        }
    }

    private static Map<Path, Path> planFileNames(
            List<Path> sourceFiles, Config config, List<Path> allFiles) {
        if (!config.renameFiles) {
            return Map.of();
        }
        Set<Path> occupied = new HashSet<>(allFiles);
        Map<Path, Path> result = new LinkedHashMap<>();
        NameGenerator generator = new NameGenerator(config.seed, config.filePrefix, config.highStrength);
        for (Path oldPath : sourceFiles) {
            if (config.keepFile(oldPath)) {
                continue;
            }
            String fileName = oldPath.getFileName().toString();
            String extension = extension(fileName);
            Path parent = oldPath.getParent();
            Path candidate;
            int attempt = 0;
            do {
                String generated = generator.fileName(slash(oldPath), attempt++) + extension;
                candidate = parent == null ? Path.of(generated) : parent.resolve(generated);
            } while (occupied.contains(candidate) || result.containsValue(candidate));
            result.put(oldPath, candidate);
        }
        return result;
    }

    private static String rewriteIdentifiers(String source, Map<String, String> names) {
        if (names.isEmpty()) {
            return source;
        }
        StringBuilder out = new StringBuilder(source.length());
        for (Token token : Lexer.lex(source)) {
            if (token.kind == Kind.IDENTIFIER) {
                out.append(names.getOrDefault(token.text, token.text));
            } else {
                out.append(token.text);
            }
        }
        return out.toString();
    }

    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        for (Token token : Lexer.lex(source)) {
            if (token.kind != Kind.COMMENT) {
                out.append(token.text);
                continue;
            }
            for (int i = 0; i < token.text.length(); i++) {
                char c = token.text.charAt(i);
                out.append(c == '\r' || c == '\n' ? c : ' ');
            }
        }
        return out.toString();
    }

    private static String insertRandomComments(String source, Config config, Path sourcePath) {
        List<Token> tokens = Lexer.lex(source);
        long pathBits = Integer.toUnsignedLong(slash(sourcePath).hashCode());
        SplittableRandom random = new SplittableRandom(config.seed ^ (pathBits << 32) ^ pathBits);
        StringBuilder out = new StringBuilder(source.length() + source.length() / 10);
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            out.append(token.text);
            if (token.kind != Kind.WHITESPACE || i + 1 >= tokens.size()
                    || random.nextInt(100) >= config.commentRate) {
                continue;
            }
            Token previous = i > 0 ? tokens.get(i - 1) : null;
            if (previous != null && previous.kind == Kind.SYMBOL && previous.text.equals("\\")
                    && (token.text.contains("\n") || token.text.contains("\r"))) {
                continue;
            }
            out.append("/*");
            for (int j = 0; j < config.commentLength; j++) {
                out.append(NameGenerator.COMMENT_ALPHABET[
                        random.nextInt(NameGenerator.COMMENT_ALPHABET.length)]);
            }
            out.append("*/");
        }
        return out.toString();
    }

    private static String rewriteIncludes(String source, Path sourcePath, Map<Path, Path> files) {
        if (files.isEmpty()) {
            return source;
        }
        Matcher matcher = INCLUDE_PATTERN.matcher(source);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String oldInclude = matcher.group(2).replace('\\', '/');
            String replacement = findIncludeReplacement(oldInclude, sourcePath, files);
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    matcher.group(1) + replacement + matcher.group(3)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String findIncludeReplacement(
            String include, Path sourcePath, Map<Path, Path> files) {
        Path sourceParent = sourcePath.getParent() == null ? Path.of("") : sourcePath.getParent();
        Path resolved = sourceParent.resolve(include).normalize();
        Path direct = files.get(resolved);
        if (direct != null) {
            Path newSource = files.getOrDefault(sourcePath, sourcePath);
            Path newParent = newSource.getParent() == null ? Path.of("") : newSource.getParent();
            return slash(newParent.relativize(direct));
        }

        List<Map.Entry<Path, Path>> suffixMatches = files.entrySet().stream()
                .filter(entry -> {
                    String old = slash(entry.getKey());
                    return old.equals(include) || old.endsWith("/" + include);
                })
                .toList();
        if (suffixMatches.size() == 1) {
            Path includePath = Path.of(include);
            Path includeParent = includePath.getParent();
            String newName = suffixMatches.get(0).getValue().getFileName().toString();
            return includeParent == null ? newName : slash(includeParent.resolve(newName));
        }
        return include;
    }

    private static String rewriteBuildPaths(String text, Map<Path, Path> files) {
        List<Map.Entry<Path, Path>> entries = new ArrayList<>(files.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<Path, Path> e) -> e.getKey().toString().length())
                .reversed());
        String result = text;
        Map<String, Integer> basenameCounts = new HashMap<>();
        for (Path old : files.keySet()) {
            basenameCounts.merge(old.getFileName().toString(), 1, Integer::sum);
        }
        for (Map.Entry<Path, Path> entry : entries) {
            String oldSlash = slash(entry.getKey());
            String newSlash = slash(entry.getValue());
            result = result.replace(oldSlash, newSlash)
                    .replace(oldSlash.replace('/', '\\'), newSlash.replace('/', '\\'));
            String oldName = entry.getKey().getFileName().toString();
            if (basenameCounts.get(oldName) == 1) {
                result = result.replace(oldName, entry.getValue().getFileName().toString());
            }
        }
        return result;
    }

    private static String mappingJson(
            Config config, Map<String, String> variables, Map<String, String> types, Map<Path, Path> files) {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"seed\": ").append(config.seed)
                .append(",\n  \"strength\": \"").append(config.highStrength ? "high" : "normal")
                .append("\",\n  \"randomComments\": ").append(config.commentRate > 0)
                .append(",\n  \"originalCommentsRemoved\": ").append(config.stripComments)
                .append(",\n  \"commentRate\": ").append(config.commentRate)
                .append(",\n  \"commentLength\": ").append(config.commentLength)
                .append(",\n  \"variables\": {");
        appendStringMap(out, variables);
        out.append("\n  },\n  \"types\": {");
        appendStringMap(out, types);
        out.append("\n  },\n  \"files\": {");
        Map<String, String> stringFiles = new LinkedHashMap<>();
        files.forEach((oldPath, newPath) -> stringFiles.put(slash(oldPath), slash(newPath)));
        appendStringMap(out, stringFiles);
        out.append("\n  }\n}\n");
        return out.toString();
    }

    private static void appendStringMap(StringBuilder out, Map<String, String> map) {
        boolean first = true;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            out.append(first ? "\n" : ",\n");
            first = false;
            out.append("    \"").append(jsonEscape(entry.getKey())).append("\": \"")
                    .append(jsonEscape(entry.getValue())).append('"');
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static boolean isSource(Path path) {
        return SOURCE_EXTENSIONS.contains(extension(path.getFileName().toString()));
    }

    private static boolean isBuildText(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return TEXT_BUILD_FILES.contains(name) || TEXT_BUILD_EXTENSIONS.contains(extension(name));
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void printHelp() {
        System.out.println("""
                YukiObfuscator - simple C/C++ source obfuscator

                Usage:
                  java -jar yuki-obfuscator.jar <input-dir> <output-dir> [options]

                Options:
                  --seed <number>           Deterministic random seed (default: current time)
                  --exclude <a,b,c>         Variable names that must not be changed
                  --exclude-file <path>     Read excluded names from a UTF-8 text file
                  --strength <normal|high>  Name obfuscation strength (default: normal)
                  --random-comments         Insert random English block comments
                  --comment-rate <0-100>    Comment chance per whitespace (enables comments)
                  --comment-length <4-128>  Random comment text length (default: 16)
                  --keep-comments           Keep original source comments
                  --no-type-rename          Keep class/struct/enum/type alias names
                  --no-file-rename          Keep source/header file names
                  --keep-file <glob>        Keep matching source file names (repeatable)
                  --no-map                  Do not write the reverse mapping file
                  --dry-run                 Analyze and print counts without writing files
                  --variable-prefix <text>  Generated variable prefix (default: v_)
                  --type-prefix <text>      Generated type prefix (default: t_)
                  --file-prefix <text>      Generated file prefix (default: f_)
                  --map <relative-path>      Mapping file in output (default: obfuscation-map.json)
                  -h, --help                 Show this help
                """);
    }

    static final class Config {
        Path input;
        Path output;
        long seed = System.currentTimeMillis();
        boolean renameFiles = true;
        boolean renameTypes = true;
        boolean writeMap = true;
        boolean dryRun;
        boolean highStrength;
        boolean stripComments = true;
        boolean help;
        int commentRate;
        int commentLength = 16;
        String variablePrefix = "v_";
        String typePrefix = "t_";
        String filePrefix = "f_";
        Path mapFile = Path.of("obfuscation-map.json");
        Path excludeFile;
        Set<String> excludedNames = new HashSet<>();
        List<String> keptFileGlobs = new ArrayList<>();

        static Config parse(String[] args) {
            Config config = new Config();
            List<String> positional = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "-h", "--help" -> config.help = true;
                    case "--no-file-rename" -> config.renameFiles = false;
                    case "--no-type-rename" -> config.renameTypes = false;
                    case "--no-map" -> config.writeMap = false;
                    case "--dry-run" -> config.dryRun = true;
                    case "--keep-comments" -> config.stripComments = false;
                    case "--random-comments" -> {
                        if (config.commentRate == 0) config.commentRate = 8;
                    }
                    case "--strength" -> config.highStrength = parseStrength(requireValue(args, ++i, arg));
                    case "--comment-rate" -> config.commentRate = parseRange(
                            requireValue(args, ++i, arg), arg, 0, 100);
                    case "--comment-length" -> config.commentLength = parseRange(
                            requireValue(args, ++i, arg), arg, 4, 128);
                    case "--seed" -> config.seed = Long.parseLong(requireValue(args, ++i, arg));
                    case "--variable-prefix" -> config.variablePrefix = validPrefix(requireValue(args, ++i, arg));
                    case "--type-prefix" -> config.typePrefix = validPrefix(requireValue(args, ++i, arg));
                    case "--file-prefix" -> config.filePrefix = validPrefix(requireValue(args, ++i, arg));
                    case "--map" -> config.mapFile = Path.of(requireValue(args, ++i, arg));
                    case "--exclude-file" -> config.excludeFile = Path.of(requireValue(args, ++i, arg));
                    case "--keep-file" -> config.keptFileGlobs.add(requireValue(args, ++i, arg));
                    case "--exclude" -> {
                        for (String name : requireValue(args, ++i, arg).split(",")) {
                            if (!name.isBlank()) config.excludedNames.add(name.trim());
                        }
                    }
                    default -> {
                        if (arg.startsWith("-")) throw new IllegalArgumentException("unknown option: " + arg);
                        positional.add(arg);
                    }
                }
            }
            if (!config.help) {
                if (positional.size() != 2) {
                    throw new IllegalArgumentException("input-dir and output-dir are required");
                }
                config.input = Path.of(positional.get(0));
                config.output = Path.of(positional.get(1));
            }
            return config;
        }

        boolean keepFile(Path path) {
            for (String glob : keptFileGlobs) {
                String nativeGlob = glob.replace('/', java.io.File.separatorChar)
                        .replace('\\', java.io.File.separatorChar);
                var matcher = FileSystems.getDefault().getPathMatcher("glob:" + nativeGlob);
                if (matcher.matches(path) || matcher.matches(path.getFileName())) return true;
            }
            return false;
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) throw new IllegalArgumentException("missing value for " + option);
            return args[index];
        }

        private static String validPrefix(String value) {
            if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("invalid identifier prefix: " + value);
            }
            return value;
        }

        private static boolean parseStrength(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "normal" -> false;
                case "high" -> true;
                default -> throw new IllegalArgumentException(
                        "strength must be normal or high: " + value);
            };
        }

        private static int parseRange(String value, String option, int min, int max) {
            final int number;
            try {
                number = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " must be a number: " + value);
            }
            if (number < min || number > max) {
                throw new IllegalArgumentException(option + " must be between " + min + " and " + max);
            }
            return number;
        }
    }

    record Result(int variableCount, int typeCount, int renamedFileCount, int sourceFileCount, int totalFileCount) {
    }

    enum Kind { IDENTIFIER, NUMBER, STRING, COMMENT, WHITESPACE, SYMBOL }

    record Token(Kind kind, String text) {
    }

    static final class Lexer {
        private Lexer() {
        }

        static List<Token> lex(String source) {
            List<Token> tokens = new ArrayList<>();
            int i = 0;
            while (i < source.length()) {
                char c = source.charAt(i);
                int start = i;
                if (Character.isWhitespace(c)) {
                    while (++i < source.length() && Character.isWhitespace(source.charAt(i))) { }
                    tokens.add(new Token(Kind.WHITESPACE, source.substring(start, i)));
                } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                    i += 2;
                    while (i < source.length() && source.charAt(i) != '\n') i++;
                    tokens.add(new Token(Kind.COMMENT, source.substring(start, i)));
                } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                    i += 2;
                    while (i + 1 < source.length()
                            && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) i++;
                    i = Math.min(source.length(), i + 2);
                    tokens.add(new Token(Kind.COMMENT, source.substring(start, i)));
                } else if (isRawStringStart(source, i)) {
                    i = rawStringEnd(source, i);
                    tokens.add(new Token(Kind.STRING, source.substring(start, i)));
                } else if (c == '"' || c == '\'') {
                    char quote = c;
                    i++;
                    while (i < source.length()) {
                        if (source.charAt(i) == '\\') {
                            i = Math.min(source.length(), i + 2);
                        } else if (source.charAt(i++) == quote) {
                            break;
                        }
                    }
                    tokens.add(new Token(Kind.STRING, source.substring(start, i)));
                } else if (isIdentifierStart(c)) {
                    while (++i < source.length() && isIdentifierPart(source.charAt(i))) { }
                    tokens.add(new Token(Kind.IDENTIFIER, source.substring(start, i)));
                } else if (Character.isDigit(c)) {
                    while (++i < source.length()) {
                        char n = source.charAt(i);
                        if (!(Character.isLetterOrDigit(n) || n == '_' || n == '.' || n == '\'')) break;
                    }
                    tokens.add(new Token(Kind.NUMBER, source.substring(start, i)));
                } else {
                    String symbol = i + 1 < source.length() ? source.substring(i, i + 2) : "";
                    if (Set.of("::", "->", "&&", "||", "<<", ">>", "==", "!=", "<=", ">=", "++", "--",
                            "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "##").contains(symbol)) {
                        i += 2;
                    } else {
                        i++;
                    }
                    tokens.add(new Token(Kind.SYMBOL, source.substring(start, i)));
                }
            }
            return tokens;
        }

        private static boolean isIdentifierStart(char c) {
            return c == '_' || Character.isLetter(c);
        }

        private static boolean isIdentifierPart(char c) {
            return c == '_' || Character.isLetterOrDigit(c);
        }

        private static boolean isRawStringStart(String source, int index) {
            return source.charAt(index) == 'R' && index + 1 < source.length() && source.charAt(index + 1) == '"';
        }

        private static int rawStringEnd(String source, int start) {
            int open = source.indexOf('(', start + 2);
            if (open < 0 || open - (start + 2) > 16) return start + 1;
            String delimiter = source.substring(start + 2, open);
            int close = source.indexOf(")" + delimiter + "\"", open + 1);
            return close < 0 ? source.length() : close + delimiter.length() + 2;
        }
    }

    static final class Analyzer {
        private static final Set<String> KEYWORDS = Set.of(
                "alignas", "alignof", "and", "and_eq", "asm", "auto", "bitand", "bitor", "bool", "break",
                "case", "catch", "char", "char8_t", "char16_t", "char32_t", "class", "compl", "concept",
                "const", "consteval", "constexpr", "constinit", "const_cast", "continue", "co_await", "co_return",
                "co_yield", "decltype", "default", "delete", "do", "double", "dynamic_cast", "else", "enum",
                "explicit", "export", "extern", "false", "float", "for", "friend", "goto", "if", "inline", "int",
                "long", "mutable", "namespace", "new", "noexcept", "not", "not_eq", "nullptr", "operator", "or",
                "or_eq", "private", "protected", "public", "register", "reinterpret_cast", "requires", "return",
                "short", "signed", "sizeof", "static", "static_assert", "static_cast", "struct", "switch", "template",
                "this", "thread_local", "throw", "true", "try", "typedef", "typeid", "typename", "union", "unsigned",
                "using", "virtual", "void", "volatile", "wchar_t", "while", "xor", "xor_eq");
        private static final Set<String> TYPE_WORDS = Set.of(
                "auto", "bool", "char", "char8_t", "char16_t", "char32_t", "double", "float", "int", "long",
                "short", "signed", "unsigned", "void", "wchar_t", "size_t", "ssize_t", "string");
        private static final Set<String> QUALIFIERS = Set.of(
                "const", "constexpr", "constinit", "extern", "inline", "mutable", "register", "static",
                "thread_local", "volatile", "typename", "signed", "unsigned", "long", "short");
        private static final Set<String> CONTROL_WORDS = Set.of(
                "return", "if", "else", "for", "while", "switch", "case", "throw", "new", "delete", "sizeof",
                "alignof", "decltype", "static_assert", "using", "typedef", "namespace", "class", "struct",
                "union", "enum", "concept");
        private static final Set<String> ALLOWED_PREFIX_SYMBOLS = Set.of(
                "::", "*", "&", "&&", "<", ">", ">>", ",", "[", "]", "...");
        private static final Set<String> DECLARATOR_ENDS = Set.of(
                "=", ";", ",", ")", "[", "]", "{", "}", ":");
        private static final Set<String> TYPE_DECLARATION_WORDS = Set.of("class", "struct", "union", "enum");
        private static final Set<String> TYPE_DECLARATION_IGNORED = Set.of(
                "class", "struct", "union", "enum", "final", "alignas", "__declspec");

        private Analyzer() {
        }

        static Set<String> identifiers(List<Token> tokens) {
            Set<String> result = new HashSet<>();
            for (Token token : tokens) {
                if (token.kind == Kind.IDENTIFIER) result.add(token.text);
            }
            return result;
        }

        static Set<String> typeNames(List<Token> tokens) {
            Set<String> result = new HashSet<>();
            learnTypes(significantCode(tokens), result);
            result.removeAll(KEYWORDS);
            result.removeAll(TYPE_DECLARATION_IGNORED);
            return result;
        }

        static Set<String> variableCandidates(List<Token> tokens, Set<String> projectTypes) {
            List<Token> significant = significantCode(tokens);
            Set<String> knownTypes = new HashSet<>(TYPE_WORDS);
            knownTypes.addAll(projectTypes);
            learnTypes(significant, knownTypes);
            Set<String> result = new HashSet<>();
            for (int i = 0; i < significant.size(); i++) {
                Token token = significant.get(i);
                if (token.kind != Kind.IDENTIFIER || KEYWORDS.contains(token.text) || token.text.equals("main")) continue;
                String next = i + 1 < significant.size() ? significant.get(i + 1).text : ";";
                if (next.equals("(") || next.equals("::") || !DECLARATOR_ENDS.contains(next)) continue;
                if (looksLikeDeclaration(significant, i, knownTypes)
                        || looksLikeFunctionPointer(significant, i, knownTypes)) {
                    result.add(token.text);
                    addSiblingDeclarators(significant, i, result);
                }
            }
            return result;
        }

        private static void addSiblingDeclarators(List<Token> tokens, int declaration, Set<String> result) {
            int parentheses = 0;
            int brackets = 0;
            int braces = 0;
            int angles = 0;
            for (int i = declaration + 1; i < tokens.size(); i++) {
                String text = tokens.get(i).text;
                switch (text) {
                    case "(" -> parentheses++;
                    case ")" -> {
                        if (parentheses == 0) return;
                        parentheses--;
                    }
                    case "[" -> brackets++;
                    case "]" -> brackets = Math.max(0, brackets - 1);
                    case "{" -> braces++;
                    case "}" -> {
                        if (braces == 0) return;
                        braces--;
                    }
                    case "<" -> angles++;
                    case ">" -> angles = Math.max(0, angles - 1);
                    case ">>" -> angles = Math.max(0, angles - 2);
                    case ";" -> {
                        if (parentheses == 0 && brackets == 0 && braces == 0 && angles == 0) return;
                    }
                    case "," -> {
                        if (parentheses == 0 && brackets == 0 && braces == 0 && angles == 0) {
                            int candidate = nextDeclarator(tokens, i + 1);
                            if (candidate >= 0) result.add(tokens.get(candidate).text);
                        }
                    }
                    default -> { }
                }
            }
        }

        private static int nextDeclarator(List<Token> tokens, int start) {
            for (int i = start; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                if (token.text.equals(";") || token.text.equals(")")) return -1;
                if (token.kind == Kind.IDENTIFIER && !QUALIFIERS.contains(token.text)) {
                    String next = i + 1 < tokens.size() ? tokens.get(i + 1).text : ";";
                    return !KEYWORDS.contains(token.text) && !next.equals("(")
                            && DECLARATOR_ENDS.contains(next) ? i : -1;
                }
                if (token.kind == Kind.SYMBOL && !Set.of("*", "&", "&&").contains(token.text)) return -1;
            }
            return -1;
        }

        private static List<Token> significantCode(List<Token> tokens) {
            List<Token> result = new ArrayList<>();
            boolean directive = false;
            boolean directiveContinued = false;
            for (Token token : tokens) {
                if (directive) {
                    if (token.kind == Kind.SYMBOL) {
                        directiveContinued = token.text.equals("\\");
                    } else if (token.kind != Kind.WHITESPACE && token.kind != Kind.COMMENT) {
                        directiveContinued = false;
                    }
                    if ((token.kind == Kind.WHITESPACE || token.kind == Kind.COMMENT)
                            && (token.text.contains("\n") || token.text.contains("\r"))) {
                        if (!directiveContinued) {
                            directive = false;
                            result.add(new Token(Kind.SYMBOL, ";"));
                        }
                        directiveContinued = false;
                    }
                    continue;
                }
                if (token.kind == Kind.SYMBOL && token.text.equals("#")) {
                    directive = true;
                } else if (token.kind != Kind.WHITESPACE && token.kind != Kind.COMMENT) {
                    result.add(token);
                }
            }
            return result;
        }

        private static void learnTypes(List<Token> tokens, Set<String> types) {
            for (int i = 0; i < tokens.size(); i++) {
                String current = tokens.get(i).text;
                if (TYPE_DECLARATION_WORDS.contains(current)) {
                    String declaredName = null;
                    for (int j = i + 1; j < tokens.size(); j++) {
                        Token token = tokens.get(j);
                        if (Set.of("{", ":", ";", ">", ",", "=").contains(token.text)) break;
                        if (token.kind == Kind.IDENTIFIER && !KEYWORDS.contains(token.text)
                                && !TYPE_DECLARATION_IGNORED.contains(token.text)) {
                            declaredName = token.text;
                        }
                    }
                    if (declaredName != null) types.add(declaredName);
                }
                if (current.equals("using") && i + 2 < tokens.size()) {
                    Token next = tokens.get(i + 1);
                    if (next.kind == Kind.IDENTIFIER && !KEYWORDS.contains(next.text)
                        && tokens.get(i + 2).text.equals("=")) {
                        types.add(next.text);
                    }
                }
            }
            for (int i = 0; i < tokens.size(); i++) {
                if (!tokens.get(i).text.equals("typedef")) continue;
                String lastIdentifier = null;
                int braces = 0;
                int parentheses = 0;
                int brackets = 0;
                for (int j = i + 1; j < tokens.size(); j++) {
                    Token token = tokens.get(j);
                    if (token.text.equals(";") && braces == 0 && parentheses == 0 && brackets == 0) break;
                    switch (token.text) {
                        case "{" -> braces++;
                        case "}" -> braces = Math.max(0, braces - 1);
                        case "(" -> parentheses++;
                        case ")" -> parentheses = Math.max(0, parentheses - 1);
                        case "[" -> brackets++;
                        case "]" -> brackets = Math.max(0, brackets - 1);
                        default -> { }
                    }
                    if (token.kind == Kind.IDENTIFIER && !KEYWORDS.contains(token.text)) {
                        lastIdentifier = token.text;
                    }
                }
                if (lastIdentifier != null) types.add(lastIdentifier);
            }
        }

        private static boolean looksLikeDeclaration(List<Token> tokens, int candidate, Set<String> knownTypes) {
            int start = candidate - 1;
            while (start >= 0 && !isBoundary(tokens.get(start).text)) start--;
            start++;
            if (start >= candidate) return false;

            boolean hasType = false;
            boolean hasNonQualifierIdentifier = false;
            boolean qualified = false;
            boolean unknownTypeShape = true;
            String firstIdentifier = null;
            int angleDepth = 0;
            for (int i = start; i < candidate; i++) {
                Token token = tokens.get(i);
                String text = token.text;
                if (token.kind == Kind.STRING) return false;
                if (token.kind == Kind.NUMBER && angleDepth == 0) return false;
                if (token.kind == Kind.SYMBOL) {
                    if (text.equals("<")) {
                        angleDepth++;
                        continue;
                    }
                    if (text.equals(">") || text.equals(">>")) {
                        angleDepth -= text.equals(">>") ? 2 : 1;
                        if (angleDepth < 0) return false;
                        continue;
                    }
                    if (angleDepth > 0) continue;
                    if (!ALLOWED_PREFIX_SYMBOLS.contains(text)) return false;
                    if (text.equals("::")) qualified = true;
                    if (!Set.of("::", "*", "&", "&&").contains(text)) unknownTypeShape = false;
                }
                if (token.kind == Kind.IDENTIFIER) {
                    if (CONTROL_WORDS.contains(text)) return false;
                    if (knownTypes.contains(text)) hasType = true;
                    if (!QUALIFIERS.contains(text)) {
                        hasNonQualifierIdentifier = true;
                        if (firstIdentifier == null) firstIdentifier = text;
                    }
                }
            }
            if (angleDepth != 0) return false;
            if (!hasType) {
                // Unknown external types are accepted when their spelling is conventionally type-like.
                hasType = unknownTypeShape && hasNonQualifierIdentifier && firstIdentifier != null
                        && (qualified || Character.isUpperCase(firstIdentifier.charAt(0)));
            }
            return hasType;
        }

        private static boolean looksLikeFunctionPointer(
                List<Token> tokens, int candidate, Set<String> knownTypes) {
            if (candidate < 3 || !tokens.get(candidate - 1).text.equals("*")
                    || !tokens.get(candidate - 2).text.equals("(")) return false;
            int start = candidate - 3;
            while (start >= 0 && !isBoundary(tokens.get(start).text)) start--;
            for (int i = start + 1; i < candidate - 2; i++) {
                Token token = tokens.get(i);
                if (token.kind == Kind.IDENTIFIER && knownTypes.contains(token.text)) return true;
            }
            return false;
        }

        private static boolean isBoundary(String text) {
            return Set.of(";", "{", "}", "(", ")", "=", ":").contains(text);
        }
    }

    static final class NameGenerator {
        private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
        private static final char[] CONFUSING_ALPHABET = "IlOo01".toCharArray();
        static final char[] COMMENT_ALPHABET = ALPHABET;
        private final long seed;
        private final String prefix;
        private final boolean highStrength;

        NameGenerator(long seed, String prefix, boolean highStrength) {
            this.seed = seed;
            this.prefix = prefix;
            this.highStrength = highStrength;
        }

        String identifierName(String original, Set<String> sourceNames, java.util.Collection<String> generated) {
            int attempt = 0;
            while (true) {
                String name = prefix + encoded(original, attempt++, highStrength ? 24 : 12);
                if (!sourceNames.contains(name) && !generated.contains(name) && !Analyzer.KEYWORDS.contains(name)) {
                    return name;
                }
            }
        }

        String fileName(String path, int attempt) {
            return prefix + encoded(path, attempt, highStrength ? 20 : 14);
        }

        private String encoded(String value, int attempt, int length) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] bytes = digest.digest((seed + ":" + value + ":" + attempt)
                        .getBytes(StandardCharsets.UTF_8));
                StringBuilder result = new StringBuilder(length);
                char[] alphabet = highStrength ? CONFUSING_ALPHABET : ALPHABET;
                for (int i = 0; i < length; i++) {
                    result.append(alphabet[Byte.toUnsignedInt(bytes[i]) % alphabet.length]);
                }
                return result.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is unavailable", e);
            }
        }
    }
}
