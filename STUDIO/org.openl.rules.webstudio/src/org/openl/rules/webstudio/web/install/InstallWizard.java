package org.openl.rules.webstudio.web.install;

import static org.openl.rules.webstudio.web.admin.AdministrationSettings.DESIGN_REPOSITORY_CONFIGS;
import static org.openl.rules.webstudio.web.admin.AdministrationSettings.PRODUCTION_REPOSITORY_CONFIGS;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.faces.application.FacesMessage;
import javax.faces.component.UIComponent;
import javax.faces.component.UIInput;
import javax.faces.component.UIViewRoot;
import javax.faces.context.FacesContext;
import javax.faces.event.AjaxBehaviorEvent;
import javax.faces.validator.ValidatorException;
import javax.naming.directory.InvalidSearchFilterException;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.flywaydb.core.api.FlywayException;
import org.hibernate.validator.constraints.NotBlank;
import org.openl.config.InMemoryProperties;
import org.openl.config.PropertiesHolder;
import org.openl.rules.repository.RepositoryMode;
import org.openl.rules.webstudio.security.KeyStoreUtils;
import org.openl.rules.webstudio.util.WebStudioValidationUtils;
import org.openl.rules.webstudio.web.admin.ConnectionProductionRepoController;
import org.openl.rules.webstudio.web.admin.FolderStructureSettings;
import org.openl.rules.webstudio.web.admin.RepositoryEditor;
import org.openl.rules.webstudio.web.admin.RepositoryConfiguration;
import org.openl.rules.webstudio.web.admin.RepositoryValidators;
import org.openl.rules.webstudio.web.repository.RepositoryFactoryProxy;
import org.openl.rules.webstudio.web.util.WebStudioUtils;
import org.openl.rules.workspace.dtr.impl.DesignTimeRepositoryImpl;
import org.openl.spring.env.DynamicPropertySource;
import org.openl.util.StringUtils;
import org.openl.util.db.JDBCDriverRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.PropertyResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.ResourceUtils;
import org.springframework.web.context.annotation.SessionScope;

@Service
@SessionScope
public class InstallWizard implements Serializable {

    private static final String MULTI_USER_MODE = "multi";
    private static final String AD_USER_MODE = "ad";
    private static final String CAS_USER_MODE = "cas";
    private static final String SAML_USER_MODE = "saml";
    private static final String USER_MODE_DEMO = "demo";
    private static final String VIEWERS_GROUP = "Authenticated";

    private final Logger log = LoggerFactory.getLogger(InstallWizard.class);

    private int step;

    private static final String PAGE_PREFIX = "step";
    private static final String PAGE_POSTFIX = "?faces-redirect=true";

    @NotBlank
    private String workingDir;
    private boolean showErrorMessage = false;

    private String userMode = "demo";
    /**
     * TODO: Rename the field and properties to something more clear
     */
    private boolean groupsAreManagedInStudio = true;

    @NotBlank
    private String dbUrl;
    private String dbUsername;
    private String dbPassword;

    private String adDomain;
    private String adUrl;
    private String adUsername;
    private String adPassword;
    private String ldapFilter;

    private CASSettings casSettings;
    private SAMLSettings samlSettings;

    private RepositoryConfiguration designRepositoryConfiguration;
    private RepositoryConfiguration deployConfigRepositoryConfiguration;

    private RepositoryEditor productionRepositoryEditor;
    private RepositoryFactoryProxy productionRepositoryFactoryProxy;

    // Reuse existing controllers
    private ConnectionProductionRepoController connectionProductionRepoController;

    private String defaultGroup;
    private String externalAdmins;

    private final PropertyResolver propertyResolver;
    private final PropertiesHolder properties;

    public InstallWizard(PropertyResolver propertyResolver) {
        this.propertyResolver = propertyResolver;
        this.properties = new InMemoryProperties(propertyResolver);
    }

    private static FacesMessage createErrorMessage(String summary) {
        return new FacesMessage(FacesMessage.SEVERITY_ERROR, summary, null);
    }

