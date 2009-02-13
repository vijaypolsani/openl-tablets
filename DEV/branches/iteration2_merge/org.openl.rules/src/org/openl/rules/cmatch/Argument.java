package org.openl.rules.cmatch;

import org.openl.types.IOpenClass;
import org.openl.types.IOpenField;
import org.openl.vm.IRuntimeEnv;

public class Argument {
    private int index;
    /**
     * Type of argument
     */
    private IOpenClass type;
    private IOpenField field;
    
    public Argument(int index, IOpenClass type) {
        this.index = index;
        this.type = type;
    }

    public Argument(int index, IOpenField field) {
        this.index = index;
        this.type = field.getDeclaringClass();
        this.field = field;
    }

    public Object extractValue(Object target, Object[] params, IRuntimeEnv env) {
        if (field == null) {
            return params[index];
        } else {
            return field.get(target, env);
        }
    }

    public IOpenClass getType() {
        return type;
    }
}
