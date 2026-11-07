package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The maven atom against a fake {@code mvn} on PATH: no Jenkins, no toolchain. What the old smoke test asserted —
 * arguments reach the tool, the tool runs in the job workspace, the exit code is reported, failOnError decides the
 * step's result, an unknown home falls back to PATH — is the same set of checks here.
 */
class MavenIT {

    /** Records its arguments and its working directory; "explode" means exit 7. */
    private static final String FAKE_MVN = """
            #!/bin/sh
            echo "fake mvn called with: $*"
            echo "workspace=$PWD"
            case "$*" in
              *explode*) exit 7 ;;
            esac
            exit 0
            """;

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("goals and options reach the tool, it runs in the workspace, exit 0 is a success")
    void argumentsAndWorkspace() throws Exception {
        AtomResult run = atom.bin("mvn", FAKE_MVN)
                // the smoke test unsets MAVEN_HOME so PATH decides; an empty value does the same for the atom
                .env("MAVEN_HOME", "")
                .input("{\"goals\": \"verify -Dbuild=${BUILD_NUMBER}\", \"options\": \"-Pci\", \"home\": \"\", "
                        + "\"failOnError\": \"true\", \"BUILD_NUMBER\": \"7\"}")
                .run();

        run.assertOk();
        assertTrue(run.console().contains("fake mvn called with: verify -Dbuild=7 -Pci"),
                "arguments did not reach the tool:\n" + run.console());
        assertTrue(run.console().contains("workspace=" + run.work()),
                "tool did not run in the workspace " + run.work() + ":\n" + run.console());
        assertEquals("success", run.status());
        assertEquals("0", run.field("exitCode"), "exit code missing");
    }

    @Test
    @DisplayName("a non-zero exit fails the step and is reported with the code")
    void nonZeroExitFails() throws Exception {
        AtomResult run = atom.bin("mvn", FAKE_MVN)
                .env("MAVEN_HOME", "")
                .input("{\"goals\": \"explode\", \"options\": \"\", \"home\": \"\", \"failOnError\": \"true\"}")
                .run();

        run.assertRefused();
        assertEquals("failure", run.status(), "failure not reported");
        assertTrue(run.file("output.json").contains("mvn exited with 7"), "exit code is not in the message");
        assertEquals("7", run.field("exitCode"));
    }

    @Test
    @DisplayName("failOnError=false turns a non-zero exit into a success with the code still reported")
    void toleratesFailure() throws Exception {
        AtomResult run = atom.bin("mvn", FAKE_MVN)
                .env("MAVEN_HOME", "")
                .input("{\"goals\": \"explode\", \"options\": \"\", \"home\": \"\", \"failOnError\": \"false\"}")
                .run();

        run.assertOk();
        assertEquals("7", run.field("exitCode"), "exit code lost");
    }

    @Test
    @DisplayName("an unknown home falls back to PATH instead of crashing")
    void unknownHomeFallsBackToPath() throws Exception {
        AtomResult run = atom.bin("mvn", FAKE_MVN)
                .env("MAVEN_HOME", "")
                .input("{\"goals\": \"verify\", \"options\": \"\", \"home\": \"/definitely/not/here\", "
                        + "\"failOnError\": \"true\"}")
                .run();

        run.assertOk();
        assertTrue(run.console().contains("fake mvn called"), "fallback to PATH did not happen:\n" + run.console());
    }
}
