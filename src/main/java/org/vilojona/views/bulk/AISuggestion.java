package org.vilojona.views.bulk;

import java.util.List;


public record AISuggestion(
    String id,
    String issueId,
    String explanation,
    List<Change> changes
) {
    public record Change(
        int startLine,
        int endLine,
        String newCode
    ) {}
}
