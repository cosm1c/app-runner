package e2e;

import com.danielflower.apprunner.Config;
import com.danielflower.apprunner.runners.JavaHomeProvider;
import com.danielflower.apprunner.runners.MavenRunner;
import com.danielflower.apprunner.runners.Waiter;
import com.danielflower.apprunner.web.WebServer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.maven.shared.invoker.InvocationOutputHandler;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.json.JSONObject;
import org.junit.*;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import scaffolding.AppRepo;
import scaffolding.RestClient;
import scaffolding.TestConfig;

import java.io.File;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.danielflower.apprunner.FileSandbox.dirPath;
import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ContentResponseMatcher.equalTo;

public class SystemTest {

    static final int port = WebServer.getAFreePort();
    static final String appRunnerUrl = "http://localhost:" + port;
    static final RestClient restClient = RestClient.create(appRunnerUrl);
    static final HttpClient client = new HttpClient();
    static final AppRepo leinApp = AppRepo.create("lein");
    static final AppRepo mavenApp = AppRepo.create("maven");
    static final AppRepo nodeApp = AppRepo.create("nodejs");
    static final File dataDir = new File("target/datadirs/" + System.currentTimeMillis());
    static MavenRunner mavenRunner;
    private static List<AppRepo> apps = asList(mavenApp, nodeApp, leinApp);


    @BeforeClass
    public static void setup() throws Exception {
        client.start();
        buildAndStartUberJar();

        for (AppRepo app : apps) {
            assertThat(restClient.createApp(app.gitUrl()).getStatus(), is(201));
            assertThat(restClient.deploy(app.name).getStatus(), is(200));
        }
    }

    public static void buildAndStartUberJar() throws Exception {
        mavenRunner = new MavenRunner(new File("."), JavaHomeProvider.default_java_home,
            asList("-DskipTests=true", "package"));
        Map<String, String> env = new HashMap<String, String>(System.getenv()) {{
            put(Config.SERVER_PORT, String.valueOf(port));
            put(Config.DATA_DIR, dirPath(dataDir));
            put("JAVA_HOME", dirPath(TestConfig.config.javaHome()));
        }};
        InvocationOutputHandler logHandler = line -> System.out.println("Test build output > " + line);
        URI appRunnerURL = URI.create(appRunnerUrl + "/");
        try (Waiter startupWaiter = Waiter.waitFor("AppRunner uber jar", appRunnerURL, 2, TimeUnit.MINUTES)) {
            mavenRunner.start(logHandler, logHandler, env, startupWaiter);
        }
    }

    public static void shutDownAppRunner() throws Exception {
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
        buildAndStartUberJar();

        assertThat(restClient.homepage(mavenApp.name), is(equalTo(200, containsString("My Maven App"))));
        leinAppsWork();
        nodeAppsWork();

        File indexHtml = new File(mavenApp.originDir, FilenameUtils.separatorsToSystem("src/main/resources/web/index.html"));
        String newVersion = FileUtils.readFileToString(indexHtml).replace("My Maven App", "My new and improved maven app!");
        FileUtils.write(indexHtml, newVersion, false);
        mavenApp.origin.add().addFilepattern(".").call();
        mavenApp.origin.commit().setMessage("Updated index.html").setAuthor("Dan F", "danf@example.org").call();

        assertThat(restClient.deploy(mavenApp.name),
            is(equalTo(200, allOf(
                containsString("Going to build and deploy " + mavenApp.name),
                containsString("Success")))));

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
    public void theRestAPILives() throws Exception {
        ContentResponse resp = client.GET(appRunnerUrl + "/api/v1/apps");
        assertThat(resp.getStatus(), is(200));

        JSONObject all = new JSONObject(resp.getContentAsString());

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
}
