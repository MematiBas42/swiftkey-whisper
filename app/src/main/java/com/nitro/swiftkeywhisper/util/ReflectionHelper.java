package com.nitro.swiftkeywhisper.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ReflectionHelper {

    public static Object getObjectField(Object obj, String fieldName) {
        if (obj == null || fieldName == null) return null;
        try {
            Field field = findField(obj.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                return field.get(obj);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        Object val = getObjectField(obj, fieldName);
        return val instanceof Boolean && (Boolean) val;
    }

    public static void setBooleanField(Object obj, String fieldName, boolean value) {
        if (obj == null || fieldName == null) return;
        try {
            Field field = findField(obj.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                field.setBoolean(obj, value);
            }
        } catch (Throwable ignored) {}
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        if (obj == null || methodName == null) return null;
        try {
            Method method = findMethod(obj.getClass(), methodName, args);
            if (method != null) {
                method.setAccessible(true);
                return method.invoke(obj, args);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {}
            current = current.getSuperclass();
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String methodName, Object... args) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method m : current.getDeclaredMethods()) {
                if (m.getName().equals(methodName)) {
                    Class<?>[] paramTypes = m.getParameterTypes();
                    if (args == null || args.length == 0) {
                        if (paramTypes.length == 0) return m;
                    } else if (paramTypes.length == args.length) {
                        boolean match = true;
                        for (int i = 0; i < args.length; i++) {
                            if (args[i] != null && !isAssignable(paramTypes[i], args[i].getClass())) {
                                match = false;
                                break;
                            }
                        }
                        if (match) return m;
                    }
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static boolean isAssignable(Class<?> targetType, Class<?> fromType) {
        if (targetType.isAssignableFrom(fromType)) return true;
        if (targetType.isPrimitive()) {
            if (targetType == int.class && fromType == Integer.class) return true;
            if (targetType == boolean.class && fromType == Boolean.class) return true;
            if (targetType == float.class && fromType == Float.class) return true;
            if (targetType == long.class && fromType == Long.class) return true;
            if (targetType == double.class && fromType == Double.class) return true;
            if (targetType == byte.class && fromType == Byte.class) return true;
            if (targetType == short.class && fromType == Short.class) return true;
            if (targetType == char.class && fromType == Character.class) return true;
        }
        return false;
    }
}
