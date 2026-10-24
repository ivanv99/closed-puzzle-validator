package org.example.puzzle.cli;

import org.example.puzzle.server.EngineServer;
import org.example.puzzle.validator.PuzzleValidator;
import org.example.puzzle.validator.ValidationReport;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Command-line entry point.
 *
 * <pre>
 *     java -jar closed-puzzle-validator.jar &lt;file.json&gt; [&lt;file2.json&gt; ...]
 *     java -jar closed-puzzle-validator.jar serve [--port N] [--parent-pid PID]
 * </pre>
 */
public final class ValidatorCli {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: validator <file.json> [<file2.json> ...]");
            System.err.println("       validator serve [--port N] [--parent-pid PID]");
            System.exit(64);
        }
        if (args[0].equals("serve")) {
            EngineServer.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        PuzzleValidator validator = new PuzzleValidator();
        int totalErrors = 0;

        for (String a : args) {
            Path file = Path.of(a);
            System.out.println("== " + file + " ==");
            ValidationReport r = validator.validate(file);
            if (r.isEmpty()) {
                System.out.println("  OK — no issues.");
            } else {
                for (ValidationReport.Issue issue : r.issues()) {
                    System.out.println("  " + issue);
                }
            }
            System.out.printf("  -> %d error(s), %d warning(s)%n",
                    r.errorCount(), r.warningCount());
            totalErrors += r.errorCount();
        }

        System.exit(totalErrors == 0 ? 0 : 1);
    }

    private ValidatorCli() {
    }
}

