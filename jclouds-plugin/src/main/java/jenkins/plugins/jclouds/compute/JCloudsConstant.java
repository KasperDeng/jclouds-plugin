package jenkins.plugins.jclouds.compute;

public class JCloudsConstant {
    public static final int MIN_IN_DAY = 1440;
    public static final int MILLI_SEC_IN_SECOND = 1000;
    public static final int MILLI_SEC_IN_MIN = MILLI_SEC_IN_SECOND * 60;
    public static final int MILLI_SEC_IN_DAY = MIN_IN_DAY * MILLI_SEC_IN_MIN;
    public static final String OFFLINE_LABEL = "OfflineOSInstance";
}
