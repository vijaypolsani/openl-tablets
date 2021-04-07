/*
 * Created on Jul 25, 2003
 *
 * Developed by Intelligent ChoicePoint Inc. 2003
 */

package org.openl.rules.lang.xls.types;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;

import org.openl.base.INamedThing;
import org.openl.binding.exception.DuplicatedFieldException;
import org.openl.rules.lang.xls.XlsBinder;
import org.openl.rules.lang.xls.syntax.TableSyntaxNode;
import org.openl.types.IAggregateInfo;
import org.openl.types.IOpenClass;
import org.openl.types.IOpenField;
import org.openl.types.IOpenMember;
import org.openl.types.IOpenMethod;
import org.openl.types.impl.ADynamicClass;
import org.openl.types.impl.DynamicArrayAggregateInfo;
import org.openl.types.impl.MethodKey;
import org.openl.types.impl.ParameterDeclaration;
import org.openl.types.java.JavaOpenClass;
import org.openl.types.java.JavaOpenConstructor;
import org.openl.types.java.JavaOpenMethod;
import org.openl.util.StringUtils;
import org.openl.vm.IRuntimeEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Open class for types represented as datatype table components in openl.
 *
 * @author snshor
 */
public class DatatypeOpenClass extends ADynamicClass {

    private static final Logger LOG = LoggerFactory.getLogger(DatatypeOpenClass.class);

    private IOpenClass superClass;

    private final String javaName;

    private final String packageName;

    private TableSyntaxNode tableSyntaxNode;

    private byte[] bytecode;

    /**
     * User has a possibility to set the package (by table properties mechanism) where he wants to generate datatype
     * beans classes.
     */
    public DatatypeOpenClass(String name, String packageName) {
        // NOTE! The instance class during the construction is null.
        // It will be set after the generating the appropriate byte code for the
        // datatype.
        // See {@link
        // org.openl.rules.datatype.binding.DatatypeTableBoundNode.addFields()}
        //
        // @author Denis Levchuk
        //
        // FIXME: instance class have to be defined to prevent multiple NPEs in CastFactory
        super(name, null);
        if (StringUtils.isBlank(packageName)) {
            javaName = name;
        } else {
            javaName = packageName + '.' + name;
        }
        this.packageName = packageName;
    }

    @Override
    public IAggregateInfo getAggregateInfo() {
        return DynamicArrayAggregateInfo.aggregateInfo;
    }

    public IOpenClass getSuperClass() {
        return superClass;
    }

    public void setSuperClass(IOpenClass superClass) {
        this.superClass = superClass;
    }

    @Override
    public Collection<IOpenClass> superClasses() {
        if (superClass != null) {
            return Collections.singletonList(superClass);
        } else {
            return Collections.emptyList();
        }
    }

    @Override
    public String getJavaName() {
        return javaName;
    }

    @Override
    public String getPackageName() {
        return packageName;
    }

    @Override
    public boolean isArray() {
        return false;
    }

    /**
     * Used {@link LinkedHashMap} to store fields in order as them defined in DataType table
     */
    @Override
    protected LinkedHashMap<String, IOpenField> fieldMap() {
        if (fieldMap == null) {
            fieldMap = new LinkedHashMap<>();
        }
        return (LinkedHashMap<String, IOpenField>) fieldMap;
    }

    private volatile Map<String, IOpenField> fields;
    private volatile Map<String, IOpenField> staticFields;

    @Override
    public Collection<IOpenField> getFields() {
        ensureFieldsInitialized();
        return Collections.unmodifiableCollection(this.fields.values());
    }

    private void ensureFieldsInitialized(){
        if (this.fields == null || this.staticFields == null) {
            synchronized (this) {
                if (this.fields == null || this.staticFields == null) {
                    initializeFields();
                }
            }
        }
    }

    private void initializeFields() {
        Map<String, IOpenField> fields = new LinkedHashMap<>();
        Map<String, IOpenField> staticFields = new LinkedHashMap<>();
        Iterable<IOpenClass> superClasses = superClasses();
        for (IOpenClass superClassValue : superClasses) {
            for (IOpenField field : superClassValue.getFields()) {
                fields.put(field.getName(), field);
            }
        }
        fieldMap().forEach(fields::putIfAbsent);
        staticFields.put("class", new JavaOpenClass.JavaClassClassField(instanceClass));
        this.fields = fields;
        this.staticFields = staticFields;
    }

    @Override
    public void addField(IOpenField field) throws DuplicatedFieldException {
        this.fields = null;
        super.addField(field);
        invalidateInternalData();
    }

    @Override
    public Collection<IOpenField> getDeclaredFields() {
        return Collections.unmodifiableCollection(fieldMap().values());
    }

    @Override
    public Object newInstance(IRuntimeEnv env) {
        Object instance = null;
        try {
            instance = getInstanceClass().newInstance();
        } catch (Exception e) {
            LOG.error("{}", this, e);
        }
        return instance;
    }

    @Override
    public IOpenClass getComponentClass() {
        return null;
    }

    /**
     * Override super class implementation to provide possibility to compare datatypes with info about their fields
     *
     * @author DLiauchuk
     */
    @Override
    public int hashCode() {
        return Objects.hash(superClass, javaName);
    }

