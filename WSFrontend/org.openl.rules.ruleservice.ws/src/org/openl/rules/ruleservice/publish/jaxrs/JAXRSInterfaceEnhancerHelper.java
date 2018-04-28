package org.openl.rules.ruleservice.publish.jaxrs;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.cxf.jaxrs.ext.xml.ElementClass;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.openl.rules.datatype.gen.FieldDescription;
import org.openl.rules.datatype.gen.JavaBeanClassBuilder;
import org.openl.rules.ruleservice.core.OpenLService;
import org.openl.rules.ruleservice.core.RuleServiceRuntimeException;
import org.openl.rules.ruleservice.publish.common.MethodUtil;
import org.openl.util.ClassUtils;
import org.openl.util.StringUtils;
import org.openl.util.generation.InterfaceTransformer;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

/**
 * Utility class for generate JAXRS annotations for service interface.
 * 
 * @author Marat Kamalov
 *
 */
public class JAXRSInterfaceEnhancerHelper {

    private static boolean isPrimitive(Class<?> type) {
        return type.isPrimitive();
    }

    private static class JAXRSInterfaceAnnotationEnhancerClassVisitor extends ClassVisitor {

        private static final int MAX_PARAMETERS_COUNT_FOR_GET = 4;

        private static final String DECORATED_CLASS_NAME_SUFFIX = "$JAXRSAnnotated";

        private Class<?> originalClass;
        private OpenLService service;
        private boolean changeReturnTypes = true;
        private Map<Method, String> methodNames = null;
        private Map<Method, String> paths = null;
        private Map<Method, String> methodRequests = null;

        public JAXRSInterfaceAnnotationEnhancerClassVisitor(ClassVisitor arg0,
                Class<?> originalClass,
                OpenLService service,
                boolean changeReturnTypes) {
            super(Opcodes.ASM4, arg0);
            this.originalClass = originalClass;
            this.changeReturnTypes = changeReturnTypes;
            this.service = service;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            super.visit(version, access, name, signature, superName, interfaces);
            boolean requiredPathAnnotation = true;
            boolean consumesAnnotationRequired = true;
            boolean producesAnnotationRequired = true;

            for (Annotation annotation : originalClass.getAnnotations()) {

                if (annotation.annotationType().equals(Produces.class)) {
                    producesAnnotationRequired = false;
                }
                if (annotation.annotationType().equals(Consumes.class)) {
                    consumesAnnotationRequired = false;
                }

                if (annotation.annotationType().equals(Path.class)) {
                    requiredPathAnnotation = false;
                    break;
                }
            }
            
            //Swagger annotation
            if (originalClass.getAnnotation(Api.class) == null) {
                this.visitAnnotation(Type.getDescriptor(Api.class), true);
            }

            if (requiredPathAnnotation) {
                AnnotationVisitor annotationVisitor = this.visitAnnotation(Type.getDescriptor(Path.class), true);
                annotationVisitor.visit("value", "/");
                annotationVisitor.visitEnd();
            }
            
            // Consumes annotation
            if (consumesAnnotationRequired) {
                addConsumesAnnotation(this);
            }
            // Produces annotation
            if (producesAnnotationRequired) {
                addProducesAnnotation(this);
            }
        }

        private String changeReturnType(String signature) {
            int index = signature.lastIndexOf(')');
            return signature.substring(0, index + 1) + Type.getDescriptor(Response.class);
        }

        private String changeArgumentTypes(String signature, Method originalMethod) throws Exception {
            String[] parameterNames = MethodUtil.getParameterNames(originalMethod, service);
            int i = 0;
            Map<String, Class<?>> props = new HashMap<>();
            for (Class<?> type : originalMethod.getParameterTypes()) {
                props.put(parameterNames[i], type);
                i++;
            }
            ClassLoader classLoader = getClassLoader();
            String requestParameterName = getRequestParameterName(originalMethod);
            Class<?> argumentWrapperClass = generateClass(requestParameterName, props, classLoader);
            int index = signature.lastIndexOf(')');
            int indexb = signature.lastIndexOf('(');
            return signature.substring(0, indexb + 1) + Type.getDescriptor(argumentWrapperClass) + signature.substring(index);
        }

