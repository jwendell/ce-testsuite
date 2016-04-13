package org.jboss.test.arquillian.ce.eap64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.jboss.arquillian.ce.api.ConfigurationHandle;
import org.jboss.arquillian.ce.api.OpenShiftHandle;
import org.jboss.arquillian.ce.api.OpenShiftResource;
import org.jboss.arquillian.ce.api.RoleBinding;
import org.jboss.arquillian.ce.api.RoleBindings;
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
        @TemplateParameter(name = "SOURCE_REPOSITORY_REF", value="t1"),
        @TemplateParameter(name = "CONTEXT_DIR", value="cluster1")
        })
@OpenShiftResource("classpath:eap-app-secret.json")
@RoleBindings({
        @RoleBinding(roleRefName = "view", userName = "system:serviceaccount:${kubernetes.namespace}:default"),
        @RoleBinding(roleRefName = "view", userName = "system:serviceaccount:${kubernetes.namespace}:eap-service-account")
})
public class Eap64ClusterTest {
    private static final Logger log = Logger.getLogger(Eap64ClusterTest.class.getName());

    @ArquillianResource
    OpenShiftHandle adapter;

    @ArquillianResource
    ConfigurationHandle config;

    @Test
    @RunAsClient
	public void testSession() throws Exception {
        final String token = config.getToken();
        assertFalse(token.isEmpty());

        adapter.scaleDeployment("eap-app", 2);
        List<String> pods = adapter.getPods();
        pods.removeIf(p -> p.endsWith("-build") || !p.startsWith("eap-app-"));

        HttpClient client = HttpClientBuilder.untrustedConnectionClient();
        final String value = UUID.randomUUID().toString();

        // Insert a session value into the first pod
        String servletUrl = buildURL(pods.get(0));
        HttpRequest request = HttpClientBuilder.doPOST(servletUrl);
        request.setHeader("Authorization", "Bearer " + token);
        Map<String, String> params = new HashMap<>();
        params.put("key", value);
        request.setEntity(params);
        HttpResponse response = client.execute(request);
        assertEquals("OK", response.getResponseBodyAsString());

        // Retrieve the value from the second pod
        servletUrl = buildURL(pods.get(1));
        request = HttpClientBuilder.doGET(servletUrl);
        request.setHeader("Authorization", "Bearer " + token);
        request.setHeader("Cookie", response.getHeader("Set-Cookie"));
        response = client.execute(request);
        assertEquals(value, response.getResponseBodyAsString());
    }

    private String buildURL(String podName) {
        final String PROXY_URL = "%s/api/%s/namespaces/%s/pods/%s:%s/proxy%s";
        return String.format(PROXY_URL, config.getKubernetesMaster(), config.getApiVersion(), config.getNamespace(), podName, 8080, "/cluster1/StoreInSession");
    }

    @Test
    @RunAsClient
    @Ignore
    public void testAppStillWorksWhenScalingDown(@RouteURL("eap-app") URL url) throws Exception {
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