    @PostConstruct
    public void init() {
        workingDir = propertyResolver.getProperty(DynamicPropertySource.OPENL_HOME);
    }

    public String start() {
        step = 1;
        return PAGE_PREFIX + step + PAGE_POSTFIX;
    }

    public String reconfigure() throws IOException {
        HashMap<String, String> props = new HashMap<>();
        props.put("webstudio.configured", "false");
        DynamicPropertySource.get().save(props);
        return next();
    }

    public String prev() {
        return PAGE_PREFIX + --step + PAGE_POSTFIX;
    }

    public String next() {
        try {
            // Validations
            if (step == 2) {
                try {
                    RepositoryValidators.validate(designRepositoryConfiguration);
                    RepositoryValidators.validateInstantiation(designRepositoryConfiguration);

                    if (!isUseDesignRepo()) {
                        RepositoryValidators.validate(deployConfigRepositoryConfiguration);
                        RepositoryValidators.validateInstantiation(deployConfigRepositoryConfiguration);
                    }

                    productionRepositoryEditor.validate();
                } catch (Exception e) {
                    Throwable rootCause = ExceptionUtils.getRootCause(e);
                    String message = "Incorrect Design Repository configuration: " + (rootCause == null ? e
                            .getMessage() : rootCause.getMessage());
                    WebStudioUtils.addErrorMessage(message);
                    return null;
                }
            }

            // Go to next step
            ++step;
            if (step == 2) {
                // Get defaults
                String designRepoId = Objects.requireNonNull(propertyResolver.getProperty(DESIGN_REPOSITORY_CONFIGS)).split("\\s*,\\s*")[0];
                designRepositoryConfiguration = new RepositoryConfiguration(designRepoId, properties);
                if (designRepositoryConfiguration.getErrorMessage() != null) {
                    log.error(designRepositoryConfiguration.getErrorMessage());
                }

                deployConfigRepositoryConfiguration = new RepositoryConfiguration("deploy-config",
                    properties);
                if (deployConfigRepositoryConfiguration.getErrorMessage() != null) {
                    log.error(deployConfigRepositoryConfiguration.getErrorMessage());
                }

                initProductionRepositoryEditor();
                userMode = propertyResolver.getProperty("user.mode");
            } else if (step == 3) {
                readDbProperties();
                readAdProperties();
                readCasProperties();
                readSamlProperties();

                switch (userMode) {
                    case AD_USER_MODE:
                        groupsAreManagedInStudio = propertyResolver
                            .getRequiredProperty("security.ad.groups-are-managed-in-studio", Boolean.class);
                        break;
                    case CAS_USER_MODE:
                        groupsAreManagedInStudio = StringUtils
                            .isBlank(propertyResolver.getProperty("security.cas.attribute.groups"));
                        break;
                    case SAML_USER_MODE:
                        groupsAreManagedInStudio = StringUtils
                            .isBlank(propertyResolver.getProperty("security.saml.attribute.groups"));
                        break;
                }

                defaultGroup = propertyResolver.getProperty("security.default-group");
                externalAdmins = propertyResolver.getProperty("security.administrators");

            }
            return PAGE_PREFIX + step + PAGE_POSTFIX;
        } catch (Exception e) {
            log.error("Failed while saving the configuration", e);
            if (e.getCause() instanceof FlywayException) {
                WebStudioUtils.addErrorMessage("Cannot migrate the database. Check the logs for details.");
            } else {
                WebStudioUtils.addErrorMessage("Cannot save the configuration. Check the logs for details.");
            }
            step--;
            return null;
        }
    }

    private void readDbProperties() {
        dbUrl = properties.getProperty("db.url");
        dbUsername = properties.getProperty("db.user");
        dbPassword = properties.getProperty("db.password");
    }

    private void readAdProperties() {
        adDomain = propertyResolver.getProperty("security.ad.domain");
        adUrl = propertyResolver.getProperty("security.ad.server-url");
        ldapFilter = propertyResolver.getProperty("security.ad.search-filter");
    }