        private static Class<?> generateClass(String prefix, Map<String, Class<?>> props, ClassLoader classLoader) throws Exception {
            String beanName = "org.openl.rules.ruleservice.publish.jaxrs." + prefix;
            JavaBeanClassBuilder beanClassBuilder = new JavaBeanClassBuilder(beanName);

            for (String name : props.keySet()) {
                beanClassBuilder.addField(name,  new FieldDescription(props.get(name).getName()));
            }
            byte[] byteCode = beanClassBuilder.byteCode();
            Class<?> aClass = ClassUtils.defineClass(beanName, byteCode, classLoader);

            return aClass;
        }

        private ClassLoader getClassLoader() {
            ClassLoader classLoader = null;
            if (service != null) {
                classLoader = service.getClassLoader();
            }
            if (classLoader == null) {
                classLoader = Thread.currentThread().getContextClassLoader();
            }
            return classLoader;
        }

        String getRequestParameterName(Method method) {
            if (methodRequests == null) {
                methodRequests = new HashMap<>();
                List<Method> methods = new ArrayList<Method>();
                for (Method m : originalClass.getMethods()) {
                    methods.add(m);
                }

                methods = MethodUtil.sort(methods);

                for (Method m : methods) {
                    String name = StringUtils.capitalize(m.getName()) + "Request";
                    String s = name;
                    int i = 1;
                    while (methodRequests.values().contains(s)) {
                        s = name + "Request" + i;
                        i++;
                    }
                    methodRequests.put(m, s);
                }
            }

            return methodRequests.get(method);
        }

        protected String getPath(Method method) {
            if (paths == null) {
                paths = new HashMap<Method, String>();
                List<Method> methods = new ArrayList<Method>();
                for (Method m : originalClass.getMethods()) {
                    Annotation pathAnnotation = m.getAnnotation(Path.class);
                    if (pathAnnotation != null) {
                        String value = ((Path) pathAnnotation).value();

                        while (value.charAt(0) == '/') {
                            value = value.substring(1);
                        }

                        if (value.indexOf('/') > 0) {
                            value = value.substring(0, value.indexOf('/'));
                        }

                        paths.put(m, value);
                    } else {
                        methods.add(m);
                    }
                }

                methods = MethodUtil.sort(methods);

                for (Method m : methods) {
                    String s = m.getName();
                    int i = 1;
                    while (paths.values().contains(s)) {
                        s = m.getName() + i;
                        i++;
                    }
                    paths.put(m, s);
                }
            }

            return paths.get(method);
        }

        protected String getMethodName(Method method) {
            if (methodNames == null) {
                methodNames = new HashMap<Method, String>();
                List<Method> methods = new ArrayList<Method>();
                for (Method m : originalClass.getMethods()) {
                    methods.add(m);
                }

                methods = MethodUtil.sort(methods);

                for (Method m : methods) {
                    String s = m.getName();
                    int i = 1;
                    while (methodNames.values().contains(s)) {
                        s = m.getName() + i;
                        i++;
                    }
                    methodNames.put(m, s);
                }
            }
            return methodNames.get(method);
        }

