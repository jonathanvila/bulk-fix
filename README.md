# My App

This application is a frontend to SonarQube API in order to interact with issues that have CodeFix available.
1. set the parameters including severity and folder
2. specifying a folder will make the app to retrieve all issues in that folder and below
3. get the issues
4. the grid will fill with only the issues that have CodeFix available
5. we can open the selected issue in the SonarQube instance configured
6. we can export those issues in the grid and the app will generate 2 files with a name using the params and the prefix set below
7. these 2 files are a JSON and a CSV
8. if we select an issue and click on Send to Sonarlint this will open the local instance of the IDE with the refactored issue

## Running the application

The project is a standard Maven project. To run it from the command line,
type `mvnw` (Windows), or `./mvnw` (Mac & Linux), then open
http://localhost:8080 in your browser.

## Requirements
Java 21