    private void readCasProperties() {
        casSettings = new CASSettings(propertyResolver.getProperty("security.cas.app-url"),
            propertyResolver.getProperty("security.cas.cas-server-url-prefix"),
            propertyResolver.getProperty("security.cas.attribute.first-name"),
            propertyResolver.getProperty("security.cas.attribute.last-name"),
            propertyResolver.getProperty("security.cas.attribute.groups"));
    }

    private void readSamlProperties() {
        samlSettings = new SAMLSettings(propertyResolver.getProperty("security.saml.app-url"),
            propertyResolver.getProperty("security.saml.saml-server-metadata-url"),
            propertyResolver.getRequiredProperty("security.saml.request-timeout", Integer.class),
            propertyResolver.getProperty("security.saml.keystore-file-path"),
            propertyResolver.getProperty("security.saml.keystore-password"),
            propertyResolver.getProperty("security.saml.keystore-sp-alias"),
            propertyResolver.getProperty("security.saml.keystore-sp-password"),
            propertyResolver.getProperty("security.saml.attribute.username"),
            propertyResolver.getProperty("security.saml.attribute.first-name"),
            propertyResolver.getProperty("security.saml.attribute.last-name"),
            propertyResolver.getProperty("security.saml.attribute.groups"),
            propertyResolver.getProperty("security.saml.authentication-contexts"),
            propertyResolver.getRequiredProperty("security.saml.local-logout", Boolean.class),
            propertyResolver.getProperty("security.saml.scheme"),
            propertyResolver.getProperty("security.saml.server-name"),
            propertyResolver.getRequiredProperty("security.saml.server-port", Integer.class),
            propertyResolver.getRequiredProperty("security.saml.include-server-port-in-request-url", Boolean.class),
            propertyResolver.getProperty("security.saml.context-path"),
            propertyResolver.getRequiredProperty("security.saml.max-authentication-age", Integer.class),
            propertyResolver.getRequiredProperty("security.saml.metadata-trust-check", Boolean.class),
            propertyResolver.getRequiredProperty("security.saml.is-app-after-balancer", Boolean.class),
            propertyResolver.getProperty("security.saml.server-certificate"),
            propertyResolver.getProperty("security.saml.server-key-alias"));
    }

