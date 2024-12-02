package org.vilojona.sonarqubedashboard.client;

public record SonarQubeFilter(String sonarqubeUrl, String sonarqubeUser, String sonarqubePassword, String project,
        String folder, String branch, String fileName, String severity) {
}