    /**
     * Override super class implementation to provide possibility to compare datatypes with info about their fields Is
     * used in {@link XlsBinder} (method filterDependencyTypes)
     *
     * @author DLiauchuk
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!super.equals(obj)) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        DatatypeOpenClass other = (DatatypeOpenClass) obj;

        return Objects.equals(superClass, other.getSuperClass()) && Objects.equals(getMetaInfo(),
            other.getMetaInfo()) && Objects.equals(javaName, other.getJavaName());
    }

    @Override
    public String toString() {
        return javaName;
    }

    private IOpenMethod wrapDatatypeOpenMethod(IOpenMethod method) {
        if (method instanceof JavaOpenMethod) {
            JavaOpenMethod javaOpenMethod = (JavaOpenMethod) method;
            Method javaMethod = javaOpenMethod.getJavaMethod();
            for (IOpenField field : fieldMap().values()) {
                if (field instanceof DatatypeOpenField) {
                    DatatypeOpenField datatypeOpenField = (DatatypeOpenField) field;
                    if (datatypeOpenField.getGetter().equals(javaMethod)) {
                        return new DatatypeOpenMethod(javaOpenMethod,
                            this,
                            javaOpenMethod.getParameterTypes(),
                            field.getType());
                    }
                    if (datatypeOpenField.getSetter().equals(javaMethod)) {
                        IOpenClass[] parameterTypes = new IOpenClass[] { field.getType() };
                        return new DatatypeOpenMethod(javaOpenMethod, this, parameterTypes, javaOpenMethod.getType());
                    }
                }
            }
        }
        return method;
    }

    @Override
    protected Map<MethodKey, IOpenMethod> initMethodMap() {
        Map<MethodKey, IOpenMethod> methods = super.initMethodMap();
        Map<MethodKey, IOpenMethod> methodMap = new HashMap<>(OBJECT_CLASS_METHODS);

        for (Entry<MethodKey, IOpenMethod> m : methods.entrySet()) {
            IOpenMethod m1 = wrapDatatypeOpenMethod(m.getValue());
            if (m1 != m.getValue()) {
                methodMap.put(new MethodKey(m1), m1);
            } else {
                methodMap.put(m.getKey(), m.getValue());
            }
        }
        return methodMap;
    }

    @Override
    protected Map<MethodKey, IOpenMethod> initConstructorMap() {
        Map<MethodKey, IOpenMethod> constructors = super.initConstructorMap();
        Map<MethodKey, IOpenMethod> constructorMap = new HashMap<>(1);
        for (Entry<MethodKey, IOpenMethod> constructor : constructors.entrySet()) {
            IOpenMethod wrapped = wrapDatatypeOpenConstructor(constructor.getKey(), constructor.getValue());
            if (wrapped == constructor.getValue()) {
                constructorMap.put(constructor.getKey(), constructor.getValue());
            } else {
                constructorMap.put(new MethodKey(wrapped), wrapped);
            }
        }
        return constructorMap;
    }

    private IOpenMethod wrapDatatypeOpenConstructor(MethodKey mk, IOpenMethod method) {
        if (method instanceof JavaOpenConstructor && javaName.equals(method.getDeclaringClass().getJavaName())) {
            JavaOpenConstructor javaOpenConstructor = (JavaOpenConstructor) method;
            if (javaOpenConstructor.getNumberOfParameters() == 0) {
                return new DatatypeOpenConstructor(javaOpenConstructor, this);
            } else {
                MethodKey candidate = new MethodKey(
                    getFields().stream().map(IOpenMember::getType).toArray(IOpenClass[]::new));
                if (mk.equals(candidate)) {
                    ParameterDeclaration[] parameters = getFields().stream()
                        .map(f -> new ParameterDeclaration(f.getType(), f.getName()))
                        .toArray(ParameterDeclaration[]::new);
                    return new DatatypeOpenConstructor(javaOpenConstructor, this, parameters);
                }
            }
        }
        return method;
    }

    @Override
    public String getDisplayName(int mode) {
        if (mode == INamedThing.LONG) {
            return getPackageName() + "." + getName();
        }
        return getName();
    }

    public byte[] getBytecode() {
        return bytecode;
    }

    public void setBytecode(byte[] bytecode) {
        this.bytecode = bytecode;
    }

    private static final Map<MethodKey, IOpenMethod> OBJECT_CLASS_METHODS;

    static {
        Map<MethodKey, IOpenMethod> objectClassMethods = new HashMap<>();
        for (IOpenMethod m : JavaOpenClass.OBJECT.getMethods()) {
            objectClassMethods.put(new MethodKey(m), m);
        }
        OBJECT_CLASS_METHODS = Collections.unmodifiableMap(objectClassMethods);
    }

    public TableSyntaxNode getTableSyntaxNode() {
        return tableSyntaxNode;
    }

    public void setTableSyntaxNode(TableSyntaxNode tableSyntaxNode) {
        this.tableSyntaxNode = tableSyntaxNode;
    }

    @Override
    public IOpenField getStaticField(String fname) {
        ensureFieldsInitialized();
        return staticFields.get(fname);
    }

    @Override
    public Collection<IOpenField> getStaticFields() {
        ensureFieldsInitialized();
        return staticFields.values();
    }

    @Override
    public IOpenField getStaticField(String name, boolean strictMatch) {
        ensureFieldsInitialized();
        Optional<String> first = staticFields.keySet().stream().filter(f -> f.equalsIgnoreCase(name)).findFirst();
        return first.map(s -> staticFields.get(s)).orElse(null);
    }

    @Override
    protected void invalidateInternalData() {
        super.invalidateInternalData();
        synchronized (this) {
            this.fields = null;
            this.staticFields = null;
        }
    }
}