    public String finish() {
        try {
            if (MULTI_USER_MODE.equals(userMode)) {
                setProductionDbProperties();
            } else {
                if (AD_USER_MODE.equals(userMode)) {

                    properties.setProperty("security.ad.domain", adDomain);
                    properties.setProperty("security.ad.server-url", adUrl);
                    properties.setProperty("security.ad.search-filter", ldapFilter);
                    properties.setProperty("security.ad.groups-are-managed-in-studio", groupsAreManagedInStudio);
                } else if (CAS_USER_MODE.equals(userMode)) {
                    properties.setProperty("security.cas.app-url", casSettings.getWebStudioUrl());
                    properties.setProperty("security.cas.cas-server-url-prefix", casSettings.getCasServerUrl());
                    properties.setProperty("security.cas.attribute.first-name", casSettings.getFirstNameAttribute());
                    properties.setProperty("security.cas.attribute.last-name", casSettings.getSecondNameAttribute());
                    properties.setProperty("security.cas.attribute.groups", casSettings.getGroupsAttribute());
                } else if (SAML_USER_MODE.equals(userMode)) {
                    String serverCertificate = samlSettings.getServerCertificate();
                    if (StringUtils.isNotBlank(serverCertificate)) {
                        importCertificateToKeystore(serverCertificate);
                    }

                    properties.setProperty("security.saml.app-url", samlSettings.getWebStudioUrl());
                    properties.setProperty("security.saml.saml-server-metadata-url",
                        samlSettings.getSamlServerMetadataUrl());
                    properties.setProperty("security.saml.request-timeout", samlSettings.getRequestTimeout());
                    properties.setProperty("security.saml.keystore-file-path", samlSettings.getKeystoreFilePath());
                    properties.setProperty("security.saml.keystore-password", samlSettings.getKeystorePassword());
                    properties.setProperty("security.saml.keystore-sp-alias", samlSettings.getKeystoreSpAlias());
                    properties.setProperty("security.saml.keystore-sp-password", samlSettings.getKeystoreSpPassword());
                    properties.setProperty("security.saml.attribute.username", samlSettings.getUsernameAttribute());
                    properties.setProperty("security.saml.attribute.first-name", samlSettings.getFirstNameAttribute());
                    properties.setProperty("security.saml.attribute.last-name", samlSettings.getSecondNameAttribute());
                    properties.setProperty("security.saml.attribute.groups", samlSettings.getGroupsAttribute());
                    properties.setProperty("security.saml.authentication-contexts",
                        samlSettings.getAuthenticationContexts());
                    properties.setProperty("security.saml.local-logout", samlSettings.isLocalLogout());

                    properties.setProperty("security.saml.scheme", samlSettings.getSamlScheme());
                    properties.setProperty("security.saml.server-name", samlSettings.getSamlServerName());
                    properties.setProperty("security.saml.server-port", samlSettings.getServerPort());
                    properties.setProperty("security.saml.include-server-port-in-request-url",
                        samlSettings.isIncludeServerPortInRequestUrl());
                    properties.setProperty("security.saml.context-path", samlSettings.getContextPath());
                    properties.setProperty("security.saml.max-authentication-age",
                        samlSettings.getMaxAuthenticationAge());
                    properties.setProperty("security.saml.metadata-trust-check", samlSettings.isMetadataTrustCheck());
                    properties.setProperty("security.saml.is-app-after-balancer", samlSettings.isAppAfterBalancer());
                    properties.setProperty("security.saml.server-certificate", serverCertificate);
                    properties.setProperty("security.saml.server-public-alias", samlSettings.getServerKeyAlias());
                }
            }

            productionRepositoryEditor.save();

            properties.setProperty("user.mode", userMode);
            properties.setProperty("security.default-group", defaultGroup);
            properties.setProperty("security.administrators", externalAdmins);


            designRepositoryConfiguration.commit();
            if (!isUseDesignRepo()) {
                deployConfigRepositoryConfiguration.commit();
            }
            properties.setProperty("webstudio.configured", true);
            DynamicPropertySource.get().save(properties.getConfig());

            destroyRepositoryObjects();

            FacesContext.getCurrentInstance()
                .getExternalContext()
                .redirect(WebStudioUtils.getExternalContext().getRequestContextPath() + "/");

            return "/";
        } catch (Exception e) {
            log.error("Failed while saving the configuration", e);
            if (e.getCause() instanceof FlywayException) {
                WebStudioUtils.addErrorMessage("Cannot migrate the database. Check the logs for details.");
            } else {
                WebStudioUtils.addErrorMessage("Cannot save the configuration. Check the logs for details.");
            }
            return null;
        }
    }

    private void importCertificateToKeystore(String certificate) {
        String keystorePassword = samlSettings.getKeystorePassword();

        X509Certificate cert;
        try {
            cert = KeyStoreUtils.generateCertificate(certificate);
        } catch (CertificateException e) {
            throw new IllegalStateException("The entered certificate isn't valid", e);
        }
        File keystore;
        KeyStore ks;
        try {
            keystore = ResourceUtils.getFile(samlSettings.getKeystoreFilePath());
            ks = KeyStoreUtils.loadKeyStore(keystore, keystorePassword);
        } catch (KeyStoreException | CertificateException | NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("The keystore of the application isn't valid", e);
        }

        try {
            String certificateAlias = samlSettings.getServerKeyAlias();
            ks.setCertificateEntry(certificateAlias, cert);
            KeyStoreUtils.saveKeyStore(keystore, ks, keystorePassword);
        } catch (KeyStoreException | IOException | CertificateException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("The keystore of the application can't be saved", e);
        }

    }

    private void setProductionDbProperties() {
        properties.setProperty("db.url", dbUrl);
        properties.setProperty("db.user", dbUsername);
        properties.setProperty("db.password", dbPassword);
    }

    /**
     * Methods tests connection to DB. Depending on the SQL error code corresponding validate exception will be thrown
     * SQL errors loading from sql-errors.properties.
     */

