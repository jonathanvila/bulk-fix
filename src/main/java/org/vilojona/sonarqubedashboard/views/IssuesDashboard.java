package org.vilojona.sonarqubedashboard.views;

import java.net.http.HttpClient;
import java.io.IOException;
import org.sonarqube.ws.Issues.Issue;
import org.vilojona.sonarqubedashboard.client.SonarQubeClient;
import org.vilojona.sonarqubedashboard.client.SonarQubeFilter;

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
import com.vaadin.flow.data.provider.ListDataProvider;
import com.vaadin.flow.router.Menu;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

@PageTitle("Bulk Issues")
@Route("")
@Menu(order = 0, icon = "line-awesome/svg/globe-solid.svg")
public class IssuesDashboard extends VerticalLayout {
    private TextField projectEdit;
    private Button getIssuesButton;
    private ComboBox<String> severityCombo;
    private NativeLabel numberOfIssuesFilteredWithAIFixLabel;
    private HorizontalLayout sonarqubePanel;
    private TextField sonarqubeUrlEdit;
    private TextField sonarqubeUserEdit;
    private TextField sonarqubePasswordEdit;
    private TextField branchEdit;
    private Grid<Issue> issuesGrid;
    private TextField folderEdit;
    private Button applyFixesButton;
    private TextField fileNameEdit;

    public IssuesDashboard() {
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

        numberOfIssuesFilteredWithAIFixLabel = new NativeLabel("Total Filtered Issues and with AI Fix : ");

        var exportButton = new Button("Export AI Fixes to CSV + JSON");
        exportButton.addClickListener(e -> {
            Notification.show("Exporting issues");
            exportFixes();
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
        add(sonarqubePanel, filterPanel, fileNameEdit, issuesPanel,
                numberOfIssuesFilteredWithAIFixLabel,
                exportButton, openInSonarQubeButton,
                issuesGrid, applyFixesButton);
    }

    private SonarQubeFilter getSonarQubeFilter() {
        return new SonarQubeFilter(sonarqubeUrlEdit.getValue(), sonarqubeUserEdit.getValue(),
                sonarqubePasswordEdit.getValue(), projectEdit.getValue(), folderEdit.getValue(), branchEdit.getValue(),
                fileNameEdit.getValue(), severityCombo.getValue());
    }

    public void getIssues() {
        var sonarQubeClient = new SonarQubeClient(getSonarQubeFilter());
        var issuesWithCodeFixList = sonarQubeClient.getIssues();
        issuesGrid.setItems(issuesWithCodeFixList);
        numberOfIssuesFilteredWithAIFixLabel
                .setText("Total Filtered Issues with and AI Fix : " + issuesWithCodeFixList.size());
    }

    public void exportFixes() {
        exportIssuesWithCodeFix();
    }

    public void applyFixes() {
        HttpClient client = HttpClient.newHttpClient();
        var sonarQubeClient = new SonarQubeClient(getSonarQubeFilter());

        var port = sonarQubeClient.findSonarLintPortByCheckingStatusAPI();
        if (port == -1) {
            Notification.show("SonarLint not running");
            return;
        }
        issuesGrid.getSelectedItems().forEach(issue -> {
            try {
                var issueCodeFix = sonarQubeClient.fetchAiSuggestionsForIssue(client, issue);
                var codeFile = sonarQubeClient.getCodeFile(issue.getComponent());
                sonarQubeClient.sendCodeFixToSonarLint(port, issue, issueCodeFix.fix(), codeFile);
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        });
    }

    private void exportIssuesWithCodeFix() {
        HttpClient client = HttpClient.newHttpClient();
        var sonarQubeClient = new SonarQubeClient(getSonarQubeFilter());

        String fileName = sonarQubeClient.getOutputFileName() + "-exported.json";
        String fileNameCSV = sonarQubeClient.getOutputFileName() + "-exported.csv";
        try (var writer = new java.io.FileWriter(fileName, true);
                var writerCSV = new java.io.FileWriter(fileNameCSV, true);) {
            writer.write("[\n");
            writerCSV.write("Rule,Severity,File,IssueId,Explanation\n");
            
             ((ListDataProvider<Issue>)issuesGrid.getDataProvider()).getItems().forEach(issue -> {
                try {
                    var issueCodeFix = sonarQubeClient.fetchAiSuggestionsForIssue(client, issue);

                    writer.write(new ObjectMapper().writeValueAsString(issueCodeFix) + System.lineSeparator());
                    writer.write(",\n");

                    writerCSV.write(
                            issue.getRule() + "," + issue.getSeverity().toString() + "," + issue.getComponent() + "," +
                                    "\"" + getSonarQubeIssueLink(issueCodeFix.fix().issueId()) + "\"" +
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
        return sonarqubeUrlEdit.getValue() + "/project/issues?id=" + projectEdit.getValue() + "&open=" + issueId;
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
}
