/*
 * Created on Mon Feb 06 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import sun.misc.Unsafe;

class ProcessorUtil {

    @SuppressWarnings({ "sunapi", "all" })
    public static void disableIllegalAccessWarning() {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Unsafe u = (Unsafe) theUnsafe.get(null);

            Class<?> cls = Class.forName("jdk.internal.module.IllegalAccessLogger");

            Field logger = cls.getDeclaredField("logger");
            u.putObjectVolatile(cls, u.staticFieldOffset(logger), null);
        } catch (Throwable t) {
        }
    }

    public static void addOpens(String[] packages) {
        class Dummy {
            @SuppressWarnings("unused")
            boolean first;
        }

        Class<?> cModule;
        try {
            cModule = Class.forName("java.lang.Module");
        } catch (ClassNotFoundException e) {
            return; // jdk8-; this is not needed.
        }

        Unsafe unsafe = getUnsafe();
        Object jdkCompilerModule = getJdkCompilerModule();
        Module ownModule = GeneratorProcessor.class.getModule();

        try {
            Method m = cModule.getDeclaredMethod("implAddOpens", String.class, cModule);

            long firstFieldOffset = unsafe.objectFieldOffset(Dummy.class.getDeclaredField("first"));
            unsafe.putBooleanVolatile(m, firstFieldOffset, true);

            for (String p : packages) {
                m.invoke(jdkCompilerModule, p, ownModule);
            }
        } catch (Exception ignore) {
        }
    }

    private static Unsafe getUnsafe() {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return (Unsafe) theUnsafe.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object getJdkCompilerModule() {
        try {
            Class.forName("java.lang.ModuleLayer");

            return ModuleLayer.boot().findModule("jdk.compiler").get();
        } catch (Exception e) {
            return null;
        }
    }
}