    /*
     * If a new database is added to the project, just add new sql error into the file sql-errors.properties
     */
    private void testDBConnection(String url, String login, String password) {
        Connection conn;

        try {
            JDBCDriverRegister.registerDrivers();
            if (StringUtils.isBlank(login)) {
                conn = DriverManager.getConnection(url);
            } else {
                conn = DriverManager.getConnection(url, login, password);
            }
        } catch (SQLException sqle) {
            int errorCode = sqle.getErrorCode();
            log.error("Code: {}. {}.", errorCode, sqle.getMessage(), sqle);
            final String SQL_ERRORS_FILE_PATH = "/sql-errors.properties";
            String errorMessage = null;
            try {
                Properties properties = new Properties();
                properties.load(getClass().getResourceAsStream(SQL_ERRORS_FILE_PATH));
                errorMessage = properties.getProperty(Integer.toString(errorCode));
            } catch (Exception e) {
                log.error("Cannot to load {} file.", SQL_ERRORS_FILE_PATH, e);
            }
            if (errorMessage == null) {
                errorMessage = "Incorrect database URL, login or password.";
            }

            throw new ValidatorException(createErrorMessage(errorMessage));
        }

        try {
            conn.close();
        } catch (SQLException e) {
            log.error(e.getMessage(), e);
        }
    }

    public void dbValidator(FacesContext context, UIComponent toValidate, Object value) {
        String dbPasswordString = (String) value;

        if (!USER_MODE_DEMO.equals(userMode)) {
            if (StringUtils.isEmpty(dbUrl)) {
                throw new ValidatorException(createErrorMessage("Database URL cannot be blank."));
            } else {
                if (StringUtils.isNotEmpty(dbUsername) && dbUsername.length() > 100) {
                    throw new ValidatorException(createErrorMessage("Username length must be less than 100."));
                }
                testDBConnection(dbUrl, dbUsername, dbPasswordString);
            }
        }

    }

    public void adValidator(FacesContext context, UIComponent toValidate, Object value) {
        UIViewRoot viewRoot = context.getViewRoot();

        String domain = (String) ((UIInput) viewRoot.findComponent("step3Form:adDomain")).getValue();
        String url = (String) ((UIInput) viewRoot.findComponent("step3Form:adUrl")).getValue();
        String username = (String) ((UIInput) viewRoot.findComponent("step3Form:adUsername")).getValue();
        String ldapFilter = (String) ((UIInput) viewRoot.findComponent("step3Form:ldapFilter")).getValue();
        String password = (String) ((UIInput) toValidate).getSubmittedValue();

        if (StringUtils.isBlank(domain)) {
            throw new ValidatorException(createErrorMessage("Active Directory domain cannot be blank."));
        }
        if (StringUtils.isBlank(url)) {
            throw new ValidatorException(createErrorMessage("Active Directory URL cannot be blank."));
        }

        if (!StringUtils.isEmpty(username) && !StringUtils.isEmpty(password)) {
            try {
                ActiveDirectoryLdapAuthenticationProvider ldapAuthenticationProvider = new ActiveDirectoryLdapAuthenticationProvider(
                    domain,
                    url);
                UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(
                    username,
                    password);
                ldapAuthenticationProvider.setSearchFilter(ldapFilter);
                ldapAuthenticationProvider.authenticate(authenticationToken);
            } catch (AuthenticationException e) {
                if (e.getCause() instanceof InvalidSearchFilterException) {
                    String message = "Invalid search filter: " + e.getCause().getMessage();
                    throw new ValidatorException(createErrorMessage(message));
                }
                throw new ValidatorException(createErrorMessage(e.getMessage()));
            } catch (RuntimeException e) {
                throw new ValidatorException(createErrorMessage(getCauseExceptionMessage(e)));
            }
        }
    }

