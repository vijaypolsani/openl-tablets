package org.openl.rules.webstudio.web.servlet;

import java.io.File;
import java.io.InputStream;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openl.config.ClassPathConfigLocator;
import org.openl.config.ConfigLocator;
import org.openl.config.ConfigManager;
import org.openl.config.SysConfigManager;
import org.openl.rules.repository.RulesRepositoryFactory;
import org.openl.rules.repository.exceptions.RRepositoryException;

/**
 * Startup listener.
 *
 * @author Aleh Bykhavets
 *
 */
public class StartupListener implements ServletContextListener {

    private final Log log = LogFactory.getLog(StartupListener.class);

    private static final String PROPERTY_TYPE_FILE = "0";
    private static final String PROPERTY_TYPE_STRING = "1";

    private static final String[][] SYSTEM_PROPERTIES = new String[][] {
        { "webstudio.home", PROPERTY_TYPE_FILE },
        { "security.mode",  PROPERTY_TYPE_STRING }
    };

    private class WebConfigLocator extends ConfigLocator {
        private ServletContext context;

        WebConfigLocator(ServletContext context) {
            this.context = context;
        }

        @Override
        public InputStream locate(String fullName) {
            String resPath = "/WEB-INF/conf/";

            if (fullName.startsWith("/")) {
                resPath += fullName.substring(1);
            } else {
                resPath += fullName;
            }

            return context.getResourceAsStream(resPath);
        }

    }

    public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        String name = context.getServletContextName();
        log.info("Starting " + name + "...");

        ConfigManager configManager = new ConfigManager();

        ClassPathConfigLocator defLocator = new ClassPathConfigLocator();
        defLocator.setPriority(0);

        WebConfigLocator webLocator = new WebConfigLocator(context);
        webLocator.setPriority(100);

        configManager.addLocator(webLocator);
        configManager.addLocator(defLocator);

        // Replace system config manager
        SysConfigManager.setConfigManager(configManager);

        initSystemProperties(context);
    }

    private void initSystemProperties(ServletContext context) {
        for (int i = 0; i < SYSTEM_PROPERTIES.length; i++) {
            String propertyName  = SYSTEM_PROPERTIES[i][0];
            String propertyType = SYSTEM_PROPERTIES[i][1];
            String propertyValue = System.getProperty(propertyName);

            if (propertyValue == null) {
                // Set default value
                propertyValue = context.getInitParameter(propertyName);
                System.setProperty(propertyName, propertyValue);
            }

            if (propertyType.equals(PROPERTY_TYPE_FILE)) {
                File propertyFile = new File(
                        StringUtils.defaultString(propertyValue));
                if (!propertyFile.exists()) {
                    log.fatal("You did not set up correctly " + propertyName + " variable: " + propertyValue);
                    return;
                }
            }

            log.info(propertyName + ": " + propertyValue);
        }
    }

    public void contextDestroyed(ServletContextEvent event) {
        try {
            RulesRepositoryFactory.release();
        } catch (RRepositoryException e) {
            log.error("Failed to release rules repository", e);
        }
        String name = event.getServletContext().getServletContextName();
        log.info(name + " is down.");
    }

}
