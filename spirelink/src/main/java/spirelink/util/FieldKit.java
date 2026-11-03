package spirelink.util;

import basemod.ReflectionHacks;

import java.lang.reflect.Field;

/**
 * Field access that never throws. ReflectionHacks.getPrivate(obj, SomeClass.class, name) requires the
 * class that *declares* the field; passing a superclass for a subclass field kills the game loop with
 * a NoSuchFieldException. Everything reflective in this mod goes through here instead.
 */
public final class FieldKit {

    private static final java.util.Set<String> WARNED = new java.util.HashSet<String>();

    private FieldKit() {
    }

    /** Reads a field from the object's own class or any ancestor; null when absent. */
    public static Object fieldOrNull(Object target, String name) {
        if (target == null || name == null) {
            return null;
        }
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Throwable t) {
                warn(target, name, t);
                return null;
            }
        }
        warn(target, name, null);
        return null;
    }

    /** True when the field exists on the object's class or an ancestor. Never warns. */
    public static boolean hasField(Object target, String name) {
        if (target == null || name == null) {
            return false;
        }
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                clazz.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    /**
     * Silent read for optional fields (per-event diagnostics): returns null instead of warning, so
     * probing "does this event have startSpin?" cannot spray the log with 字段读取失败 lines.
     */
    public static Object peek(Object target, String name) {
        if (target == null || name == null) {
            return null;
        }
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** Static field of a class (RoomEventDialog.optionList and friends), null when absent. */
    public static Object staticFieldOrNull(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(null);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /**
     * Writes a field if it exists, without warning when it does not (for optional per-event fields
     * such as GremlinWheelGame.buttonPressed).
     */
    public static boolean setFieldQuiet(Object target, String name, Object value) {
        if (target == null || name == null) {
            return false;
        }
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return true;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    /** Writes a field found anywhere up the class hierarchy; false when absent or not writable. */
    public static boolean setFieldOrNull(Object target, String name, Object value) {
        if (target == null || name == null) {
            return false;
        }
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return true;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Throwable t) {
                warn(target, name, t);
                return false;
            }
        }
        warn(target, name, null);
        return false;
    }

    /** Records that a field was expected but missing, once per class+field. */
    public static void noteMissing(Object target, String name) {
        if (target != null) {
            warn(target, name, null);
        }
    }

    /** Same, but goes through BaseMod so it also sees BaseMod's own field cache. */
    public static Object viaBasemod(Object target, Class<?> declaring, String name) {
        try {
            return ReflectionHacks.getPrivate(target, declaring, name);
        } catch (Throwable t) {
            warn(target, name, t);
            return null;
        }
    }

    private static void warn(Object target, String name, Throwable t) {
        String key = target.getClass().getName() + "#" + name;
        synchronized (WARNED) {
            if (!WARNED.add(key)) {
                return;
            }
        }
        spirelink.util.Diag.warn("字段读取失败 " + key + (t == null ? "（不存在）" : "（" + t + "）"));
    }
}
