package org.vilojona.sonarqubedashboard.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import org.sonarqube.ws.client.HttpConnector;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.issues.SearchRequest;
import org.sonarqube.ws.client.sources.RawRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.notification.Notification;

import org.sonarqube.ws.Issues.Issue;
import org.sonarqube.ws.Issues.SearchWsResponse;

public class SonarQubeClient {

    private static final String API_V2_FIX_SUGGESTIONS_AI_SUGGESTIONS = "/api/v2/fix-suggestions/ai-suggestions/";
    private static final String API_V2_FIX_SUGGESTIONS_ISSUES = "/api/v2/fix-suggestions/issues/";
    private static final String SONARLINT_API_FIX = "/sonarlint/api/fix/show";
    private static final int SONARLINT_API_INITIAL_PORT = 64120;
    private static final int SONARLINT_API_FINAL_PORT = 64130;
    private SonarQubeFilter filter;

    public record IssueAndFix(String file, String rule, AISuggestion fix) {
    }

    public SonarQubeClient(SonarQubeFilter filter) {
        this.filter = filter;
    }

    public String getCodeFile(String component) {
        // Call the SonarQube API to get the issues
        var httpConnector = HttpConnector.newBuilder()
                .url(filter.sonarqubeUrl())
                .credentials(filter.sonarqubeUser(), filter.sonarqubePassword())
                .build();
        var wsClient = WsClientFactories.getDefault().newClient(httpConnector);
        var fileCodeRequest = new RawRequest();
        fileCodeRequest.setKey(component);
        return new String(wsClient.sources().raw(fileCodeRequest).getBytes());
    }

    public List<Issue> getIssues() {
        return getIssuesFilteredAndWithAIFix();
    }