    public void casValidator(FacesContext context, UIComponent toValidate, Object value) {
        UIViewRoot viewRoot = context.getViewRoot();

        String webStudioUrl = (String) ((UIInput) viewRoot.findComponent("step3Form:casWebStudioUrl"))
            .getSubmittedValue();
        String serverUrl = (String) ((UIInput) viewRoot.findComponent("step3Form:casServerUrl")).getSubmittedValue();
        String groupsAttribute = (String) ((UIInput) viewRoot.findComponent("step3Form:casGroupsAttribute"))
            .getSubmittedValue();

        if (StringUtils.isBlank(webStudioUrl)) {
            throw new ValidatorException(createErrorMessage("WebStudio server URL cannot be blank."));
        }

        if (StringUtils.isBlank(serverUrl)) {
            throw new ValidatorException(createErrorMessage("CAS server url cannot be blank."));
        }

        if (!groupsAreManagedInStudio && StringUtils.isBlank(groupsAttribute)) {
            throw new ValidatorException(createErrorMessage(
                "Attribute for Groups cannot be blank or Internal User Management must be selected."));
        }
    }

    public void samlValidator(FacesContext context, UIComponent toValidate, Object value) {
        UIViewRoot viewRoot = context.getViewRoot();

        String webStudioUrl = (String) ((UIInput) viewRoot.findComponent("step3Form:samlWebStudioUrl"))
            .getSubmittedValue();
        String serverUrl = (String) ((UIInput) viewRoot.findComponent("step3Form:samlServerUrl")).getSubmittedValue();
        String requestTimeout = (String) ((UIInput) viewRoot.findComponent("step3Form:samlRequestTimeout"))
            .getSubmittedValue();
        String maxAuthenticationAge = (String) ((UIInput) viewRoot.findComponent("step3Form:samlMaxAuthenticationAge"))
            .getSubmittedValue();
        String groupsAttribute = (String) ((UIInput) viewRoot.findComponent("step3Form:samlGroupsAttribute"))
            .getSubmittedValue();
        String publicServerCert = (String) ((UIInput) viewRoot.findComponent("step3Form:samlServerCertificate"))
            .getSubmittedValue();
        boolean isAppAfterBalancer = (boolean) ((UIInput) viewRoot.findComponent("step3Form:samlIsAppAfterBalancer"))
            .getSubmittedValue();
        String samlScheme = (String) ((UIInput) viewRoot.findComponent("step3Form:samlScheme")).getSubmittedValue();
        String samlServerName = (String) ((UIInput) viewRoot.findComponent("step3Form:samlServerName"))
            .getSubmittedValue();
        String samlServerPort = (String) ((UIInput) viewRoot.findComponent("step3Form:samlServerPort"))
            .getSubmittedValue();
        String samlContextPath = (String) ((UIInput) viewRoot.findComponent("step3Form:samlContextPath"))
            .getSubmittedValue();

        if (StringUtils.isBlank(webStudioUrl)) {
            throw new ValidatorException(createErrorMessage("WebStudio server URL cannot be blank."));
        }

        if (StringUtils.isBlank(serverUrl)) {
            throw new ValidatorException(createErrorMessage("SAML server metadata url cannot be blank."));
        }

        if (StringUtils.isBlank(requestTimeout)) {
            throw new ValidatorException(createErrorMessage("Request timeout cannot be blank."));
        }

        if (StringUtils.isBlank(maxAuthenticationAge)) {
            throw new ValidatorException(createErrorMessage("SAML max authentication age cannot be blank."));
        }

        if (StringUtils.isBlank(publicServerCert)) {
            throw new ValidatorException(createErrorMessage("SAML server certificate cannot be blank"));
        } else {
            try {
                X509Certificate cert = KeyStoreUtils.generateCertificate(publicServerCert);
                cert.checkValidity();
            } catch (Exception e) {
                throw new ValidatorException(createErrorMessage("Entered SAML server certificate is not valid."));
            }
        }

        if (!groupsAreManagedInStudio && StringUtils.isBlank(groupsAttribute)) {
            throw new ValidatorException(createErrorMessage(
                "Attribute for Groups cannot be blank or Internal User Management must be selected."));
        }

        if (isAppAfterBalancer) {
            if (StringUtils.isBlank(samlScheme)) {
                throw new ValidatorException(createErrorMessage("SAML scheme cannot be blank."));
            }
            if (StringUtils.isBlank(samlServerName)) {
                throw new ValidatorException(createErrorMessage("SAML server name cannot be blank."));
            }

            if (StringUtils.isBlank(samlServerPort)) {
                throw new ValidatorException(createErrorMessage("SAML server port cannot be blank."));
            }

            if (StringUtils.isBlank(samlContextPath)) {
                throw new ValidatorException(createErrorMessage("SAML context path cannot be blank."));
            }
        }
    }

