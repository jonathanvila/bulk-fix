package org.vilojona.views.bulk;

import java.util.Collections;
import java.util.List;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URLEncoder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;

import org.sonarqube.ws.Issues.Issue;
import org.sonarqube.ws.Issues.SearchWsResponse;
import org.sonarqube.ws.client.HttpConnector;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.issues.SearchRequest;
import org.sonarqube.ws.client.sources.RawRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.Grid.SelectionMode;
import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Menu;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

@PageTitle("Bulk Issues")
@Route("")
@Menu(order = 0, icon = "line-awesome/svg/globe-solid.svg")
public class BulkIssuesView extends VerticalLayout {

    private static final String API_V2_FIX_SUGGESTIONS_AI_SUGGESTIONS = "/api/v2/fix-suggestions/ai-suggestions/";
    private static final String API_V2_FIX_SUGGESTIONS_ISSUES = "/api/v2/fix-suggestions/issues/";
    private static final String SONARLINT_API_FIX = "/sonarlint/api/fix/show";
    private static final int SONARLINT_API_INITIAL_PORT = 64120;
    private static final int SONARLINT_API_FINAL_PORT = 64130;
    private TextField projectEdit;
    private Button getIssuesButton;
    private ComboBox<String> severityCombo;
    private NativeLabel numberOfIssuesFilteredWithAIFixLabel;
    private NativeLabel numberOfIssuesFilteredLabel;
    private HorizontalLayout sonarqubePanel;
    private TextField sonarqubeUrlEdit;
    private TextField sonarqubeUserEdit;
    private TextField sonarqubePasswordEdit;
    private TextField branchEdit;
    private Grid<Issue> issuesGrid;
    private TextField folderEdit;
    private Button applyFixesButton;
    private List<Issue> issuesWithCodeFixList;
    private TextField fileNameEdit;

    record IssueAndFix(String file, String rule, AISuggestion fix) {
    }

    public BulkIssuesView() {

        sonarqubePanel = new HorizontalLayout();
        sonarqubeUrlEdit = new TextField("SonarQube Server URL");
        sonarqubeUrlEdit.setValue("http://localhost:9000");

        sonarqubeUserEdit = new TextField("User");

        sonarqubePasswordEdit = new TextField("Password");

        sonarqubePanel.add(sonarqubeUrlEdit, sonarqubeUserEdit, sonarqubePasswordEdit);

        var filterPanel = new HorizontalLayout();
        projectEdit = new TextField("Project");

        severityCombo = new ComboBox<>("Severity");
        severityCombo.setItems("INFO", "MINOR", "MAJOR", "CRITICAL", "BLOCKER");
        folderEdit = new TextField("Folder");
        branchEdit = new TextField("Branch");
        branchEdit.setValue("master");

        filterPanel.setMargin(true);
        filterPanel.add(projectEdit, severityCombo, folderEdit, branchEdit);

        var issuesPanel = new HorizontalLayout();
        getIssuesButton = new Button("Get Issues");
        getIssuesButton.addClickListener(e -> {
            getIssues();
            Notification.show("Requesting issues ");
        });
        getIssuesButton.addClickShortcut(Key.ENTER);
        issuesPanel.add(getIssuesButton);

        numberOfIssuesFilteredLabel = new NativeLabel("Total Project Issues : ");
        numberOfIssuesFilteredWithAIFixLabel = new NativeLabel("Total Filtered Issues and with AI Fix : ");

        var exportButton = new Button("Export AI Fixes to CSV + JSON");
        exportButton.addClickListener(e -> {
            Notification.show("Exporting issues");
            exportIssuesWithCodeFix();
            Notification.show("Exporting issues finished");
        });

        var openInSonarQubeButton = new Button("Open Selected Issue In SonarQube Server");
        openInSonarQubeButton.addClickListener(e -> {
            var selectedIssue = issuesGrid.asSingleSelect().getValue();
            if (selectedIssue != null) {
                getUI().ifPresent(ui -> ui.getPage().open(getSonarQubeIssueLink(selectedIssue.getKey())));
            } else {
                Notification.show("No issue selected");
            }
        });

        issuesGrid = new Grid<>();
        issuesGrid.setSelectionMode(SelectionMode.SINGLE);
        issuesGrid.addColumn(Issue::getProject).setHeader("Project");
        issuesGrid.addColumn(Issue::getSeverity).setHeader("Severity");
        issuesGrid.addColumn(Issue::getRule).setHeader("Rule");
        issuesGrid.addColumn(Issue::getComponent).setHeader("File");
        issuesGrid.addItemDoubleClickListener(e -> dialogIssue(issuesGrid.asSingleSelect().getValue()));

        applyFixesButton = new Button("Send Selected Fix to SonarQube IDE");
        applyFixesButton.addClickListener(e -> {
            Notification.show("Processing fixes");
            applyFixes();
            Notification.show("Processing fixes finished");
        });
        applyFixesButton.addClickShortcut(Key.ENTER);
        fileNameEdit = new TextField("Files Prefix");
        fileNameEdit.setValue("codefix-issues-output-");
        add(sonarqubePanel, filterPanel, fileNameEdit, issuesPanel, numberOfIssuesFilteredLabel, numberOfIssuesFilteredWithAIFixLabel,
                exportButton, openInSonarQubeButton,
                issuesGrid,  applyFixesButton);
    }

