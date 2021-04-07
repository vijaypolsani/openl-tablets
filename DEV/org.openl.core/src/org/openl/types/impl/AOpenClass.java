/*
 * Created on Jun 24, 2003
 *
 * Developed by Intelligent ChoicePoint Inc. 2003
 */

package org.openl.types.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.openl.binding.exception.AmbiguousFieldException;
import org.openl.binding.exception.DuplicatedMethodException;
import org.openl.domain.IDomain;
import org.openl.domain.IType;
import org.openl.meta.IMetaInfo;
import org.openl.types.IOpenClass;
import org.openl.types.IOpenField;
import org.openl.types.IOpenMethod;
import org.openl.types.StaticOpenClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author snshor
 *
 */
public abstract class AOpenClass implements IOpenClass {

    private volatile StaticOpenClass staticOpenClass;

    private static final Logger LOG = LoggerFactory.getLogger(AOpenClass.class);

    protected static final Map<MethodKey, IOpenMethod> STUB = Collections.emptyMap();
    private IOpenField indexField;

    protected IMetaInfo xlsMetaInfo;
    protected volatile Map<String, IOpenField> uniqueLowerCaseFieldMap;

    protected volatile Map<String, List<IOpenField>> nonUniqueLowerCaseFieldMap;

    private void addFieldToLowerCaseMaps(IOpenField f,
            Map<String, IOpenField> uniqueLCaseFieldMap,
            Map<String, List<IOpenField>> nonUniqueLCaseFieldMap) {
        String lname = f.getName().toLowerCase().replace(" ", "");
        if (uniqueLCaseFieldMap.containsKey(lname)) {
            List<IOpenField> ff = new ArrayList<>(2);
            ff.add(uniqueLCaseFieldMap.get(lname));
            ff.add(f);
            nonUniqueLCaseFieldMap.put(lname, ff);
            uniqueLCaseFieldMap.remove(lname);
        } else if (nonUniqueLCaseFieldMap.containsKey(lname)) {
            nonUniqueLCaseFieldMap.get(lname).add(f);
        } else {
            uniqueLCaseFieldMap.put(lname, f);
        }
    }

    protected void addFieldToLowerCaseMap(IOpenField f) {
        if (uniqueLowerCaseFieldMap == null || nonUniqueLowerCaseFieldMap == null) {
            return;
        }
        addFieldToLowerCaseMaps(f, getUniqueLowerCaseFieldMap(), getNonUniqueLowerCaseFieldMap());
    }

    protected abstract Map<String, IOpenField> fieldMap();

    @Override
    public Collection<IOpenField> getFields() {
        Collection<IOpenField> fields = new ArrayList<>();
        Iterable<IOpenClass> superClasses = superClasses();
        for (IOpenClass superClass : superClasses) {
            fields.addAll(superClass.getFields());
        }
        fields.addAll(fieldMap().values());
        return fields;
    }

    @Override
    public Collection<IOpenField> getDeclaredFields() {
        return Collections.unmodifiableCollection(fieldMap().values());
    }

    public static IOpenClass getArrayType(IOpenClass openClass, int dim) {
        if (dim > 0) {
            IOpenClass arrayType = ComponentTypeArrayOpenClass.createComponentTypeArrayOpenClass(openClass, dim);
            if (openClass.getDomain() != null) {
                StringBuilder domainOpenClassName = new StringBuilder(openClass.getName());
                for (int j = 0; j < dim; j++) {
                    domainOpenClassName.append("[]");
                }
                return new DomainOpenClass(domainOpenClassName.toString(), arrayType, openClass.getDomain(), null);
            } else {
                return arrayType;
            }
        }
        throw new IllegalArgumentException("Expected positive number for array dimension");
    }

    @Override
    public IOpenClass getArrayType(int dim) {
        return getArrayType(this, dim);
    }

    @Override
    public IDomain<?> getDomain() {
        return null;
    }

    @Override
    public IOpenField getField(String fname) {
        try {
            return getField(fname, true);
        } catch (AmbiguousFieldException e) {
            LOG.debug("Ignored error: ", e);
            return null;
        }
    }

    @Override
    public IOpenField getField(String fname, boolean strictMatch) throws AmbiguousFieldException {

        IOpenField f;
        if (strictMatch) {

            Map<String, IOpenField> m = fieldMap();

            f = m == null ? null : m.get(fname);
            if (f != null) {
                return f;
            } else {
                return searchFieldFromSuperClass(fname, strictMatch);
            }
        }

        String lfname = fname.toLowerCase();

        f = getUniqueLowerCaseFieldMap().get(lfname);
        if (f != null) {
            return f;
        }

        List<IOpenField> ff = getNonUniqueLowerCaseFieldMap().get(lfname);

        if (ff != null) {
            throw new AmbiguousFieldException(fname, ff);
        }

        return searchFieldFromSuperClass(fname, strictMatch);
    }