    public void externalAdminsValidator(FacesContext context, UIComponent toValidate, Object value) {
        String admins = (String) value;
        if (StringUtils.isBlank(admins) || admins.trim().equals(",")) {
            throw new ValidatorException(createErrorMessage("Administrators field must not be empty."));
        }

        String[] allAdmins = StringUtils.split(admins, ',');
        for (String admin : allAdmins) {
            if (admin.length() > 50) {
                throw new ValidatorException(createErrorMessage("Administrator username length must be less than 50."));
            }
        }
    }

    private String getCauseExceptionMessage(Exception e) {
        String errorMessage = e.getMessage();
        Throwable cause = e.getCause();

        if (cause != null) {
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }

            if (cause.getMessage() != null) {
                errorMessage = cause.getMessage();
            }
        }

        return errorMessage;
    }

    /**
     * Validates WebStudio working directory for write access. If specified folder is not writable the validation error
     * will appears
     */
    public void workingDirValidator(FacesContext context, UIComponent toValidate, Object value) {
        WebStudioValidationUtils.directoryValidator(value, "WebStudio working directory");
    }

    /**
     * Ajax event for changing database url.
     *
     * @param e AjaxBehavior event
     */
    public void urlChanged(AjaxBehaviorEvent e) {
        UIInput uiInput = (UIInput) e.getComponent();
        String url = uiInput.getValue().toString();
        setDbUrl(url);
    }

    /**
     * Ajax event for changing database username
     *
     * @param e AjaxBehavior event
     */
    public void usernameChanged(AjaxBehaviorEvent e) {
        UIInput uiInput = (UIInput) e.getComponent();
        String username = uiInput.getValue().toString();
        setDbUsername(username);
    }

    public void groupsAreManagedInStudioChanged(AjaxBehaviorEvent e) {
        UIInput uiInput = (UIInput) e.getComponent();
        groupsAreManagedInStudio = Boolean.parseBoolean(uiInput.getValue().toString());
    }

    public int getStep() {
        return step;
    }

    public void setStep(int step) {
        this.step = step;
    }

    public String getWorkingDir() {
        return workingDir;
    }

    public void setWorkingDir(String workingDir) {
        this.workingDir = workingDir;
        // Other configurations depend on this property
        DynamicPropertySource.get().setOpenLHomeDir(this.workingDir);
    }

    public String getGroupsAreManagedInStudio() {
        return "" + groupsAreManagedInStudio;
    }

    public void setGroupsAreManagedInStudio(String groupsAreManagedInStudio) {
        this.groupsAreManagedInStudio = Boolean.parseBoolean(groupsAreManagedInStudio);
    }

    public void setAllowAccessToNewUsers(Boolean allowAccessToNewUsers) {
        defaultGroup = Boolean.TRUE.equals(allowAccessToNewUsers) ? VIEWERS_GROUP : "";
    }

    public Boolean getAllowAccessToNewUsers() {
        return StringUtils.isNotBlank(defaultGroup);
    }

    public void setExternalAdmins(String externalAdmins) {
        this.externalAdmins = externalAdmins;
    }

    public String getExternalAdmins() {
        return externalAdmins;
    }

    public String getUserMode() {
        return userMode;
    }

    public void setUserMode(String userMode) {
        this.userMode = userMode;
    }

    public String getDbUrl() {
        return dbUrl;
    }

    public void setDbUrl(String dbUrl) {
        this.dbUrl = dbUrl;
    }

    public String getDbUsername() {
        return dbUsername;
    }

    public void setDbUsername(String dbUsername) {
        this.dbUsername = dbUsername;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public void setDbPassword(String dbPassword) {
        this.dbPassword = dbPassword;
    }

    public String getAdDomain() {
        return adDomain;
    }

    public void setAdDomain(String adDomain) {
        this.adDomain = adDomain;
    }

    public String getAdUrl() {
        return adUrl;
    }

    public void setAdUrl(String adUrl) {
        this.adUrl = adUrl;
    }

    public String getAdUsername() {
        return adUsername;
    }

    public void setAdUsername(String adUsername) {
        this.adUsername = adUsername;
    }

    public String getLdapFilter() {
        return ldapFilter;
    }

    public void setLdapFilter(String ldapFilter) {
        this.ldapFilter = ldapFilter;
    }

    public String getAdPassword() {
        return adPassword;
    }

    public void setAdPassword(String adPassword) {
        this.adPassword = adPassword;
    }

    public CASSettings getCasSettings() {
        return casSettings;
    }

    public SAMLSettings getSamlSettings() {
        return samlSettings;
    }

    public boolean isShowErrorMessage() {
        return showErrorMessage;
    }

    public void setShowErrorMessage(boolean showErrorMessage) {
        this.showErrorMessage = showErrorMessage;
    }

    public String getFolderSeparator() {

        return File.separator;
    }

    public RepositoryConfiguration getDesignRepositoryConfiguration() {
        return designRepositoryConfiguration;
    }

    public RepositoryConfiguration getDeployConfigRepositoryConfiguration() {
        return deployConfigRepositoryConfiguration;
    }

    public boolean isUseDesignRepo() {
        return StringUtils.isNotBlank(properties.getProperty(DesignTimeRepositoryImpl.USE_REPOSITORY_FOR_DEPLOY_CONFIG));
    }

    public void setUseDesignRepo(boolean useDesignRepo) {
        // TODO: We should point specific design repository
        String designRepoId = Objects.requireNonNull(propertyResolver.getProperty(DESIGN_REPOSITORY_CONFIGS)).split("\\s*,\\s*")[0];
        properties.setProperty(DesignTimeRepositoryImpl.USE_REPOSITORY_FOR_DEPLOY_CONFIG, useDesignRepo ? designRepoId : null);
    }

    public FolderStructureSettings getDesignFolderStructure() {
        return new FolderStructureSettings(designRepositoryConfiguration);
    }

    public FolderStructureSettings getDeployConfigFolderStructure() {
        return new FolderStructureSettings(deployConfigRepositoryConfiguration);
    }

    public List<RepositoryConfiguration> getProductionRepositoryConfigurations() {
        return productionRepositoryEditor.getRepositoryConfigurations();
    }

    public void deleteProductionRepository(String configName) {
        try {
            productionRepositoryEditor.deleteRepository(configName);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            WebStudioUtils.addErrorMessage(e.getMessage());
        }
    }

    public ConnectionProductionRepoController getConnectionProductionRepoController() {
        return connectionProductionRepoController;
    }

    @PreDestroy
    public void destroy() {
        destroyRepositoryObjects();
    }

    private void initProductionRepositoryEditor() {
        destroyRepositoryObjects();

        productionRepositoryFactoryProxy = new RepositoryFactoryProxy(propertyResolver, RepositoryMode.PRODUCTION);
        productionRepositoryEditor = new RepositoryEditor(productionRepositoryFactoryProxy, properties);

        connectionProductionRepoController = new ConnectionProductionRepoController();
        connectionProductionRepoController.setProperties(properties, PRODUCTION_REPOSITORY_CONFIGS);
        connectionProductionRepoController.setProductionRepositoryFactoryProxy(productionRepositoryFactoryProxy);
        connectionProductionRepoController
            .setProductionRepositoryConfigurations(getProductionRepositoryConfigurations());
        connectionProductionRepoController.clearForm();
    }

    private void destroyRepositoryObjects() {
        if (productionRepositoryFactoryProxy != null) {
            try {
                productionRepositoryFactoryProxy.destroy();
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
            productionRepositoryFactoryProxy = null;
        }
    }
}
