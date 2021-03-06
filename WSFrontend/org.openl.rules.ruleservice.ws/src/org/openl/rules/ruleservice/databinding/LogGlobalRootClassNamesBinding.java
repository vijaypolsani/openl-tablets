package org.openl.rules.ruleservice.databinding;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.openl.rules.project.model.RulesDeployHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.FactoryBean;

public class LogGlobalRootClassNamesBinding implements FactoryBean<Set<String>> {

    private final Logger log = LoggerFactory.getLogger(LogGlobalRootClassNamesBinding.class);

    private String rootClassNames;

    public void setRootClassNames(String rootClassNames) {
        this.rootClassNames = rootClassNames;
    }

    public String getRootClassNames() {
        return rootClassNames;
    }

    @Override
    public Set<String> getObject() {
        Set<String> ret = new HashSet<>();
        if (rootClassNames == null || rootClassNames.trim().length() == 0) {
            return ret;
        }
        ret.addAll(RulesDeployHelper.splitRootClassNamesBindingClasses(rootClassNames));
        for (String clsName : ret) {
            log.info(
                "Class name '{}' is added to the root class names list that will be used for each deployed service.",
                clsName);
        }
        return Collections.unmodifiableSet(ret);
    }

    @Override
    public Class<?> getObjectType() {
        return Set.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }
}
