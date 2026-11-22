package com.jenkinschina.atom.maven;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maven as an atom, written against the SDK, with two verbs:
 *
 * <ul>
 *   <li>{@code action=build} (the default, and what this atom was): run {@code mvn <goals> <options>} in the job
 *       workspace and report the exit code. Tool discovery, log streaming and the fail-on-error rule are the whole
 *       step; the SDK handles the contract around them.
 *   <li>{@code action=resolve}: fetch artifacts by coordinate from a Maven repository and put them in the workspace.
 *       That is {@link MavenResolve}, the atom {@code maven-resolve} 1.0.0, moved here unchanged -- both verbs are
 *       Maven and a second atom would only have to re-explain Maven.
 * </ul>
 */
public class MavenAtom implements AtomTask {

    /** What {@code action} accepts; also what an unknown one is told to use. */
    static final List<String> ACTIONS = List.of("build", "resolve");

    private static final String TOOL = "mvn";
    private static final String TOOL_HOME = "MAVEN_HOME";

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String action = context.getParam("action", "build").trim().toLowerCase(Locale.ROOT);
        if (action.isEmpty()) {
            action = "build";
        }
        if (!ACTIONS.contains(action)) {
            return AtomResult.error(ErrorType.USER, 40014,
                    "action '" + action + "' is not supported; use one of " + String.join(" / ", ACTIONS));
        }
        return "resolve".equals(action) ? MavenResolve.run(context) : build(context);
    }

    // ------------------------------------------------------------------ build

    private AtomResult build(AtomContext context) throws Exception {
        String goals = expand(context, context.getParam("goals", "verify"));
        String options = expand(context, context.getParam("options", ""));
        String home = context.getParam("home", "");
        boolean failOnError = context.getBooleanParam("failOnError", true);

        List<String> command = new ArrayList<>();
        command.add(binary(home));
        command.addAll(split(goals));
        command.addAll(split(options));
        // mvn colours its output only for a terminal; here stdout is a pipe, so ask for the colour anyway — the runner
        // turns the ANSI sequences into markup, and a plain log would otherwise lose the one signal Maven is loudest with
        if (command.stream().noneMatch(option -> option.startsWith("-Dstyle.color"))
                && command.stream().noneMatch(option -> option.startsWith("-Dmaven.color.force"))) {
            command.add("-Dstyle.color=always");
        }

        long started = System.currentTimeMillis();
        ProcessBuilder builder =
                new ProcessBuilder(command).directory(context.getWorkspace().toFile());
        builder.inheritIO();
        context.log("[mvn] " + String.join(" ", command) + "  (in " + context.getWorkspace() + ")");
        int exit;
        try {
            exit = builder.start().waitFor();
        } catch (IOException e) {
            return AtomResult.error(ErrorType.THIRD_PART, 40001, TOOL + " could not be started: " + e.getMessage());
        }
        long duration = System.currentTimeMillis() - started;
        context.log("[mvn] exit=" + exit + " in " + duration + " ms");

        AtomResult result = AtomResult.success()
                .data("exitCode", String.valueOf(exit))
                .data("durationMs", String.valueOf(duration));
        if (exit != 0 && failOnError) {
            return AtomResult.failure(TOOL + " exited with " + exit)
                    .data("exitCode", String.valueOf(exit))
                    .data("durationMs", String.valueOf(duration));
        }
        return result;
    }

    /** The tool's executable: {@code <home>/bin/<tool>} when a home is set, else whatever PATH resolves. */
    private static String binary(String home) {
        String configured = home == null ? "" : home.trim();
        if (configured.isEmpty()) {
            configured = System.getenv().getOrDefault(TOOL_HOME, "");
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

    private static String expand(AtomContext context, String template) {
        Matcher matcher = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}").matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = context.getParam(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
