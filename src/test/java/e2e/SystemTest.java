package e2e;

import com.danielflower.apprunner.Config;
import com.danielflower.apprunner.runners.HomeProvider;
import com.danielflower.apprunner.runners.MavenRunner;
import com.danielflower.apprunner.runners.Waiter;
import com.danielflower.apprunner.web.WebServer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.maven.shared.invoker.InvocationOutputHandler;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import scaffolding.AppRepo;
import scaffolding.RestClient;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.danielflower.apprunner.FileSandbox.dirPath;
import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ContentResponseMatcher.equalTo;

public class SystemTest {

    private static final int port = WebServer.getAFreePort();
    private static final String appRunnerUrl = "http://localhost:" + port;
    private static final RestClient restClient = RestClient.create(appRunnerUrl);
    private static final HttpClient client = new HttpClient();
    private static final AppRepo leinApp = AppRepo.create("lein");
    private static final AppRepo mavenApp = AppRepo.create("maven");
    private static final AppRepo nodeApp = AppRepo.create("nodejs");
    private static final File dataDir = new File("target/datadirs/" + System.currentTimeMillis());
    private static MavenRunner mavenRunner;
    private static List<AppRepo> apps = asList(mavenApp, nodeApp, leinApp);

    @BeforeClass
    public static void setup() throws Exception {
        client.start();
        buildAndStartUberJar(asList("-DskipTests=true", "package"));

        for (AppRepo app : apps) {
            assertThat(restClient.createApp(app.gitUrl()).getStatus(), is(201));
            assertThat(restClient.deploy(app.name).getStatus(), is(200));
        }
    }

    private static void buildAndStartUberJar(List<String> goals) throws Exception {
        mavenRunner = new MavenRunner(new File("."), HomeProvider.default_java_home, goals);
        Map<String, String> env = new HashMap<String, String>(System.getenv()) {{
            put(Config.SERVER_PORT, String.valueOf(port));
            put(Config.DATA_DIR, dirPath(dataDir));
        }};

        InvocationOutputHandler logHandler = line -> System.out.print("Test build output > " + line);
        try (Waiter startupWaiter = new Waiter("AppRunner uber jar", httpClient -> {
            try {
                JSONObject sysInfo = new JSONObject(client.GET(appRunnerUrl + "/api/v1/system").getContentAsString());
                return sysInfo.getBoolean("appRunnerStarted");
            } catch (Exception e) {
                return false;
            }
        }, 2, TimeUnit.MINUTES)) {
            mavenRunner.start(logHandler, logHandler, env, startupWaiter);
        }

    }

    private static void shutDownAppRunner() throws Exception {
        for (AppRepo app : apps) {
            restClient.stop(app.name);
        }
        mavenRunner.shutdown();
    }

    @AfterClass
    public static void cleanup() throws Exception {
        shutDownAppRunner();
        restClient.stop();
        client.stop();
    }

    @Test
    public void leinAppsWork() throws Exception {
        assertThat(restClient.homepage(leinApp.name),
            is(equalTo(200, containsString("Hello from lein"))));
    }

    @Test
    public void nodeAppsWork() throws Exception {
        assertThat(restClient.homepage(nodeApp.name),
            is(equalTo(200, containsString("Hello from nodejs!"))));
    }

    @Test
    public void canCloneARepoAndStartItAndRestartingAppRunnerIsFine() throws Exception {
        assertThat(restClient.homepage(mavenApp.name), is(equalTo(200, containsString("My Maven App"))));

        shutDownAppRunner();
        buildAndStartUberJar(Collections.emptyList());

        assertThat(restClient.homepage(mavenApp.name), is(equalTo(200, containsString("My Maven App"))));
        leinAppsWork();
        nodeAppsWork();

        updateHeaderAndCommit(mavenApp, "My new and improved maven app!");

        assertThat(restClient.deploy(mavenApp.name),
            is(equalTo(200, containsString("buildLogUrl"))));

        JSONObject appInfo = new JSONObject(client.GET(appRunnerUrl + "/api/v1/apps/" + mavenApp.name).getContentAsString());

        assertThat(
            client.GET(appInfo.getString("url")),
            is(equalTo(200, containsString("My new and improved maven app!"))));


        assertThat(
            client.GET(appInfo.getString("buildLogUrl")),
            is(equalTo(200, containsString("[INFO] Building my-maven-app 1.0-SNAPSHOT"))));
        assertThat(
            client.GET(appInfo.getString("consoleLogUrl")),
            is(equalTo(200, containsString("Starting maven in prod"))));
    }