        @Override
        public MethodVisitor visitMethod(int arg0, String methodName, String arg2, String arg3, String[] arg4) {
            Method originalMethod = findOriginalMethod(methodName, arg2);
            if (originalMethod == null) {
                throw new RuleServiceRuntimeException("Method is not found in the original class!");
            }

            boolean skip = false;
            if (originalMethod.getAnnotation(Path.class) != null) {
                skip = true;
            }
            if (originalMethod.getAnnotation(POST.class) != null) {
                skip = true;
            }
            if (originalMethod.getAnnotation(GET.class) != null) {
                skip = true;
            }

            MethodVisitor mv = null;
            if (!skip) {
                boolean allParametersIsPrimitive = true;
                if (originalMethod.getParameterTypes().length < MAX_PARAMETERS_COUNT_FOR_GET) {
                    for (Class<?> parameterType : originalMethod.getParameterTypes()) {
                        if (!isPrimitive(parameterType)) {
                            allParametersIsPrimitive = false;
                            break;
                        }
                    }
                }
                StringBuilder sb = new StringBuilder();
                sb.append("/" + getPath(originalMethod));
                if (originalMethod.getParameterTypes().length < MAX_PARAMETERS_COUNT_FOR_GET && allParametersIsPrimitive) {
                    if (changeReturnTypes && !originalMethod.getReturnType().equals(Response.class)) {
                        mv = super.visitMethod(arg0, getMethodName(originalMethod), changeReturnType(arg2), arg3, arg4);
                    } else {
                        mv = super.visitMethod(arg0, getMethodName(originalMethod), arg2, arg3, arg4);
                    }
                    String[] parameterNames = MethodUtil.getParameterNames(originalMethod, service);
                    int i = 0;
                    for (String paramName : parameterNames) {
                        sb.append("/{" + paramName + ": .*}");
                        addPathParamAnnotation(mv, i, paramName);
                        i++;
                    }
                    addGetAnnotation(mv);
                    addPathAnnotation(mv, sb.toString());
                } else {
                    try {
                        if (changeReturnTypes && !originalMethod.getReturnType().equals(Response.class)) {
                            if (originalMethod.getParameterTypes().length > 1 && originalMethod.getParameterTypes().length != 0) {
                                mv = super.visitMethod(arg0,
                                    getMethodName(originalMethod),
                                    changeArgumentTypes(changeReturnType(arg2), originalMethod),
                                    arg3,
                                    arg4);
                            } else {
                                mv = super.visitMethod(arg0,
                                    getMethodName(originalMethod),
                                    changeReturnType(arg2),
                                    arg3,
                                    arg4);
                            }
                            annotateReturnElementClass(mv, originalMethod.getReturnType());
                        } else {
                            if (originalMethod.getParameterTypes().length > 1 && originalMethod.getParameterTypes().length != 0) {
                                mv = super.visitMethod(arg0,
                                    getMethodName(originalMethod),
                                    changeArgumentTypes(arg2, originalMethod),
                                    arg3,
                                    arg4);
                            } else {
                                mv = super.visitMethod(arg0, getMethodName(originalMethod), arg2, arg3, arg4);
                            }
                        }
                        addPostAnnotation(mv);
                        addPathAnnotation(mv, sb.toString());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            } else {
                if (changeReturnTypes && !originalMethod.getReturnType().equals(Response.class)) {
                    mv = super.visitMethod(arg0, getMethodName(originalMethod), changeReturnType(arg2), arg3, arg4);
                } else {
                    mv = super.visitMethod(arg0, getMethodName(originalMethod), arg2, arg3, arg4);
                }
                
                //Parameter annotations process, because InterfaceTransformet skip them
                //Need refactoring.
                if (originalMethod.getParameterAnnotations().length > 0) {
                    int index = 0;
                    for (Annotation[] annotatons : originalMethod.getParameterAnnotations()) {
                        for (int j = 0; j < annotatons.length; j++) {
                            AnnotationVisitor av = mv.visitParameterAnnotation(index,
                                Type.getDescriptor(annotatons[j].annotationType()),
                                true);
                            InterfaceTransformer.processAnnotation(annotatons[j], av);
                        }
                        index++;
                    }
                }
            }

            addJAXRSMethodAnnotation(mv, originalMethod.getName());
            
            addSwaggerMethodAnnotation(mv, originalMethod);
            return mv;
        }
        
        private Method findOriginalMethod(String methodName, String argumentTypes) {
            Method originalMethod = null;
            for (Method method : originalClass.getMethods()) {
                if (originalMethod == null && methodName.equals(method.getName())) {
                    Type[] typesInOriginalClassMethod = Type.getArgumentTypes(method);
                    Type[] typesInCurrentMethod = Type.getArgumentTypes(argumentTypes);
                    if (typesInCurrentMethod.length == typesInOriginalClassMethod.length) {
                        boolean f = true;
                        for (int i = 0; i < typesInCurrentMethod.length; i++) {
                            if (!typesInCurrentMethod[i].equals(typesInOriginalClassMethod[i])) {
                                f = false;
                            }
                        }
                        if (f) {
                            originalMethod = method;
                        }
                    }
                }
            }
            return originalMethod;
        }

        private void annotateReturnElementClass(MethodVisitor mv, Class<?> returnType) {
            if (returnType.equals(Object.class) || returnType.equals(Void.class)){
                return;
            }
            AnnotationVisitor av = mv.visitAnnotation(Type.getDescriptor(ElementClass.class), true);
            av.visit("response", Type.getType(returnType));
            av.visitEnd();
        }
        
        private void addPostAnnotation(MethodVisitor mv) {
            AnnotationVisitor av = mv.visitAnnotation(Type.getDescriptor(POST.class), true);
            av.visitEnd();
        }

        private void addGetAnnotation(MethodVisitor mv) {
            AnnotationVisitor av = mv.visitAnnotation(Type.getDescriptor(GET.class), true);
            av.visitEnd();
        }

        private void addPathAnnotation(MethodVisitor mv, String path) {
            AnnotationVisitor av = mv.visitAnnotation(Type.getDescriptor(Path.class), true);
            av.visit("value", path);
            av.visitEnd();
        }

        private void addJAXRSMethodAnnotation(MethodVisitor mv, String methodName) {
            AnnotationVisitor av = mv.visitAnnotation(Type.getDescriptor(JAXRSMethod.class), true);
            av.visit("value", methodName);
            av.visitEnd();
        }
        
        private void addSwaggerMethodAnnotation(MethodVisitor mv, Method originalMethod) {
            if (!originalMethod.isAnnotationPresent(ApiOperation.class)) {
                AnnotationVisitor av = mv.visitAnnotation(Type.getDescriptor(ApiOperation.class), true);
                av.visit("value", "Method: " + originalMethod.getName());
                av.visitEnd();
            }
        }

        private void addPathParamAnnotation(MethodVisitor mv, int index, String paramName) {
            AnnotationVisitor av = mv.visitParameterAnnotation(index, Type.getDescriptor(PathParam.class), true);
            av.visit("value", paramName);
            av.visitEnd();
        }
        
        private void addProducesAnnotation(ClassVisitor cv) {
            AnnotationVisitor av = cv.visitAnnotation(Type.getDescriptor(Produces.class), true);
            AnnotationVisitor av1 = av.visitArray("value");
            av1.visit(null, MediaType.APPLICATION_JSON);
            av1.visitEnd();
            av.visitEnd();
        }

        private void addConsumesAnnotation(ClassVisitor cv) {
            AnnotationVisitor av = cv.visitAnnotation(Type.getDescriptor(Consumes.class), true);
            AnnotationVisitor av1 = av.visitArray("value");
            av1.visit(null, MediaType.APPLICATION_JSON);
            av1.visitEnd();
            av.visitEnd();
        }
    }

    public static Class<?> decorateInterface(Class<?> originalClass, OpenLService service, boolean changeReturnTypes) throws Exception {
        if (originalClass == null) {
            throw new IllegalArgumentException("Original class is mandatory argument!");
        }
        if (!originalClass.isInterface()) {
            throw new IllegalArgumentException("Original class must be an interface!");
        }
        ClassWriter cw = new ClassWriter(0);
        JAXRSInterfaceAnnotationEnhancerClassVisitor jaxrsAnnotationEnhancerClassVisitor = new JAXRSInterfaceAnnotationEnhancerClassVisitor(cw,
            originalClass,
            service,
            changeReturnTypes);
        String enchancedClassName = originalClass.getCanonicalName() + JAXRSInterfaceAnnotationEnhancerClassVisitor.DECORATED_CLASS_NAME_SUFFIX;
        // Fix an NPE issue JAXRSUtil with no package class
        if (originalClass.getPackage() == null) {
            enchancedClassName = "default." + enchancedClassName;
        }
        InterfaceTransformer transformer = new InterfaceTransformer(originalClass, enchancedClassName, false);
        transformer.accept(jaxrsAnnotationEnhancerClassVisitor);
        cw.visitEnd();
        ClassLoader classLoader = getClassLoader(service);
        Class<?> enchancedClass = ClassUtils.defineClass(enchancedClassName, cw.toByteArray(), classLoader);
        return enchancedClass;
    }

    
    private static ClassLoader getClassLoader(OpenLService service) {
        ClassLoader classLoader = null;
        if (service != null) {
            classLoader = service.getClassLoader();
        }
        if (classLoader == null) {
            classLoader = Thread.currentThread().getContextClassLoader();
        }
        return classLoader;
    }

    public static Object decorateBean(Object targetBean,
            OpenLService service,
            Class<?> proxyInterface,
            Class<?> targetInterface) throws Exception {
        Map<Method, Method> methodMap = new HashMap<>();
        Map<Method, PropertyDescriptor[]> methodMapToPropertyDescriptors = new HashMap<>();
        Method[] targetMethods = targetInterface.getMethods();
        for (Method method : proxyInterface.getMethods()) {
            Annotation jaxRSMethod = method.getAnnotation(JAXRSMethod.class);
            if (jaxRSMethod == null) {
                throw new IllegalStateException("Proxy interface must contain JAXRSMethod annotation for each method!");
            }
            String methodName = ((JAXRSMethod) jaxRSMethod).value();
            Class<?>[] parameterTypes = method.getParameterTypes();

            boolean found;
            try {
                Method targetMethod = targetInterface.getMethod(methodName, parameterTypes);
                methodMap.put(method, targetMethod);
                found = true;
            } catch (NoSuchMethodException ex) {
                found = false;
            }
            if (!found && parameterTypes.length == 1) {
                Class<?> methodArgument = parameterTypes[0];
                BeanInfo beanInfo = Introspector.getBeanInfo(methodArgument);
                PropertyDescriptor[] tmpPropertyDescriptors = beanInfo.getPropertyDescriptors();
                PropertyDescriptor[] propertyDescriptors = new PropertyDescriptor[tmpPropertyDescriptors.length - 1];
                int p = 0;
                for (PropertyDescriptor tmpPropertyDescriptor : tmpPropertyDescriptors) {
                    if (!tmpPropertyDescriptor.getName().equals("class")) {
                        propertyDescriptors[p] = tmpPropertyDescriptor;
                        p++;
                    }
                }
                mainloop: for (Method targetMethod : targetMethods) {
                    if (targetMethod.getName().equals(methodName)) {
                        if (targetMethod.getParameterTypes().length == propertyDescriptors.length) {
                            Class<?>[] targetParams = targetMethod.getParameterTypes();
                            String[] paramNames = MethodUtil.getParameterNames(targetMethod, service);
                            Class<?>[] params = new Class<?>[propertyDescriptors.length];
                            PropertyDescriptor[] propertyDescriptorsForMap = new PropertyDescriptor[tmpPropertyDescriptors.length - 1];
                            for (int j = 0; j < propertyDescriptors.length; j++) {
                                int k = -1;
                                for (int q = 0; q < paramNames.length; q++) {
                                    if (paramNames[q].equals(ClassUtils.decapitalize(propertyDescriptors[j].getName()))) {
                                        k = q;
                                        break;
                                    }
                                }
                                if (k >= 0) {
                                    params[k] = propertyDescriptors[j].getPropertyType();
                                    propertyDescriptorsForMap[k] = propertyDescriptors[j];
                                } else {
                                    continue mainloop;
                                }
                            }
                            boolean f = true;
                            for (int i = 0; i < targetParams.length; i++) {
                                if (!targetParams[i].equals(params[i])) {
                                    f = false;
                                    break;
                                }
                            }
                            if (f) {
                                methodMap.put(method, targetMethod);
                                methodMapToPropertyDescriptors.put(method, propertyDescriptorsForMap);
                                found = true;
                                break;
                            }
                        }
                    }
                }
                if (!found) {
                   throw new IllegalStateException("Method is not found!");
                }
            }
        }

        ClassLoader classLoader = getClassLoader(service);

        return Proxy.newProxyInstance(classLoader,
            new Class<?>[] { proxyInterface },
            new JAXRSInvocationHandler(targetBean, methodMap, methodMapToPropertyDescriptors));
    }
}