    public int findSonarLintPortByCheckingStatusAPI() {
        var port = SONARLINT_API_INITIAL_PORT;
        while (port <= SONARLINT_API_FINAL_PORT) {
            var uri = URI.create("http://localhost:" + port + "/sonarlint/api/status");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .GET()
                    .header("Origin", filter.sonarqubeUrl())
                    .header("Referer", filter.sonarqubeUrl())
                    .build();
            HttpClient client = HttpClient.newHttpClient();
            try {
                HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
                if ((response.statusCode() == 200)
                        && (response.body().toLowerCase().contains("- " + filter.project().toLowerCase()))) {
                    return port;
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
            port++;
        }
        return -1;
    }

    public void sendCodeFixToSonarLint(int port, Issue issue, AISuggestion issueCodeFix, String codeFile)
            throws JsonProcessingException {
        var uri = URI.create("http://localhost:" + port + SONARLINT_API_FIX +
                "?server=" + URLEncoder.encode(filter.sonarqubeUrl(), StandardCharsets.UTF_8) +
                "&project=" + filter.project() +
                "&issue=" + issueCodeFix.issueId() +
                "&branch=master");

        var codeFileLines = codeFile.split("\n");
        var sonarLintSuggestion = new SonarLintSuggestion(
                issueCodeFix.explanation(),
                new SonarLintSuggestion.FileEdit(
                        issueCodeFix.changes().stream().map(change -> new SonarLintSuggestion.FileEdit.Change(
                                change.newCode(),
                                String.join("\n",
                                        Arrays.copyOfRange(codeFileLines, change.startLine() - 1, change.endLine())),
                                new SonarLintSuggestion.FileEdit.Change.LineRange(
                                        change.startLine(),
                                        change.endLine())))
                                .toList(),
                        getFileFromComponent(issue.getComponent())),
                issueCodeFix.id());
        HttpRequest sendCodeFixToSonarLintRequest = HttpRequest.newBuilder()
                .uri(URI.create(uri.toString()))
                .POST(BodyPublishers.ofString(new ObjectMapper().writeValueAsString(sonarLintSuggestion)))
                .build();

        HttpClient client = HttpClient.newHttpClient();
        try {
            HttpResponse<String> response = client.send(sendCodeFixToSonarLintRequest, BodyHandlers.ofString());
            System.out.println(response.request().toString() + "\n" + response.body());

            writeToFileAppliedCodeFix(issueCodeFix);
            Notification.show("Response from SonarLint: " + response.statusCode() + "\n" + response.body());
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }

    }

    public void writeToFileAppliedCodeFix(AISuggestion issueCodeFix) {
        String fileNameToOutput = getOutputFileName() + "-applied.json";
        try (var writer = new java.io.FileWriter(fileNameToOutput, true)) {
            writer.write(new ObjectMapper().writeValueAsString(issueCodeFix) + System.lineSeparator());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getOutputFileName() {
        String fileNameFinal = filter.fileName() + filter.project();
        fileNameFinal += (filter.severity() != null) ? "-" +  filter.severity() : "";
        fileNameFinal += (filter.folder().isEmpty()) ? "" : "-" + filter.folder();
        fileNameFinal += (filter.branch().isEmpty()) ? "" : "-" + filter.branch();
        return fileNameFinal;
    }

    public String getFileFromComponent(String component) {
        return component.substring(component.indexOf(":") + 1);
    }

    public SearchWsResponse getListOfIssues(String project, String page, String pageSize) {
        // Call the SonarQube API to get the issues
        var httpConnector = HttpConnector.newBuilder()
                .url(filter.sonarqubeUrl())
                .credentials(filter.sonarqubeUser(), filter.sonarqubePassword())
                .build();
        var wsClient = WsClientFactories.getDefault().newClient(httpConnector);
        var issueRequest = new SearchRequest();
        issueRequest.setProjects(Collections.singletonList(project));
        if (filter.severity() != null) {
            issueRequest.setSeverities(List.of(filter.severity()));
        }

        // Apparently this is not working as expected and it returns 0 issues with
        // values like "gradle-plugin/%", "gradle-plugin/*", "*", "/*
        //
        // if (!folderEdit.getValue().isEmpty()) {
        // issueRequest.setComponentKeys(List.of(project + ":" +
        // folderEdit.getValue()));
        // }

        issueRequest.setP(page);
        issueRequest.setPs(pageSize);
        return wsClient.issues().search(issueRequest);
    }

    public List<Issue> getIssuesFilteredAndWithAIFix() {
        HttpClient client = HttpClient.newHttpClient();

        // to know the total number of issues
        var issuesListResponse = getListOfIssues(filter.project(), "1", "1");

        // get all the issues, page by page
        // for each issue, check if it is for a file in the folder filtered
        // if so check if the issue has code fix suggestions
        var issuesRetrieved = 0;
        var issuesWithCodeFix = new ArrayList<Issue>();
        var page = 1;
        while (issuesRetrieved < issuesListResponse.getPaging().getTotal()) {
            issuesListResponse = getListOfIssues(filter.project(), String.valueOf(page), "100");
            issuesRetrieved += issuesListResponse.getIssuesCount();
            var issuesList = issuesListResponse.getIssuesList();
            for (var issue : issuesList) {
                // if the issue is for a file in the folder filtered
                if (filter.folder().isEmpty()
                        || issue.getComponent().startsWith(filter.project() + ":" + filter.folder())) {
                    var urlFixSuggestionsIssues = filter.sonarqubeUrl() + API_V2_FIX_SUGGESTIONS_ISSUES
                            + issue.getKey();

                    // check if the issue has code fix suggestions
                    HttpRequest requestCheckIfIssueHasCodeFix = HttpRequest.newBuilder()
                            .uri(URI.create(urlFixSuggestionsIssues))
                            .header("Authorization", getAuthorization())
                            .build();
                    try {
                        HttpResponse<String> response = client.send(requestCheckIfIssueHasCodeFix,
                                BodyHandlers.ofString());
                        if (response.body().contains("\"aiSuggestion\":\"AVAILABLE\"")) {
                            issuesWithCodeFix.add(issue);
                        }
                    } catch (IOException | InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
            page++;
        }
        return issuesWithCodeFix;
    }

    public IssueAndFix fetchAiSuggestionsForIssue(HttpClient client, Issue issue)
            throws IOException, InterruptedException {
        HttpRequest requestCheckIfIssueHasCodeFix;
        HttpResponse<String> response;

        requestCheckIfIssueHasCodeFix = HttpRequest.newBuilder()
                .uri(URI.create(filter.sonarqubeUrl() + API_V2_FIX_SUGGESTIONS_AI_SUGGESTIONS))
                .header("Authorization", getAuthorization())
                .POST(BodyPublishers.ofString("{\"issueId\": \"" + issue.getKey() + "\"}"))
                .header("Content-Type", "application/json")
                .build();
        response = client.send(requestCheckIfIssueHasCodeFix, BodyHandlers.ofString());
        Notification.show("AI suggestions for issue " + issue.getKey() + " are: " + response.body());
        if (response.body().contains("message")) {
            Notification.show("Error " + issue.getKey() + " is: " + response.body());
            return null;
        }

        var aiSuggestion = new ObjectMapper().readValue(response.body(), AISuggestion.class);

        return new IssueAndFix(issue.getComponent(), issue.getRule(), aiSuggestion);
    }

    private String getAuthorization() {
        return "Basic " + Base64.getEncoder()
                .encodeToString((filter.sonarqubeUser() + ":" + filter.sonarqubePassword()).getBytes());
    }

}
