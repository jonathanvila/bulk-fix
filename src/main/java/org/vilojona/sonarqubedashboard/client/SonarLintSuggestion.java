package org.vilojona.sonarqubedashboard.client;

import java.util.List;

public record SonarLintSuggestion(
    String explanation,
    FileEdit fileEdit,
    String suggestionId
) {
    public record FileEdit(
        List<Change> changes,
        String path
    ) {
        public record Change(
            String after,
            String before,
            LineRange beforeLineRange
        ) {
            public record LineRange(
                int startLine,
                int endLine
            ) {}
        }
    }
}