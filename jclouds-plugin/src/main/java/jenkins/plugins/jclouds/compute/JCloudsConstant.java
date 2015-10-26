package jenkins.plugins.jclouds.compute;

public class JCloudsConstant {
    public static final int MIN_IN_DAY = 1440;
    public static final int MILLISEC_IN_SECOND = 1000;
    public static final int MILLISEC_IN_MIN = MILLISEC_IN_SECOND * 60;
    public static final int MILLISEC_IN_DAY = MIN_IN_DAY * MILLISEC_IN_MIN;
    public static final String OFFLINE_LABEL = "OfflineOSInstance";
}