    private IOpenField searchFieldFromSuperClass(String fname, boolean strictMatch) throws AmbiguousFieldException {
        IOpenField f;
        Iterable<IOpenClass> superClasses = superClasses();
        for (IOpenClass superClass : superClasses) {
            f = superClass.getField(fname, strictMatch);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    @Override
    public IOpenField getIndexField() {
        return indexField;
    }

    @Override
    public IOpenMethod getConstructor(IOpenClass[] params) {
        Map<MethodKey, IOpenMethod> m = constructorMap();
        MethodKey methodKey = new MethodKey(params);
        return m.get(methodKey);
    }

    @Override
    public IMetaInfo getMetaInfo() {
        return xlsMetaInfo;
    }

    @Override
    public IOpenMethod getMethod(String name, IOpenClass[] classes) {

        IOpenMethod method = getDeclaredMethod(name, classes);

        // If method is not found try to find it in parent classes.
        //
        if (method == null) {
            Iterator<IOpenClass> superClasses = superClasses().iterator();

            while (method == null && superClasses.hasNext()) {
                method = superClasses.next().getMethod(name, classes);
            }
        }

        return method;
    }

    private Map<String, List<IOpenField>> getNonUniqueLowerCaseFieldMap() {
        if (uniqueLowerCaseFieldMap == null || nonUniqueLowerCaseFieldMap == null) {
            makeLowerCaseMaps();
        }
        return nonUniqueLowerCaseFieldMap;
    }

    private Map<String, IOpenField> getUniqueLowerCaseFieldMap() {
        if (uniqueLowerCaseFieldMap == null || nonUniqueLowerCaseFieldMap == null) {
            makeLowerCaseMaps();
        }
        return uniqueLowerCaseFieldMap;
    }

    @Override
    public IOpenField getVar(String name, boolean strictMatch) throws AmbiguousFieldException {
        return getField(name, strictMatch);
    }

    @Override
    public boolean isAbstract() {
        return false;
    }

    @Override
    public boolean isAssignableFrom(IType type) {
        if (type instanceof IOpenClass) {
            return isAssignableFrom((IOpenClass) type);
        }
        return false;
    }

    @Override
    public boolean isSimple() {
        return false;
    }

    @Override
    public boolean isArray() {
        if (getInstanceClass() != null) {
            return getInstanceClass().isArray();
        }
        return false;
    }

    @Override
    public IOpenClass getComponentClass() {
        // Default implementation. Open classes that can be represented as
        // arrays, should override this method.
        //
        return null;
    }

    private synchronized void makeLowerCaseMaps() {
        if (uniqueLowerCaseFieldMap == null || nonUniqueLowerCaseFieldMap == null) {
            Map<String, IOpenField> uniqueLCaseFieldMap = new HashMap<>();
            Map<String, List<IOpenField>> nonUniqueLCaseFieldMap = new HashMap<>();
            for (IOpenField field : getFields()) {
                addFieldToLowerCaseMaps(field, uniqueLCaseFieldMap, nonUniqueLCaseFieldMap);
            }
            this.uniqueLowerCaseFieldMap = uniqueLCaseFieldMap;
            this.nonUniqueLowerCaseFieldMap = nonUniqueLCaseFieldMap;
        }
    }

    private volatile Map<MethodKey, IOpenMethod> methodMap;
    private volatile Map<MethodKey, IOpenMethod> constructorMap;

    private Map<MethodKey, IOpenMethod> methodMap() {
        if (methodMap == null) {
            synchronized (this) {
                if (methodMap == null) {
                    methodMap = initMethodMap();
                }
            }
        }
        return methodMap;
    }

    private Map<MethodKey, IOpenMethod> constructorMap() {
        if (constructorMap == null) {
            synchronized (this) {
                if (constructorMap == null) {
                    constructorMap = initConstructorMap();
                }
            }
        }
        return constructorMap;
    }

    protected Map<MethodKey, IOpenMethod> initMethodMap() {
        return STUB;
    }

    protected Map<MethodKey, IOpenMethod> initConstructorMap() {
        return STUB;
    }

    private IOpenMethod putMethod(IOpenMethod method) {
        if (methodMap == null || methodMap == STUB) {
            synchronized (this) {
                if (methodMap == null) {
                    methodMap = initMethodMap();
                }
                if (methodMap == STUB) {
                    methodMap = new HashMap<>(4);
                }
            }
        }
        MethodKey key = new MethodKey(method);
        return methodMap.put(key, method);
    }

    public void addMethod(IOpenMethod method) throws DuplicatedMethodException {
        final IOpenMethod existMethod = putMethod(method);
        if (existMethod != null) {
            throw new DuplicatedMethodException(String
                .format("Method '%s' is already defined in class '%s'", method, getName()), existMethod, method);
        }
        invalidateInternalData();
    }

    protected void overrideMethod(IOpenMethod method) {
        MethodKey key = new MethodKey(method);
        final IOpenMethod existMethod = putMethod(method);
        if (existMethod == null) {
            throw new IllegalStateException(
                String.format("Method '%s' is absent to override in class '%s'", key, getName()));
        }
        invalidateInternalData();
    }

    protected void invalidateInternalData() {
        allMethodsCacheInvalidated = true;
        allMethodNamesMapInvalidated = true;
        allConstructorNamesMapInvalidated = true;
        constructorMap = null;
    }

    private Collection<IOpenMethod> allMethodsCache;
    private volatile boolean allMethodsCacheInvalidated = true;

    @Override
    public final Collection<IOpenMethod> getMethods() {
        if (allMethodsCacheInvalidated) {
            synchronized (this) {
                if (allMethodNamesMapInvalidated) {
                    allMethodsCache = buildAllMethods();
                    allMethodsCacheInvalidated = false;
                }
            }
        }
        return allMethodsCache;
    }

    private Collection<IOpenMethod> buildAllMethods() {
        Map<MethodKey, IOpenMethod> methods = new HashMap<>();
        Iterable<IOpenClass> superClasses = superClasses();
        for (IOpenClass superClass : superClasses) {
            for (IOpenMethod method : superClass.getMethods()) {
                methods.put(new MethodKey(method), method);
            }
        }
        final Map<MethodKey, IOpenMethod> m = methodMap();
        if (m != null) {
            methods.putAll(m);
        }
        if (methods.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableCollection(methods.values());
    }

    public IOpenMethod getDeclaredMethod(String name, IOpenClass[] classes) {
        Map<MethodKey, IOpenMethod> m = methodMap();
        MethodKey methodKey = new MethodKey(name, classes);
        return m.get(methodKey);
    }

    @Override
    public Collection<IOpenMethod> getDeclaredMethods() {
        return methodMap().values();
    }

    @Override
    public Object nullObject() {
        return null;
    }

    public void setIndexField(IOpenField field) {
        this.indexField = field;
    }

    @Override
    public void setMetaInfo(IMetaInfo metaInfo) {
        this.xlsMetaInfo = metaInfo;
    }

    @Override
    public String toString() {
        return getName();
    }

    /**
     * Default implementation.
     *
     * @param type IOpenClass instance
     */
    @Override
    public void addType(IOpenClass type) {
    }

    @Override
    public IOpenClass findType(String name) {
        return null;
    }

    /**
     * Default implementation. Always returns <code>null</code>.
     *
     */
    @Override
    public Collection<IOpenClass> getTypes() {
        // Default implementation.
        // To do nothing. Not everyone has internal types.
        return Collections.emptyList();
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getName());
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof IOpenClass)) {
            return false;
        }
        return Objects.equals(getName(), ((IOpenClass) obj).getName());
    }

