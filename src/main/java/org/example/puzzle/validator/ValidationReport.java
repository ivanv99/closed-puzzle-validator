package org.example.puzzle.validator;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregated result of structural and semantic validation.
 */
public final class ValidationReport {

    public enum Severity {ERROR, WARNING, INFO}

    public record Issue(Severity severity, String code, String message, String path) {

        @Override
        public @NotNull String toString() {
            return String.format("[%s] %s %s — %s",
                    severity, code, path == null ? "" : "(" + path + ")", message);
        }
    }

    private final List<Issue> issues = new ArrayList<>();

    public void add(Severity severity, String code, String message, String path) {
        issues.add(new Issue(severity, code, message, path));
    }

    public void error(String code, String message) {
        add(Severity.ERROR, code, message, null);
    }

    public void error(String code, String message, String path) {
        add(Severity.ERROR, code, message, path);
    }

    public void warn(String code, String message, String path) {
        add(Severity.WARNING, code, message, path);
    }

    public List<Issue> issues() {
        return Collections.unmodifiableList(issues);
    }

    public boolean isValid() {
        return issues.stream().noneMatch(i -> i.severity() == Severity.ERROR);
    }

    public boolean isEmpty() {
        return issues.isEmpty();
    }

    public int errorCount() {
        return (int) issues.stream().filter(i -> i.severity() == Severity.ERROR).count();
    }

    public int warningCount() {
        return (int) issues.stream().filter(i -> i.severity() == Severity.WARNING).count();
    }
}

