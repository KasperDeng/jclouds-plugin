package jenkins.plugins.jclouds.compute;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import hudson.model.User;
import hudson.tasks.Mailer;
import hudson.util.ReflectionUtils;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;

/**
 * Class for some jenkins utilities by reflection
 */
public class JCloudsUtility {
    private static final Logger LOGGER = Logger.getLogger(JCloudsUtility.class.getName());

    /**
     * Check current user whether is administrator
     */
    public static Boolean isAdmin() {
        return Jenkins.getInstance().hasPermission(Jenkins.ADMINISTER);
    }

    /**
     * Get current user name
     */
    public static String getCurrentUserName() {
        User user = Jenkins.getInstance().getMe();
        return user.getFullName().toLowerCase();
    }

    /**
     * Save jenkins nodes setting to the config.xml
     */
    public static void saveNodesSettingToConfig() {
        try {
            // renew the node list
            Jenkins.getInstance().setNodes(Jenkins.getInstance().getNodes());
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed saving to config file", e);
        }
    }

    /**
     * Save jenkins setting to the config.xml
     */
    public static void saveSettingToConfig() {
        try {
            Jenkins.getInstance().save();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed saving to config file", e);
        }
    }

    public static void updateComputerList() {
        Method updateComputerList = ReflectionUtils.findMethod(Jenkins.getInstance().getClass(), "updateComputerList", null);
        updateComputerList.setAccessible(true);
        try {
            updateComputerList.invoke(Jenkins.getInstance(), null);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    public static void sendEmail(String emailAddress, String emailSubject, StringBuilder emailContent) {
        String charset = "UTF-8";
        MimeMessage mail = new MimeMessage(Jenkins.getInstance().getDescriptorByType(Mailer.DescriptorImpl.class).
                createSession());
        String address = new StringTokenizer(emailAddress).nextToken();
        try {
            mail.setContent("", "text/plain");
            mail.setFrom(Mailer.StringToAddress(JenkinsLocationConfiguration.get().getAdminAddress(), charset));
            mail.setSentDate(new Date());
            Set<InternetAddress> rcp = new LinkedHashSet<InternetAddress>();
            rcp.add(Mailer.StringToAddress(address, charset));
            mail.setRecipients(Message.RecipientType.TO, rcp.toArray(new InternetAddress[rcp.size()]));
            mail.setSubject(emailSubject, charset);
            mail.setText(emailContent.toString(), charset);
            Transport.send(mail);
            LOGGER.log(Level.INFO, "Email: " +  emailSubject + " is sent to " + address);
        } catch (AddressException e) {
            LOGGER.log(Level.SEVERE, "Unable to send to address: " + address + '\n'+ e);
        } catch (MessagingException e) {
            LOGGER.log(Level.SEVERE, "Unable to send to address: " + address + '\n' + e);
        } catch (UnsupportedEncodingException e) {
            LOGGER.log(Level.SEVERE, "Unable to send to address: " + address + '\n' + e);
        }
    }

    public static void setSlaveDescription(JCloudsSlave jcloudsSlave, String description) {
        Field nodeDescription = ReflectionUtils.findField(jcloudsSlave.getClass(), "description");
        if (nodeDescription != null) {
            nodeDescription.setAccessible(true);
            try {
                nodeDescription.set(jcloudsSlave, description);
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }

    }
}
