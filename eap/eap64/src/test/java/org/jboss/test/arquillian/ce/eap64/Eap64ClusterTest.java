package org.jboss.test.arquillian.ce.eap64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.jboss.arquillian.ce.api.OpenShiftHandle;
import org.jboss.arquillian.ce.api.Template;
import org.jboss.arquillian.ce.api.TemplateParameter;
import org.jboss.arquillian.ce.cube.RouteURL;
import org.jboss.arquillian.ce.httpclient.HttpClient;
import org.jboss.arquillian.ce.httpclient.HttpClientBuilder;
import org.jboss.arquillian.ce.httpclient.HttpRequest;
import org.jboss.arquillian.ce.httpclient.HttpResponse;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * @author Jonh Wendell
 */

@RunWith(Arquillian.class)
@Template(url = "https://raw.githubusercontent.com/jboss-openshift/application-templates/master/eap/eap64-basic-s2i.json",
parameters = {
        @TemplateParameter(name = "SOURCE_REPOSITORY_URL", value="https://github.com/jwendell/temp"),
        @TemplateParameter(name = "SOURCE_REPOSITORY_REF", value="master"),
        @TemplateParameter(name = "CONTEXT_DIR", value="cluster1")
        })
public class Eap64ClusterTest {
    private static final Logger log = Logger.getLogger(Eap64ClusterTest.class.getName());

    @RouteURL("eap-app")
    private URL url;

    @ArquillianResource
    OpenShiftHandle adapter;

    @Test
    @RunAsClient
    @Ignore
	public void dummyTest() throws Exception {
        Map<String, String> labels = new HashMap<>();
        labels.put("application", "eap-app");
        labels.put("deploymentConfig", "eap-app");
        adapter.scaleDeployment("eap-app", 2);
        InputStream response = adapter.execute(labels, 0, 8080, "/cluster1/Hi");
        log.info("GOT RESPONSE 1 " + readInputStream(response));

        response = adapter.execute(labels, 1, 8080, "/cluster1/Hi");
        log.info("GOT RESPONSE 2 " + readInputStream(response));

        response = adapter.execute(labels, 2, 8080, "/cluster1/Hi");
        log.info("GOT RESPONSE 3 (MUST FAIL): " + readInputStream(response));
    }

    @Test
    @RunAsClient
    public void testAppStillWorksWhenScalingDown() throws Exception {
        // Number of HTTP requests we are going to do
        final int REQUESTS = 100;
        // Initial number of replicas, will decrease over time until 1
        final int REPLICAS = 5;
        // Decrement the number of replicas on each STEP requests
        final int STEP = REQUESTS / REPLICAS;

        // Setup initial state
        int replicas = REPLICAS;
        adapter.scaleDeployment("eap-app", replicas);

        HttpClient client = HttpClientBuilder.untrustedConnectionClient();
        HttpRequest request = HttpClientBuilder.doGET(url.toString() + "/cluster1/Hi");

        // Do the requests
        for (int i = 1; i <= REQUESTS; i++) {
            HttpResponse response = client.execute(request);
            assertEquals(200, response.getResponseCode());

            String body = response.getResponseBodyAsString();
            log.info(String.format("Try %d -  GOT: %s", i,  body));
            assertTrue(body.startsWith("Served from node: "));

            if (i % STEP == 0 && replicas > 1) {
                replicas--;
                (new ScaleTo(replicas)).start();
            }
            
            Thread.sleep(1000);
        }
    }

    public static String readInputStream(InputStream is) throws Exception {
        try {
            String content = "";
            int ch;
            while ((ch = is.read()) != -1) {
                content += ((char) ch);
            }
            return content;
        } finally {
            is.close();
        }
    }

    private class ScaleTo extends Thread {
        int r;
        public ScaleTo(int r) {
            log.info(String.format("ScaleTo created with %d replicas\n", r));
            this.r = r;
        }

        @Override
        public void run() {
            try {
                adapter.scaleDeployment("eap-app", r);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
