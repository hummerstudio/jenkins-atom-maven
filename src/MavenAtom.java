import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maven as an atom: runs `mvn <goals> <options>` in the job workspace and reports the exit code.
 *
 * <p>Everything the plugin did around the call — finding the tool, streaming the output into the build log, failing the
 * build on a non-zero code — is here in about two hundred lines and needs nothing from Jenkins.
 */
public class MavenAtom {

    private static final String TOOL = "mvn";
    private static final String TOOL_HOME = "MAVEN_HOME";

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(System.getenv("atom_data_dir"));
        String inputName = or(System.getenv("atom_data_input"), "input.json");
        String outputName = or(System.getenv("atom_data_output"), "output.json");
        String workspace = or(System.getenv("atom_workspace"), System.getProperty("user.dir"));
        String input = Files.readString(dir.resolve(inputName), StandardCharsets.UTF_8);
        Map<String, String> env = System.getenv();

        String goals = expand(text(input, "goals", "verify"), env);
        String options = expand(text(input, "options", ""), env);
        String home = text(input, "home", "");
        boolean failOnError = Boolean.parseBoolean(text(input, "failOnError", "true"));

        String executable = binary(home, env);
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(split(goals));
        command.addAll(split(options));

        long started = System.currentTimeMillis();
        ProcessBuilder builder = new ProcessBuilder(command).directory(Path.of(workspace).toFile());
        builder.inheritIO();
        System.out.println("[mvn] " + String.join(" ", command) + "  (in " + workspace + ")");
        int exit;
        try {
            exit = builder.start().waitFor();
        } catch (IOException e) {
            fail(dir, outputName, TOOL + " could not be started: " + e.getMessage(), 40001);
            return;
        }
        long duration = System.currentTimeMillis() - started;

        boolean ok = exit == 0;
        System.out.println("[mvn] exit=" + exit + " in " + duration + " ms");
        String json = "{\"status\":\"" + (ok || !failOnError ? "success" : "failure") + "\","
                + (ok || !failOnError ? "" : "\"errorType\":1,\"errorCode\":40002,\"message\":\"" + TOOL
                        + " exited with " + exit + "\",")
                + "\"data\":{"
                + "\"exitCode\":{\"type\":\"string\",\"value\":\"" + exit + "\"},"
                + "\"durationMs\":{\"type\":\"string\",\"value\":\"" + duration + "\"}}}";
        Files.writeString(dir.resolve(outputName), json, StandardCharsets.UTF_8);
        if (!ok && failOnError) {
            System.exit(1);
        }
    }

    /** {@code <home>/bin/<tool>} when the job sets a home, else the tool as found on PATH. */
    private static String binary(String home, Map<String, String> env) {
        String configured = home == null ? "" : home.trim();
        if (configured.isEmpty()) {
            configured = env.getOrDefault(TOOL_HOME, "");
        }
        if (configured.isBlank()) {
            return TOOL;
        }
        Path candidate = Path.of(configured).resolve("bin").resolve(TOOL);
        return Files.isExecutable(candidate) ? candidate.toString() : TOOL;
    }

    /** Splits on whitespace, honouring double quotes so an option may carry a space. */
    private static List<String> split(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : text.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(c) && !quoted) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static void fail(Path dir, String outputName, String message, int code) throws Exception {
        Files.writeString(
                dir.resolve(outputName),
                "{\"status\":\"error\",\"errorType\":2,\"errorCode\":" + code + ",\"message\":\""
                        + escape(message) + "\"}",
                StandardCharsets.UTF_8);
        System.out.println("[mvn] ERROR " + message);
        System.exit(1);
    }

    private static String expand(String template, Map<String, String> env) {
        Matcher matcher = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}").matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = env.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String text(String json, String key, String fallback) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        return matcher.find() ? unescape(matcher.group(1)) : fallback;
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    default -> out.append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
