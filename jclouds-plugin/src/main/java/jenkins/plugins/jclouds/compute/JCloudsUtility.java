package jenkins.plugins.jclouds.compute;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import hudson.util.ReflectionUtils;
import jenkins.model.Jenkins;

/**
 * Class for some jenkins utilities by reflection
 */
public class JCloudsUtility {
    /**
     * Save jenkins setting to the config.xml
     */
    public static void saveSettingToConfig() {
        Method save = ReflectionUtils.findMethod(Jenkins.getInstance().getClass(), "save", null);
        save.setAccessible(true);
        try {
            save.invoke(Jenkins.getInstance(), null);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
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
}
