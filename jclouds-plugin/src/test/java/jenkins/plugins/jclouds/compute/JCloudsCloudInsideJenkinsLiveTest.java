package jenkins.plugins.jclouds.compute;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import org.jclouds.ssh.SshKeys;
import org.jvnet.hudson.test.HudsonTestCase;

import hudson.util.FormValidation;

public class JCloudsCloudInsideJenkinsLiveTest extends HudsonTestCase {

    private ComputeTestFixture fixture;
    private JCloudsCloud cloud;
    private Map<String, String> generatedKeys;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        fixture = new ComputeTestFixture();
        fixture.setUp();
        generatedKeys = SshKeys.generate();

        // TODO: this may need to vary per test
        cloud = new JCloudsCloud(fixture.getProvider() + "-profile", fixture.getProvider(), fixture.getIdentity(), fixture.getCredential(),
                fixture.getTenantId(), generatedKeys.get("private"), generatedKeys.get("public"), fixture.getEndpoint(),
                1, 30, 600 * 1000, 600 * 1000, null, Collections.<JCloudsSlaveTemplate>emptyList());
    }

    public void testDoTestConnectionCorrectCredentialsEtc() throws IOException {
        FormValidation result = new JCloudsCloud.DescriptorImpl().doTestConnection(fixture.getProvider(), fixture.getIdentity(), fixture.getCredential(),
                generatedKeys.get("private"), fixture.getEndpoint(), null);
        assertEquals("Connection succeeded!", result.getMessage());
    }

    @Override
    public void tearDown() {
        if (fixture != null)
            fixture.tearDown();
    }
}
