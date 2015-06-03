package org.openl.rules.lang.xls.binding.delegate;

import java.util.List;

import org.openl.rules.lang.xls.binding.XlsModuleOpenClass;
import org.openl.rules.lang.xls.syntax.TableSyntaxNode;
import org.openl.rules.types.impl.OverloadedMethodsDispatcherTable;
import org.openl.types.IMemberMetaInfo;
import org.openl.types.IMethodSignature;
import org.openl.types.IOpenClass;
import org.openl.types.IOpenMethod;
import org.openl.vm.IRuntimeEnv;

public class OverloadedMethodsDispatcherTableDelegate extends OverloadedMethodsDispatcherTable implements DispatchDelegateOpenMethod{
    OverloadedMethodsDispatcherTable delegate;
    XlsModuleOpenClass xlsModuleOpenClass;
    
    public OverloadedMethodsDispatcherTableDelegate(XlsModuleOpenClass xlsModuleOpenClass, OverloadedMethodsDispatcherTable delegate) {
        this.delegate = delegate;
        this.xlsModuleOpenClass = xlsModuleOpenClass;
    }
    
    public Object invoke(Object target, Object[] params, IRuntimeEnv env) {
        return DispatcherLogic.dispatch(xlsModuleOpenClass, delegate, target, params, env);
    }

    public int hashCode() {
        return delegate.hashCode();
    }

    public IOpenMethod getDispatchingOpenMethod() {
        return delegate.getDispatchingOpenMethod();
    }

    public void setDispatchingOpenMethod(IOpenMethod dispatchingOpenMethod) {
        delegate.setDispatchingOpenMethod(dispatchingOpenMethod);
    }

    public TableSyntaxNode getDispatcherTable() {
        return delegate.getDispatcherTable();
    }

    public IMethodSignature getSignature() {
        return delegate.getSignature();
    }

    public IOpenClass getDeclaringClass() {
        return delegate.getDeclaringClass();
    }

    public void addMethod(IOpenMethod candidate) {
        delegate.addMethod(candidate);
    }

    public IOpenClass getType() {
        return delegate.getType();
    }

    public boolean isStatic() {
        return delegate.isStatic();
    }

    public String getDisplayName(int mode) {
        return delegate.getDisplayName(mode);
    }

    public String getName() {
        return delegate.getName();
    }

    public IOpenMethod getMethod() {
        return delegate.getMethod();
    }

    public boolean equals(Object obj) {
        return delegate.equals(obj);
    }

    public IMemberMetaInfo getInfo() {
        return delegate.getInfo();
    }

    public String toString() {
        return delegate.toString();
    }

    public IOpenMethod getTargetMethod() {
        return delegate.getTargetMethod();
    }

    public List<IOpenMethod> getCandidates() {
        return delegate.getCandidates();
    }
}