    @Test
    public void stoppedAppsSayTheyAreStopped() throws Exception {
        try {
            restClient.createApp(mavenApp.gitUrl(), "maven-status-test");
            assertMavenAppAvailable("maven-status-test", false, "Not started");
            restClient.deploy("maven-status-test");
            assertMavenAppAvailable("maven-status-test", true, "Running");
            restClient.stop("maven-status-test");
            assertMavenAppAvailable("maven-status-test", false, "Stopped");

            restClient.deploy("maven-status-test");
            assertMavenAppAvailable("maven-status-test", true, "Running");

            // Detecting crashed apps not supported yet
//            new JavaSysMon().processTree().accept((process, level) ->
//                process.processInfo().getCommand().contains("maven-status-test"), 2);
//            assertMavenAppAvailable("maven-status-test", false, "Crashed");
        } finally {
            restClient.deleteApp("maven-status-test");
        }
    }

    private static void assertMavenAppAvailable(String appName, boolean available, String message) throws InterruptedException, ExecutionException, TimeoutException {
        JSONObject appInfo = new JSONObject(client.GET(appRunnerUrl + "/api/v1/apps/" + appName).getContentAsString());
        assertThat(appInfo.getBoolean("available"), is(available));
        assertThat(appInfo.getString("availableStatus"), is(message));
    }

    private static void updateHeaderAndCommit(AppRepo mavenApp, String replacement) throws IOException, GitAPIException {
        File indexHtml = new File(mavenApp.originDir, FilenameUtils.separatorsToSystem("src/main/resources/web/index.html"));
        String newVersion = FileUtils.readFileToString(indexHtml).replaceAll("<h1>.*</h1>", "<h1>" + replacement + "</h1>");
        FileUtils.write(indexHtml, newVersion, false);
        mavenApp.origin.add().addFilepattern(".").call();
        mavenApp.origin.commit().setMessage("Updated index.html").setAuthor("Dan F", "danf@example.org").call();
    }

    @Test
    public void theRestAPILives() throws Exception {
        JSONObject all = getAllApps();
        ContentResponse resp;

        JSONAssert.assertEquals("{apps:[" +
            "{ name: \"lein\" }," +
            "{ name: \"maven\", url: \"" + appRunnerUrl + "/maven/\"}," +
            "{ name: \"nodejs\" }" +
            "]}", all, JSONCompareMode.LENIENT);

        assertThat(restClient.deploy("invalid-app-name"),
            is(equalTo(404, is("No app found with name 'invalid-app-name'. Valid names: lein, maven, nodejs"))));

        resp = client.GET(appRunnerUrl + "/api/v1/apps/maven");
        assertThat(resp.getStatus(), is(200));
        JSONObject single = new JSONObject(resp.getContentAsString());
        JSONAssert.assertEquals(all.getJSONArray("apps").getJSONObject(1), single, JSONCompareMode.STRICT_ORDER);
    }

    private static JSONObject getAllApps() throws InterruptedException, ExecutionException, TimeoutException {
        ContentResponse resp = client.GET(appRunnerUrl + "/api/v1/apps");
        assertThat(resp.getStatus(), is(200));

        return new JSONObject(resp.getContentAsString());
    }

