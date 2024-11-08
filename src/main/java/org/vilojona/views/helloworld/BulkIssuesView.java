package org.vilojona.views.helloworld;

import java.util.Collections;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.HttpURLConnection;
import java.net.URI;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Base64;

import org.sonarqube.ws.client.HttpConnector;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.issues.SearchRequest;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Menu;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

@PageTitle("Bulk Issues")
@Route("")
@Menu(order = 0, icon = "line-awesome/svg/globe-solid.svg")
public class BulkIssuesView extends HorizontalLayout {

    private TextField project;
    private Button getIssues;
    private ComboBox<String> priority;
    private Text numberOfIssues;

    public BulkIssuesView() {
        project = new TextField("Project");
        priority = new ComboBox<>("Priority");
        priority.setItems("LOW", "MEDIUM", "HIGH");
        numberOfIssues = new Text("0");
        getIssues = new Button("Get Issues");

        getIssues.addClickListener(e -> {
            getIssues();
            Notification.show("Requesting issues ");
        });
        getIssues.addClickShortcut(Key.ENTER);

        setMargin(true);
        setVerticalComponentAlignment(Alignment.END, project, getIssues);

        add(project, priority, getIssues, numberOfIssues);
    }

    private void getIssues() {
        // Call the SonarQube API to get the issues
        var httpConnector = HttpConnector.newBuilder()
                .url("http://localhost:9000")
                .credentials("admin", "Jonatito1609$")
                .build();
        var wsClient = WsClientFactories.getDefault().newClient(httpConnector);
        var issueRequest = new SearchRequest();
        issueRequest.setProjects(Collections.singletonList(project.getValue()));
        issueRequest.setPs("1");

        var issuesList = wsClient.issues().search(issueRequest);
        numberOfIssues.setText(String.valueOf(issuesList.getPaging().getTotal()));

        var issuesRetrieved = 0;
        issueRequest.setP("1");
        issueRequest.setPs("100");
        while (issuesRetrieved < issuesList.getPaging().getTotal()) {
            issuesList = wsClient.issues().search(issueRequest);
            for (var issue : issuesList.getIssuesList()) {
                var url = "http://localhost:9000/api/v2/fix-suggestions/issues/" + issue.getKey();
                
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("admin:Jonatito1609$".getBytes()))
                    .build();
                HttpResponse<String> response;
                try {
                    response = client.send(request, BodyHandlers.ofString());
                    if (response.body().contains("AVAILABLE")) {
                        request = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:9000/api/v2/fix-suggestions/ai-suggestions/"))
                        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("admin:Jonatito1609$".getBytes()))
                        .POST(BodyPublishers.ofString("{\"issueId\": \"" + issue.getKey() + "\"}"))
                        .header("Content-Type", "application/json")
                        .build();
                        response = client.send(request, BodyHandlers.ofString());
                        Notification.show("AI suggestions for issue " + issue.getKey() + " are: " + response.body());                
                    }
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
                
            }
            issueRequest.setP(String.valueOf(Integer.parseInt(issueRequest.getP()) + 1));
        }
        
    }

}