    private void exportIssuesWithCodeFix() {
        HttpClient client = HttpClient.newHttpClient();
        String fileName = getOutputFileName() + "-exported.json";
        String fileNameCSV = getOutputFileName() + "-exported.csv";
        try (var writer = new java.io.FileWriter(fileName, true);
                var writerCSV = new java.io.FileWriter(fileNameCSV, true);) {
            writer.write("[\n");
            writerCSV.write("Rule,Severity,File,IssueId,Explanation\n");
            issuesWithCodeFixList.forEach(issue -> {
                try {
                    var issueCodeFix = fetchAiSuggestionsForIssue(client, issue);

                    writer.write(new ObjectMapper().writeValueAsString(issueCodeFix) + System.lineSeparator());
                    writer.write(",\n");

                    writerCSV.write(
                            issue.getRule() + "," + issue.getSeverity().toString() + "," + issue.getComponent() + "," +
                                    "\"" + getSonarQubeIssueLink(issueCodeFix.fix.issueId()) + "\"" +
                                    ",\"" + issueCodeFix.fix().explanation().replaceAll("\"", "'") + "\""
                                    + System.lineSeparator());
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
            });
            writer.write("]\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getSonarQubeIssueLink(String issueId) {
        return  sonarqubeUrlEdit.getValue() + "/project/issues?id=" + projectEdit.getValue() + "&open=" + issueId ;
    }

    private void dialogIssue(Issue issue) {
        var dialog = new Dialog();
        dialog.setWidth("400px");
        dialog.setHeight("300px");

        var layout = new VerticalLayout();
        layout.add(new NativeLabel("Issue ID: " + issue.getKey()));
        layout.add(new NativeLabel("Project: " + issue.getProject()));
        layout.add(new NativeLabel("Severity: " + issue.getSeverity()));
        layout.add(new NativeLabel("Rule: " + issue.getRule()));
        layout.add(new NativeLabel("File: " + issue.getComponent()));

        var closeButton = new Button("Close", e -> dialog.close());
        layout.add(closeButton);

        dialog.add(layout);
        dialog.open();
    }

    private void applyFixes() {
        HttpClient client = HttpClient.newHttpClient();

        var port = findSonarLintPortByCheckingStatusAPI();
        if (port == -1) {
            Notification.show("SonarLint not running");
            return;
        }
        issuesGrid.getSelectedItems().forEach(issue -> {
            try {
                var issueCodeFix = fetchAiSuggestionsForIssue(client, issue);
                var codeFile = getCodeFile(issue.getComponent());
                sendCodeFixToSonarLint(port, issue, issueCodeFix.fix, codeFile);
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        });
    }

    private String createGitPatch(SonarLintSuggestion suggestion, String codeFile) {
        // header
        StringBuilder header = new StringBuilder();
        header.append("From: ").append(sonarqubeUserEdit.getValue()).append("\n");
        header.append("Date: ").append(java.time.LocalDate.now()).append("\n");
        header.append("Subject: [PATCH] Fix issue ").append(suggestion.suggestionId()).append("\n\n");
        header.append("---\n");

        // modified files
        int startLine = suggestion.fileEdit().changes().get(0).beforeLineRange().startLine();
        int endLine = suggestion.fileEdit().changes().get(0).beforeLineRange().endLine();
        var numberOfOldLines = endLine - startLine + 1;
        var numberOfNewLines = suggestion.fileEdit().changes().get(0).after().split("\n").length;

        var modifiedFiles = new StringBuilder();
        modifiedFiles.append("a/").append(suggestion.fileEdit().path())
                .append(" | ")
                .append(numberOfOldLines + numberOfNewLines).append(" ")
                .append("+".repeat(numberOfNewLines)).append("-".repeat(numberOfOldLines)).append("\n");

        // hunk
        var hunk = new StringBuilder();
        hunk.append("@@ -")
                .append(startLine).append(",").append(endLine).append(" ")
                .append(" +").append(startLine).append(",").append(numberOfNewLines)
                .append(" @@\n");

        // lines
        var lines = new StringBuilder();
        lines.append(codeFile).append("\n");

        // Patch
        var patch = new StringBuilder();
        patch.append(header).append(modifiedFiles).append(hunk).append(lines);
        return patch.toString();
    }

    private String getCodeFile(String component) {
        // Call the SonarQube API to get the issues
        var httpConnector = HttpConnector.newBuilder()
                .url(sonarqubeUrlEdit.getValue())
                .credentials(sonarqubeUserEdit.getValue(), sonarqubePasswordEdit.getValue())
                .build();
        var wsClient = WsClientFactories.getDefault().newClient(httpConnector);
        var fileCodeRequest = new RawRequest();
        fileCodeRequest.setKey(component);
        return new String(wsClient.sources().raw(fileCodeRequest).getBytes());
    }

    private void getIssues() {
        issuesWithCodeFixList = getIssuesFilteredAndWithAIFix();
        issuesGrid.setItems(issuesWithCodeFixList);
        numberOfIssuesFilteredWithAIFixLabel
                .setText("Total Filtered Issues with and AI Fix : " + issuesWithCodeFixList.size());
    }

    private int findSonarLintPortByCheckingStatusAPI() {
        var port = SONARLINT_API_INITIAL_PORT;
        while (port <= SONARLINT_API_FINAL_PORT) {
            var uri = URI.create("http://localhost:" + port + "/sonarlint/api/status");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .GET()
                    .header("Origin", sonarqubeUrlEdit.getValue())
                    .header("Referer", sonarqubeUrlEdit.getValue())
                    .build();
            HttpClient client = HttpClient.newHttpClient();
            try {
                HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
                if ((response.statusCode() == 200)
                        && (response.body().toLowerCase().contains("- " + projectEdit.getValue().toLowerCase()))) {
                    return port;
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
            port++;
        }
        return -1;
    }

    private void sendCodeFixToSonarLint(int port, Issue issue, AISuggestion issueCodeFix, String codeFile)
            throws JsonProcessingException {
        var uri = URI.create("http://localhost:" + port + SONARLINT_API_FIX +
                "?server=" + URLEncoder.encode(sonarqubeUrlEdit.getValue(), StandardCharsets.UTF_8) +
                "&project=" + projectEdit.getValue() +
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

    private void writeToFileAppliedCodeFix(AISuggestion issueCodeFix) {
        String fileName = getOutputFileName() + "-applied.json";
        try (var writer = new java.io.FileWriter(fileName, true)) {
            writer.write(new ObjectMapper().writeValueAsString(issueCodeFix) + System.lineSeparator());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getOutputFileName() {
        String fileName = fileNameEdit.getValue() + projectEdit.getValue();
        fileName += (severityCombo.getValue() != null) ? "-" + severityCombo.getValue() : "";
        fileName += (folderEdit.getValue().isEmpty()) ? "" : "-" + folderEdit.getValue();
        fileName += (branchEdit.getValue().isEmpty()) ? "" : "-" + branchEdit.getValue();
        return fileName;
    }

    private String getFileFromComponent(String component) {
        return component.substring(component.indexOf(":") + 1);
    }

    private SearchWsResponse getListOfIssues(String project, String page, String pageSize) {
        // Call the SonarQube API to get the issues
        var httpConnector = HttpConnector.newBuilder()
                .url(sonarqubeUrlEdit.getValue())
                .credentials(sonarqubeUserEdit.getValue(), sonarqubePasswordEdit.getValue())
                .build();
        var wsClient = WsClientFactories.getDefault().newClient(httpConnector);
        var issueRequest = new SearchRequest();
        issueRequest.setProjects(Collections.singletonList(project));
        if (severityCombo.getValue() != null) {
            issueRequest.setSeverities(List.of(severityCombo.getValue()));
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

    private List<Issue> getIssuesFilteredAndWithAIFix() {
        HttpClient client = HttpClient.newHttpClient();

        // to know the total number of issues
        var issuesListResponse = getListOfIssues(projectEdit.getValue(), "1", "1");
        numberOfIssuesFilteredLabel.setText("Total Project Issues : " + issuesListResponse.getPaging().getTotal());

        // get all the issues, page by page
        // for each issue, check if it is for a file in the folder filtered
        // if so check if the issue has code fix suggestions
        var issuesRetrieved = 0;
        var issuesWithCodeFix = new ArrayList<Issue>();
        var page = 1;
        while (issuesRetrieved < issuesListResponse.getPaging().getTotal()) {
            issuesListResponse = getListOfIssues(projectEdit.getValue(), String.valueOf(page), "100");
            issuesRetrieved += issuesListResponse.getIssuesCount();
            var issuesList = issuesListResponse.getIssuesList();
            for (var issue : issuesList) {
                // if the issue is for a file in the folder filtered
                if (folderEdit.getValue().isEmpty()
                        || issue.getComponent().startsWith(projectEdit.getValue() + ":" + folderEdit.getValue())) {
                    var urlFixSuggestionsIssues = sonarqubeUrlEdit.getValue() + API_V2_FIX_SUGGESTIONS_ISSUES
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

    private IssueAndFix fetchAiSuggestionsForIssue(HttpClient client, Issue issue)
            throws IOException, InterruptedException {
        HttpRequest requestCheckIfIssueHasCodeFix;
        HttpResponse<String> response;

        requestCheckIfIssueHasCodeFix = HttpRequest.newBuilder()
                .uri(URI.create(sonarqubeUrlEdit.getValue() + API_V2_FIX_SUGGESTIONS_AI_SUGGESTIONS))
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
                .encodeToString((sonarqubeUserEdit.getValue() + ":" + sonarqubePasswordEdit.getValue()).getBytes());
    }

}