    private Map<String, List<IOpenMethod>> allMethodNamesMap;

    private volatile boolean allMethodNamesMapInvalidated = true;

    private Collection<IOpenMethod> allConstructors;

    private volatile boolean allConstructorNamesMapInvalidated = true;

    @Override
    public final Iterable<IOpenMethod> methods(String name) {
        if (allMethodNamesMapInvalidated) {
            synchronized (this) {
                if (allMethodNamesMapInvalidated) {
                    allMethodNamesMap = buildMethodNameMap(getMethods());
                    allMethodNamesMapInvalidated = false;
                }
            }
        }
        List<IOpenMethod> found = allMethodNamesMap.get(name);
        return found == null ? Collections.emptyList() : Collections.unmodifiableList(found);
    }

    @Override
    public final Iterable<IOpenMethod> constructors() {
        if (allConstructorNamesMapInvalidated) {
            synchronized (this) {
                if (allConstructorNamesMapInvalidated) {
                    allConstructors = Collections.unmodifiableCollection(constructorMap().values());
                    allConstructorNamesMapInvalidated = false;
                }
            }
        }
        return allConstructors == null ? Collections.emptyList() : allConstructors;
    }

    public static Map<String, List<IOpenMethod>> buildMethodNameMap(Iterable<IOpenMethod> methods) {
        Map<String, List<IOpenMethod>> res = new HashMap<>();

        for (IOpenMethod m : methods) {
            String name = m.getName();
            List<IOpenMethod> list = res.computeIfAbsent(name, e -> new LinkedList<>());
            list.add(m);
        }

        return res;
    }

    @Override
    public boolean isInterface() {
        return false;
    }

    @Override
    public IOpenClass toStaticClass() {
        if (staticOpenClass == null) {
            synchronized (this) {
                if (staticOpenClass == null) {
                    staticOpenClass = new StaticOpenClass(this);
                }
            }
        }
        return staticOpenClass;
    }

    @Override
    public IOpenField getStaticField(String fname) {
        return null;
    }

    @Override
    public Collection<IOpenField> getStaticFields() {
        return null;
    }

    @Override
    public IOpenField getStaticField(String name, boolean strictMatch) {
        return null;
    }

    @Override
    public boolean isStatic() {
        return false;
    }
}
