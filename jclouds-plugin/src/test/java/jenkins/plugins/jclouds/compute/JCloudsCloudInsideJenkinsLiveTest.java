package jenkins.plugins.jclouds.compute;

<<<<<<< HEAD
=======
import static org.junit.Assert.assertEquals;

import org.jvnet.hudson.test.JenkinsRule;
import hudson.util.FormValidation;

>>>>>>> 7301897bfa748ac9d0d056f4e129968b0c634ec4
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import org.jclouds.ssh.SshKeys;

<<<<<<< HEAD
import hudson.util.FormValidation;

public class JCloudsCloudInsideJenkinsLiveTest extends HudsonTestCase {
=======
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;

public class JCloudsCloudInsideJenkinsLiveTest {

    @Rule public JenkinsRule j = new JenkinsRule();
>>>>>>> 7301897bfa748ac9d0d056f4e129968b0c634ec4

    private ComputeTestFixture fixture;
    private JCloudsCloud cloud;
    private Map<String, String> generatedKeys;

    @Before
    public void setUp() throws Exception {
        fixture = new ComputeTestFixture();
        fixture.setUp();
        generatedKeys = SshKeys.generate();

        // TODO: this may need to vary per test
<<<<<<< HEAD
        cloud = new JCloudsCloud(fixture.getProvider() + "-profile", fixture.getProvider(), fixture.getIdentity(), fixture.getCredential(),
                fixture.getTenantId(), generatedKeys.get("private"), generatedKeys.get("public"), fixture.getEndpoint(),
                1, 30, 600 * 1000, 600 * 1000, null, Collections.<JCloudsSlaveTemplate>emptyList());
=======
        cloud = new JCloudsCloud(fixture.getProvider() + "-profile", fixture.getProvider(), fixture.getCredentialsId(),
                null, fixture.getEndpoint(), 1, CloudInstanceDefaults.DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES, 600 * 1000, 600 * 1000,
                null, "foobar", true, Collections.<JCloudsSlaveTemplate>emptyList());
>>>>>>> 7301897bfa748ac9d0d056f4e129968b0c634ec4
    }

    @Test
    public void testDoTestConnectionCorrectCredentialsEtc() throws IOException {
        FormValidation result = new JCloudsCloud.DescriptorImpl().doTestConnection(fixture.getProvider(), fixture.getCredentialsId(),
                generatedKeys.get("private"), fixture.getEndpoint(), null, true);
        assertEquals("Connection succeeded!", result.getMessage());
    }

    @After
    public void tearDown() {
        if (fixture != null)
            fixture.tearDown();
    }
}
