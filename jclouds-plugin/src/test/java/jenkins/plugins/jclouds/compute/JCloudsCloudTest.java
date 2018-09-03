package jenkins.plugins.jclouds.compute;

import java.util.Collections;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import com.gargoylesoftware.htmlunit.WebAssert;
import com.gargoylesoftware.htmlunit.html.HtmlAnchor;
import com.gargoylesoftware.htmlunit.html.HtmlButton;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlFormUtil;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @author Vijay Kiran
 */
public class JCloudsCloudTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    private void mySelectPresent(final HtmlPage p, final String name) {
        final String xpath = "//select[@name='" + name + "']";
        final List<?> list = p.getByXPath(xpath);
        if (list.isEmpty()) {
            throw new AssertionError("Unable to find an select element named '" + name + "'.");
        }
    }

    @Test
    public void testConfigurationUI() throws Exception {
<<<<<<< HEAD
        j.recipeLoadCurrentPlugin();
        j.configRoundtrip();
        HtmlPage page = j.createWebClient().goTo("configure");
        final String pageText = page.asText();
        assertTrue("Cloud Section must be present in the global configuration ", pageText.contains("Cloud"));

        final HtmlForm configForm = page.getFormByName("config");
        final HtmlButton buttonByCaption = configForm.getButtonByName("Add a new cloud");
        HtmlPage page1 = buttonByCaption.click();
        WebAssert.assertLinkPresentWithText(page1, "Cloud (JClouds)");

        HtmlPage page2 = page.getAnchorByText("Cloud (JClouds)").click();
        WebAssert.assertInputPresent(page2, "_.profile");
        WebAssert.assertInputPresent(page2, "_.endPointUrl");
        WebAssert.assertInputPresent(page2, "_.identity");
        WebAssert.assertInputPresent(page2, "_.credential");
        WebAssert.assertInputPresent(page2, "_.instanceCap");
        WebAssert.assertInputPresent(page2, "_.retentionTime");

        HtmlForm configForm2 = page2.getFormByName("config");
        assertNotNull(configForm2.getTextAreaByName("_.privateKey"));
        assertNotNull(configForm2.getTextAreaByName("_.publicKey"));
        HtmlButton generateKeyPairButton = configForm2.getButtonByName("Generate Key Pair");
        HtmlButton testConnectionButton = configForm2.getButtonByName("Test Connection");
        HtmlButton deleteCloudButton = configForm2.getButtonByName("Delete cloud");
        assertNotNull(generateKeyPairButton);
        assertNotNull(testConnectionButton);
        assertNotNull(deleteCloudButton);
=======
        JCloudsCloud cloud = new JCloudsCloud("aws-profile", "aws-ec2", "",
                "", "http://localhost", 1, CloudInstanceDefaults.DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES,
                600 * 1000, 600 * 1000, null, "foobar", true, Collections.<JCloudsSlaveTemplate>emptyList());
        j.getInstance().clouds.add(cloud);
>>>>>>> 7301897bfa748ac9d0d056f4e129968b0c634ec4

        HtmlPage p = j.createWebClient().goTo("configure");
        WebAssert.assertInputPresent(p, "_.profile");
        mySelectPresent(p, "_.providerName");
        WebAssert.assertInputPresent(p, "_.endPointUrl");
        WebAssert.assertInputPresent(p, "_.instanceCap");
        WebAssert.assertInputPresent(p, "_.retentionTime");
        mySelectPresent(p, "_.cloudCredentialsId");
        WebAssert.assertInputPresent(p, "_.trustAll");
        mySelectPresent(p, "_.cloudGlobalKeyId");
        WebAssert.assertInputPresent(p, "_.scriptTimeout");
        WebAssert.assertInputPresent(p, "_.startTimeout");
        WebAssert.assertInputPresent(p, "_.zones");
        WebAssert.assertInputPresent(p, "_.groupPrefix");
        HtmlForm f = p.getFormByName("config");
        HtmlButton b = HtmlFormUtil.getButtonByCaption(f, "Test Connection");
        assertNotNull(b);
        b = HtmlFormUtil.getButtonByCaption(f, "Delete cloud");
        assertNotNull(b);
    }

    @Test
    public void testConfigRoundtrip() throws Exception {

<<<<<<< HEAD
        JCloudsCloud original = new JCloudsCloud("aws-profile", "aws-ec2", "identity", "credential", "tenantId", "privateKey", "publicKey", "endPointUrl", 1, 30,
                600 * 1000, 600 * 1000, null, Collections.<JCloudsSlaveTemplate>emptyList());
=======
        JCloudsCloud original = new JCloudsCloud("aws-profile", "aws-ec2", "",
                "", "http://localhost", 1, CloudInstanceDefaults.DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES,
                600 * 1000, 600 * 1000, null, "foobar", true, Collections.<JCloudsSlaveTemplate>emptyList());
>>>>>>> 7301897bfa748ac9d0d056f4e129968b0c634ec4

        j.getInstance().clouds.add(original);
        j.submit(j.createWebClient().goTo("configure").getFormByName("config"));

        j.assertEqualBeans(original, j.getInstance().clouds.getByName("aws-profile"),
                "profile,providerName,cloudCredentialsId,cloudGlobalKeyId,endPointUrl,instanceCap,retentionTime,groupPrefix");

        j.assertEqualBeans(original, JCloudsCloud.getByName("aws-profile"),
                "profile,providerName,cloudCredentialsId,cloudGlobalKeyId,endPointUrl,instanceCap,retentionTime,groupPrefix");
    }

}
