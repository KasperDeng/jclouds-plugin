package jenkins.plugins.jclouds.compute;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

public final class InstancesToRun extends AbstractDescribableImpl<InstancesToRun> {
    public final String cloudName;
    public final String templateName;
    public final String manualTemplateName;
    public final int count;
    public final boolean suspendOrTerminate;
    public String slavePostAction;

    @DataBoundConstructor
    public InstancesToRun(String cloudName, String templateName, String manualTemplateName, int count,
            boolean suspendOrTerminate, String slavePostAction) {
        this.cloudName = Util.fixEmptyAndTrim(cloudName);
        this.templateName = Util.fixEmptyAndTrim(templateName);
        this.manualTemplateName = Util.fixEmptyAndTrim(manualTemplateName);
        this.count = count;
        this.suspendOrTerminate = suspendOrTerminate;
        this.slavePostAction = slavePostAction;
    }

    public String getActualTemplateName() {
        if (isUsingManualTemplateName()) {
            return manualTemplateName;
        } else {
            return templateName;
        }
    }

    public boolean isUsingManualTemplateName() {
        if (manualTemplateName == null || manualTemplateName.equals("")) {
            return false;
        } else {
            return true;
        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<InstancesToRun> {
        public ListBoxModel doFillCloudNameItems() {
            ListBoxModel m = new ListBoxModel();
            for (String cloudName : JCloudsCloud.getCloudNames()) {
                m.add(cloudName, cloudName);
            }

            return m;
        }

        public ListBoxModel doFillTemplateNameItems(@QueryParameter String cloudName) {
            ListBoxModel m = new ListBoxModel();
            JCloudsCloud c = JCloudsCloud.getByName(cloudName);
            if (c != null) {
                for (JCloudsSlaveTemplate t : c.getTemplates()) {
                    m.add(String.format("%s in cloud %s", t.name, cloudName), t.name);
                }
            }
            return m;
        }

        public FormValidation doCheckCount(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        private String[] getSlavePostAction() {
            return new String[] {
                    InstancePostAction.DESTROY_SLAVE,
                    InstancePostAction.OFFLINE_SLAVE,
                    InstancePostAction.SUSPEND_SLAVE_JOB_DONE,
                    InstancePostAction.SUSPEND_SLAVE_JOB_FAILED,
                    InstancePostAction.SNAPSHOT_SLAVE_JOB_DONE,
                    InstancePostAction.SNAPSHOT_SLAVE_JOB_FAILED,
            };
        }

        public ListBoxModel doFillSlavePostActionItems() {
            ListBoxModel model = new ListBoxModel();
            for (String postAction : getSlavePostAction()) {
                model.add(postAction);
            }
            return model;
        }

        @Override
        public String getDisplayName() {
            return "";
        }
    }
}
