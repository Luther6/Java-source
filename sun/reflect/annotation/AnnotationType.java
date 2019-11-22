/*
 * Copyright (c) 2003, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.reflect.annotation;

import sun.misc.JavaLangAccess;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;
import java.security.AccessController;
import java.security.PrivilegedAction;

/**
 * Represents an annotation type at run time.  Used to type-check annotations
 * and apply member defaults.
 *
 * @author  Josh Bloch
 * @since   1.5
 */
public class AnnotationType {
    /**
     * Member name -> type mapping. Note that primitive types
     * are represented by the class objects for the corresponding wrapper
     * types.  This matches the return value that must be used for a
     * dynamic proxy, allowing for a simple isInstance test.
     */
    private final Map<String, Class<?>> memberTypes;

    /**
     * Member name -> default value mapping.
     */
    private final Map<String, Object> memberDefaults;

    /**
     * Member name -> Method object mapping. This (and its assoicated
     * accessor) are used only to generate AnnotationTypeMismatchExceptions.
     */
    private final Map<String, Method> members;

    /**
     * The retention policy for this annotation type.
     */
    private final RetentionPolicy retention;

    /**
     * Whether this annotation type is inherited.
     */
    private final boolean inherited;

    /**
     * Returns an AnnotationType instance for the specified annotation type.
     *
     * @throw IllegalArgumentException if the specified class object for
     *     does not represent a valid annotation type
     */
    public static AnnotationType getInstance(
        Class<? extends Annotation> annotationClass)
    {
        // 这个对象里包含了关于JVM底层的一些东西,我们只需要知道这里可以通过一个注解Class对象能够获取注解的类型
        JavaLangAccess jla = sun.misc.SharedSecrets.getJavaLangAccess();
        // 因为是我们的自定义注解所以我们这里是为null
        AnnotationType result = jla.getAnnotationType(annotationClass); // volatile read
        if (result == null) {
            // 在这里上生成了注解的类型,继续分析这个注解类型类的构造方法
            result = new AnnotationType(annotationClass);
            // try to CAS the AnnotationType: null -> result 缓存生成的自定义注解
            if (!jla.casAnnotationType(annotationClass, null, result)) {
                // somebody was quicker -> read it's result
                result = jla.getAnnotationType(annotationClass);
                assert result != null;
            }
        }

        return result;
    }

    /**
     * Sole constructor.
     *
     * @param annotationClass the class object for the annotation type
     * @throw IllegalArgumentException if the specified class object for
     *     does not represent a valid annotation type
     */
    private AnnotationType(final Class<? extends Annotation> annotationClass) {
        if (!annotationClass.isAnnotation())
            throw new IllegalArgumentException("Not an annotation type");
        // 这里是用来获取当前注解的属性对象,注解的属性在底层是使用方法对象来表示的
        Method[] methods =
            AccessController.doPrivileged(new PrivilegedAction<Method[]>() {
                public Method[] run() {
                    // Initialize memberTypes and defaultValues
                    return annotationClass.getDeclaredMethods();
                }
            });
        // 这个注解类型对象会缓存该注解的属性类型与属性值等内容,这个map用来缓存我们注解对象的各个属性的返回值类型
        memberTypes = new HashMap<String,Class<?>>(methods.length+1, 1.0f);
        // 这个Map用来缓存注解对象的属性的默认值(对应于我们@CustomerAnnotation就是value 的default 的内容)
        memberDefaults = new HashMap<String, Object>(0);
        // 这个map用来缓存当前注解对象的方法对象(对应于我们@CustomerAnnotation就是value方法)
        members = new HashMap<String, Method>(methods.length+1, 1.0f);

        for (Method method :  methods) {
            if (method.getParameterTypes().length != 0)
                throw new IllegalArgumentException(method + " has params");
            String name = method.getName();
            Class<?> type = method.getReturnType();
            // 把基本数据类型对象转为对应的类对象,并存储,注意这里的Map的key都是对应着我们的方法名称(value)
            memberTypes.put(name, invocationHandlerReturnType(type));
            members.put(name, method);
            // 判断是否有默认值,底层任然是通过某种规则找到当前注解对象的常量池的索引
            Object defaultValue = method.getDefaultValue();
            if (defaultValue != null)
                memberDefaults.put(name, defaultValue);
        }

        // Initialize retention, & inherited fields.  Special treatment
        // of the corresponding annotation types breaks infinite recursion.
        // 用来判断是否是Java中的Retention与Inherited注解对象
        if (annotationClass != Retention.class &&
            annotationClass != Inherited.class) {
            JavaLangAccess jla = sun.misc.SharedSecrets.getJavaLangAccess();
            // 获取当前注解的元注解,你会看到这里Java的元注解还是通过动态生成的,而不是与生俱来的(这里还是会走我们自己自定义注解的生成路径)
            Map<Class<? extends Annotation>, Annotation> metaAnnotations =
                AnnotationParser.parseSelectAnnotations(
                    jla.getRawClassAnnotations(annotationClass),
                    jla.getConstantPool(annotationClass),
                    annotationClass,
                    Retention.class, Inherited.class
                );
            Retention ret = (Retention) metaAnnotations.get(Retention.class);
            // 这里就是用来确认当前注解的存活期,如果没有指定当前注解的存活期的话,那么指定默认的存活期为Class
            retention = (ret == null ? RetentionPolicy.CLASS : ret.value());
            // 查询当前注解是否可以被继承
            inherited = metaAnnotations.containsKey(Inherited.class);
        }
        else {
            // 元注解的存活期默认是RUNTIME并且是不可继承的
            retention = RetentionPolicy.RUNTIME;
            inherited = false;
        }
    }

    /**
     * Returns the type that must be returned by the invocation handler
     * of a dynamic proxy in order to have the dynamic proxy return
     * the specified type (which is assumed to be a legal member type
     * for an annotation).
     */
    public static Class<?> invocationHandlerReturnType(Class<?> type) {
        // Translate primitives to wrappers
        if (type == byte.class)
            return Byte.class;
        if (type == char.class)
            return Character.class;
        if (type == double.class)
            return Double.class;
        if (type == float.class)
            return Float.class;
        if (type == int.class)
            return Integer.class;
        if (type == long.class)
            return Long.class;
        if (type == short.class)
            return Short.class;
        if (type == boolean.class)
            return Boolean.class;

        // Otherwise, just return declared type
        return type;
    }

    /**
     * Returns member types for this annotation type
     * (member name -> type mapping).
     */
    public Map<String, Class<?>> memberTypes() {
        return memberTypes;
    }

    /**
     * Returns members of this annotation type
     * (member name -> associated Method object mapping).
     */
    public Map<String, Method> members() {
        return members;
    }

    /**
     * Returns the default values for this annotation type
     * (Member name -> default value mapping).
     */
    public Map<String, Object> memberDefaults() {
        return memberDefaults;
    }

    /**
     * Returns the retention policy for this annotation type.
     */
    public RetentionPolicy retention() {
        return retention;
    }

    /**
     * Returns true if this this annotation type is inherited.
     */
    public boolean isInherited() {
        return inherited;
    }

    /**
     * For debugging.
     */
    public String toString() {
        return "Annotation Type:\n" +
               "   Member types: " + memberTypes + "\n" +
               "   Member defaults: " + memberDefaults + "\n" +
               "   Retention policy: " + retention + "\n" +
               "   Inherited: " + inherited;
    }
}