    @Test
    public void postingToAnExistingNameChangesTheURL() throws Exception {
        ContentResponse resp = client.GET(appRunnerUrl + "/api/v1/apps/maven");
        String webUrl = new JSONObject(resp.getContentAsString()).getString("url");

        AppRepo newMavenApp = AppRepo.create("maven");
        updateHeaderAndCommit(newMavenApp, "Different repo");
        assertThat(restClient.createApp(newMavenApp.gitUrl()).getStatus(), is(200));
        restClient.deploy(newMavenApp.name);

        assertThat(getAllApps().getJSONArray("apps").length(), is(apps.size()));

        assertThat(
            client.GET(webUrl),
            is(equalTo(200, containsString("Different repo"))));

        // put the old app back. The git repo can no longer to a fastforward merge

        updateHeaderAndCommit(mavenApp, "My maven app");
        assertThat(restClient.createApp(mavenApp.gitUrl()).getStatus(), is(200));
        restClient.deploy(mavenApp.name);

        assertThat(
            client.GET(webUrl),
            is(equalTo(200, containsString("My maven app"))));

        assertThat(getAllApps().getJSONArray("apps").length(), is(apps.size()));
    }

    @Test
    public void appsCanBeDeleted() throws Exception {

        AppRepo newMavenApp = AppRepo.create("maven");
        updateHeaderAndCommit(newMavenApp, "Different repo");
        assertThat(restClient.createApp(newMavenApp.gitUrl(), "another-app").getStatus(), is(201));
        restClient.deploy(newMavenApp.name);

        assertThat(getAllApps().getJSONArray("apps").length(), is(apps.size() + 1));

        assertThat(
            restClient.deleteApp("another-app"),
            is(equalTo(200, containsString("another-app"))));

        assertThat(getAllApps().getJSONArray("apps").length(), is(apps.size()));
    }

    @Test
    public void appsCanGetAuthors() throws Exception {
        ContentResponse resp = client.GET(appRunnerUrl + "/api/v1/apps/maven");
        JSONObject respJson = new JSONObject(resp.getContentAsString());
        assertThat(
            respJson.getString("contributors"),
            is("Author Test"));
    }

    @Test
    public void theSystemApiReturnsZipsOfSampleProjects() throws IOException, InterruptedException, ExecutionException, TimeoutException {
        // ensure the zips exist
        new ZipSamplesTask().zipTheSamplesAndPutThemInTheResourcesDir();

        JSONObject sysInfo = new JSONObject(client.GET(appRunnerUrl + "/api/v1/system").getContentAsString());
        System.out.println("sysInfo.toString(4) = " + sysInfo.toString(4));
        JSONArray samples = sysInfo.getJSONArray("samples");
        assertThat(samples.length(), is(3));

        JSONAssert.assertEquals("[ " +
            "{ name: 'maven', runCommands: [ 'mvn clean package', 'java -jar target/{artifactid}-{version}.jar' ] }, " +
            "{ name: 'lein' }, " +
            "{ name: 'nodejs' } ]", samples, JSONCompareMode.LENIENT);

        for (Object app : samples) {
            JSONObject json = (JSONObject) app;
            String url = json.getString("url");
            ContentResponse zip = client.GET(url);
            assertThat(url, zip.getStatus(), is(200));
            assertThat(url, zip.getHeaders().get("Content-Type"), is("application/zip"));
        }

        assertThat(client.GET(appRunnerUrl + "/api/v1/system/samples/badname.zip").getStatus(), is((404)));
    }

    @Test
    public void theSwaggerJSONDescribesTheAPI() throws Exception {
        ContentResponse swagger = restClient.get("/api/v1/swagger.json");
        assertThat(swagger.getStatus(), is(200));
        System.out.println("swagger.getContentAsString() = " + swagger.getContentAsString());
        JSONAssert.assertEquals("{ basePath: '/api/v1'," +
            "paths: {" +
            "'/apps': {}," +
            "'/system': {}" +
            "}" +
            "}", swagger.getContentAsString(), JSONCompareMode.LENIENT);
    }
}
